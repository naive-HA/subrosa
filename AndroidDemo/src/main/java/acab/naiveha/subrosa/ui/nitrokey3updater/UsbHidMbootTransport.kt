package acab.naiveha.subrosa.ui.nitrokey3updater

import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootTimeoutException
import acab.naiveha.subrosa.ui.nitrokey3updater.mboot.MbootTransport
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException

internal class UsbHidMbootTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) : MbootTransport, Closeable {

    override val maxFrameSize: Int get() = MAX_FRAME_SIZE

    private val readLimit: Int = endpointIn.maxPacketSize.takeIf { it > 0 } ?: DEFAULT_PACKET_SIZE

    override fun write(frame: ByteArray, length: Int, timeoutMs: Int) {
        val sent = connection.bulkTransfer(endpointOut, frame, 0, length, timeoutMs)
        when {
            sent == length -> return
            sent < 0 -> throw MbootTimeoutException("USB write of $length bytes failed or timed out")
            else -> throw IOException("Incomplete USB write ($sent of $length bytes)")
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val received = connection.bulkTransfer(endpointIn, buffer, 0, minOf(buffer.size, readLimit), timeoutMs)
        if (received < 0) throw MbootTimeoutException("no report received within $timeoutMs ms")
        return received
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }

    fun linkInfo(): UsbLinkInfo {
        var descriptor: ByteArray? = null
        var problem: String? = null
        try {
            val buffer = ByteArray(MAX_REPORT_DESCRIPTOR_SIZE)
            val length = connection.controlTransfer(
                REQUEST_TYPE_INTERFACE_TO_HOST,
                REQUEST_GET_DESCRIPTOR,
                VALUE_HID_REPORT_DESCRIPTOR,
                usbInterface.id,
                buffer,
                buffer.size,
                CONTROL_TIMEOUT_MS,
            )
            if (length > 0) descriptor = buffer.copyOf(length) else problem = "the control transfer returned $length"
        } catch (e: RuntimeException) {
            problem = e.message ?: e.javaClass.simpleName
        }
        return UsbLinkInfo(
            interfaceNumber = usbInterface.id,
            endpointIn = describe(endpointIn),
            endpointOut = describe(endpointOut),
            reportDescriptor = descriptor,
            reportDescriptorProblem = problem,
        )
    }

    private fun describe(endpoint: UsbEndpoint) = EndpointInfo(endpoint.address, endpoint.maxPacketSize, endpoint.interval)

    private class HidInterface(val usbInterface: UsbInterface, val endpointIn: UsbEndpoint, val endpointOut: UsbEndpoint)

    companion object {
        const val MAX_FRAME_SIZE = 4096

        private const val DEFAULT_PACKET_SIZE = 64

        private const val REQUEST_TYPE_INTERFACE_TO_HOST = 0x81
        private const val REQUEST_GET_DESCRIPTOR = 0x06
        private const val VALUE_HID_REPORT_DESCRIPTOR = 0x2200
        private const val MAX_REPORT_DESCRIPTOR_SIZE = 4096
        private const val CONTROL_TIMEOUT_MS = 1_000

        @Throws(IOException::class)
        fun open(usbManager: UsbManager, device: UsbDevice): UsbHidMbootTransport {
            val hid = findHidInterface(device)
                ?: throw IOException("The device has no HID interface with interrupt endpoints")
            val connection = try {
                usbManager.openDevice(device)
            } catch (e: SecurityException) {
                throw IOException("No permission to access the USB device", e)
            } ?: throw IOException("The USB device could not be opened")
            try {
                if (!connection.claimInterface(hid.usbInterface, true)) {
                    throw IOException("The bootloader's USB interface could not be claimed")
                }
                return UsbHidMbootTransport(connection, hid.usbInterface, hid.endpointIn, hid.endpointOut)
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }

        private fun findHidInterface(device: UsbDevice): HidInterface? {
            var fallback: HidInterface? = null
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                var endpointIn: UsbEndpoint? = null
                var endpointOut: UsbEndpoint? = null
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) endpointIn = endpoint else endpointOut = endpoint
                }
                if (endpointIn == null || endpointOut == null) continue
                val candidate = HidInterface(usbInterface, endpointIn, endpointOut)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID) return candidate
                if (fallback == null) fallback = candidate
            }
            return fallback
        }
    }
}
