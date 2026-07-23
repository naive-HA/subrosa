package com.yubico.yubikit.android.transport.usb.connection;

import com.yubico.yubikit.core.internal.Logger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.slf4j.LoggerFactory;

class T1Protocol {

  interface BlockTransport {
    byte[] transceiveBlock(byte[] block) throws IOException;
  }

  private static final int DEFAULT_IFSC = 254;
  private static final int FALLBACK_IFSC = 32; // ISO/IEC 7816-3 spec-default IFSC
  private static final int MAX_RETRANSMITS = 3;

  private static final byte PCB_I_TOP_BIT = (byte) 0x80;
  private static final byte PCB_RS_MASK = (byte) 0xC0;
  private static final byte PCB_R_TYPE = (byte) 0x80;
  private static final byte PCB_WTX_REQUEST = (byte) 0xC3;
  private static final byte PCB_WTX_RESPONSE = (byte) 0xE3;

  private final BlockTransport transport;
  private int outboundChunkSize;

  private byte nsSend = 0;

  private static final org.slf4j.Logger logger = LoggerFactory.getLogger(T1Protocol.class);

  T1Protocol(BlockTransport transport) {
    this(transport, DEFAULT_IFSC);
  }

  T1Protocol(BlockTransport transport, int outboundChunkSize) {
    this.transport = transport;
    this.outboundChunkSize = outboundChunkSize > 0 ? outboundChunkSize : DEFAULT_IFSC;
  }

  byte[] transceiveApdu(byte[] apdu) throws IOException {
    try {
      return chunkAndSend(apdu, outboundChunkSize);
    } catch (FirstChunkRejected e) {
      if (outboundChunkSize == FALLBACK_IFSC) {
        throw e.ioCause();
      }
      Logger.warn(
          logger,
          "T=1: first chunk (size={}) was repeatedly rejected -- retrying this and all "
              + "future writes on this connection at the ISO/IEC 7816-3 default IFSC ({})",
          outboundChunkSize,
          FALLBACK_IFSC);
      outboundChunkSize = FALLBACK_IFSC;
      try {
        return chunkAndSend(apdu, outboundChunkSize);
      } catch (FirstChunkRejected e2) {
        throw e2.ioCause();
      }
    }
  }

  private byte[] chunkAndSend(byte[] apdu, int chunkSize) throws IOException {
    int offset = 0;
    do {
      int chunkLen = Math.min(chunkSize, apdu.length - offset);
      boolean more = offset + chunkLen < apdu.length;
      byte[] chunk = Arrays.copyOfRange(apdu, offset, offset + chunkLen);
      boolean isFirstChunk = offset == 0;
      offset += chunkLen;

      ParsedBlock response;
      try {
        response = sendIBlockWithRetry(chunk, more);
      } catch (IOException e) {
        if (isFirstChunk) {
          throw new FirstChunkRejected(e);
        }
        throw e;
      }

      if (more) {
        if (!response.isR()) {
          throw new IOException(
              "T=1: expected R-block during outbound chaining, got PCB=0x"
                  + Integer.toHexString(response.pcb & 0xFF));
        }
        continue;
      }
      return receiveChainedResponse(response);
    } while (true);
  }

  private static class FirstChunkRejected extends IOException {
    FirstChunkRejected(IOException cause) {
      super(cause);
    }

    IOException ioCause() {
      return (IOException) getCause();
    }
  }

  private byte[] receiveChainedResponse(ParsedBlock first) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ParsedBlock block = first;
    while (true) {
      if (!block.isI()) {
        throw new IOException(
            "T=1: expected I-block in card response, got PCB=0x"
                + Integer.toHexString(block.pcb & 0xFF));
      }
      out.write(block.inf, 0, block.inf.length);
      if (!block.more) {
        return out.toByteArray();
      }
      byte nr = (byte) (1 - block.ns());
      block = ParsedBlock.parse(exchangeBlock(buildRBlock(nr, (byte) 0)));
    }
  }

  private ParsedBlock sendIBlockWithRetry(byte[] inf, boolean more) throws IOException {
    byte[] iBlock = buildIBlock(nsSend, more, inf);
    IOException lastError = null;
    for (int attempt = 0; attempt <= MAX_RETRANSMITS; attempt++) {
      ParsedBlock block;
      try {
        block = ParsedBlock.parse(exchangeBlock(iBlock));
      } catch (IOException e) {
        lastError = e;
        continue;
      }
      if (block.isR() && block.errorCode() != 0) {
        Logger.debug(
            logger,
            "T=1: card requested retransmit (PCB=0x{}), attempt {}/{}",
            Integer.toHexString(block.pcb & 0xFF),
            attempt + 1,
            MAX_RETRANSMITS);
        lastError = null;
        continue; // resend the same I-block -- do NOT toggle N(S) on a retransmit
      }
      nsSend = (byte) (1 - nsSend);
      return block;
    }
    throw new IOException("T=1: card repeatedly requested retransmit", lastError);
  }

  private byte[] exchangeBlock(byte[] block) throws IOException {
    byte[] response = transport.transceiveBlock(block);
    while (response.length >= 4 && response[1] == PCB_WTX_REQUEST) {
      int len = response[2] & 0xFF;
      byte[] inf = Arrays.copyOfRange(response, 3, 3 + len);
      Logger.debug(
          logger,
          "T=1: WTX request (multiplier={}), acknowledging",
          inf.length > 0 ? (inf[0] & 0xFF) : 1);
      response = transport.transceiveBlock(buildSBlock(PCB_WTX_RESPONSE, inf));
    }
    return response;
  }

  private byte[] buildIBlock(byte ns, boolean more, byte[] inf) {
    byte pcb = (byte) (((ns & 0x01) << 6) | (more ? 0x20 : 0x00));
    return buildBlock(pcb, inf);
  }

  private byte[] buildRBlock(byte nr, byte errorCode) {
    byte pcb = (byte) (0x80 | ((nr & 0x01) << 4) | (errorCode & 0x03));
    return buildBlock(pcb, new byte[0]);
  }

  private byte[] buildSBlock(byte pcb, byte[] inf) {
    return buildBlock(pcb, inf);
  }

  private byte[] buildBlock(byte pcb, byte[] inf) {
    byte nad = 0x00;
    byte[] block = new byte[3 + inf.length + 1];
    block[0] = nad;
    block[1] = pcb;
    block[2] = (byte) inf.length;
    System.arraycopy(inf, 0, block, 3, inf.length);
    byte lrc = 0;
    for (int i = 0; i < block.length - 1; i++) {
      lrc ^= block[i];
    }
    block[block.length - 1] = lrc;
    return block;
  }

  private static class ParsedBlock {
    byte pcb;
    byte[] inf;
    boolean more;

    static ParsedBlock parse(byte[] raw) throws IOException {
      if (raw.length < 4) {
        throw new IOException("T=1 block too short: " + raw.length + " bytes");
      }
      byte computedLrc = 0;
      for (int i = 0; i < raw.length - 1; i++) {
        computedLrc ^= raw[i];
      }
      if (computedLrc != raw[raw.length - 1]) {
        throw new IOException("T=1 block LRC mismatch");
      }
      int len = raw[2] & 0xFF;
      if (raw.length < 3 + len + 1) {
        throw new IOException("T=1 block LEN/actual length mismatch");
      }
      ParsedBlock b = new ParsedBlock();
      b.pcb = raw[1];
      b.inf = Arrays.copyOfRange(raw, 3, 3 + len);
      b.more = b.isI() && (b.pcb & 0x20) != 0;
      return b;
    }

    boolean isI() {
      return (pcb & PCB_I_TOP_BIT) == 0;
    }

    boolean isR() {
      return (pcb & PCB_RS_MASK) == PCB_R_TYPE;
    }

    byte ns() {
      return (byte) ((pcb >> 6) & 0x01);
    }

    byte errorCode() {
      return (byte) (pcb & 0x03);
    }
  }
}
