/*
 * Copyright (C) 2020-2022 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yubico.yubikit.android.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.io.ByteArrayOutputStream;

/**
 * A helper class that is used to intercept keyboard event from a YubiKey to capture an OTP. Use it
 * directly in an Activity in {@link android.app.Activity#onKeyUp}, or in a {@link
 * android.view.View.OnKeyListener}.
 */
public class OtpKeyListener {
  private static final int OTP_DELAY_MS = 1000;
  private static final int YUBICO_VID = 0x1050;

  private static final SparseIntArray HID_USAGE_IDS = new SparseIntArray();

  static {
    int[] letters = {
      KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D,
      KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
      KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
      KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
      KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T,
      KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_X,
      KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_Z,
    };
    for (int i = 0; i < letters.length; i++) {
      HID_USAGE_IDS.put(letters[i], 0x04 + i); // a-z -> 0x04-0x1D
    }
    int[] digits1to9 = {
      KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
      KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
      KeyEvent.KEYCODE_9,
    };
    for (int i = 0; i < digits1to9.length; i++) {
      HID_USAGE_IDS.put(digits1to9[i], 0x1E + i); // 1-9 -> 0x1E-0x26
    }
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_0, 0x27);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_ENTER, 0x28);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x28);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_TAB, 0x2B);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_SPACE, 0x2C);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_MINUS, 0x2D);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_EQUALS, 0x2E);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_BACKSLASH, 0x32);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_SEMICOLON, 0x33);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_APOSTROPHE, 0x34);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_GRAVE, 0x35);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_COMMA, 0x36);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_PERIOD, 0x37);
    HID_USAGE_IDS.put(KeyEvent.KEYCODE_SLASH, 0x38);
  }

  private final SparseArray<StringBuilder> inputBuffers = new SparseArray<>();
  private final SparseArray<ByteArrayOutputStream> scancodeBuffers = new SparseArray<>();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final OtpListener listener;

  public OtpKeyListener(OtpListener listener) {
    this.listener = listener;
  }

  public boolean onKeyEvent(KeyEvent event) {
    InputDevice device = event.getDevice();
    if (device == null || device.getVendorId() != YUBICO_VID) {
      // Don't handle non-Yubico devices
      return false;
    }

    if (event.getAction() == KeyEvent.ACTION_UP) {
      // use id of keyboard device to distinguish current input device
      // in case of multiple keys inserted
      int deviceId = event.getDeviceId();
      StringBuilder otpBuffer = inputBuffers.get(deviceId, new StringBuilder());
      ByteArrayOutputStream scancodeBuffer = scancodeBuffers.get(deviceId);
      if (scancodeBuffer == null) {
        scancodeBuffer = new ByteArrayOutputStream();
      }
      if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
          || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER) {
        // Carriage return seen. Assume this is the end of the OTP credential and notify
        // immediately.
        listener.onCaptureComplete(otpBuffer.toString(), scancodeBuffer.toByteArray());
        inputBuffers.delete(deviceId);
        scancodeBuffers.delete(deviceId);
      } else {
        if (otpBuffer.length() == 0) {
          // in case if we never get keycode enter (which is pretty generic scenario) we set timer
          // for 1 sec
          // upon expiration we assume that we have no more input from key
          handler.postDelayed(
              () -> {
                StringBuilder otpBuffer1 = inputBuffers.get(deviceId, new StringBuilder());
                // if buffer is empty it means that we sent it to user already, avoid double
                // invocation
                if (otpBuffer1.length() > 0) {
                  ByteArrayOutputStream scancodeBuffer1 =
                      scancodeBuffers.get(deviceId, new ByteArrayOutputStream());
                  listener.onCaptureComplete(otpBuffer1.toString(), scancodeBuffer1.toByteArray());
                  inputBuffers.delete(deviceId);
                  scancodeBuffers.delete(deviceId);
                }
              },
              OTP_DELAY_MS);
          listener.onCaptureStarted();
        }
        otpBuffer.append((char) event.getUnicodeChar());
        inputBuffers.put(deviceId, otpBuffer);

        int hidUsageId = HID_USAGE_IDS.get(event.getKeyCode(), -1);
        if (hidUsageId >= 0) {
          scancodeBuffer.write(hidUsageId | (event.isShiftPressed() ? 0x80 : 0));
        }
        scancodeBuffers.put(deviceId, scancodeBuffer);
      }
    }

    return true;
  }

  /** Listener interface to react to events. */
  public interface OtpListener {
    /** Called when the user has triggered OTP output and capture has started. */
    void onCaptureStarted();

    /**
     * Called when OTP capture has completed.
     *
     * @param capture the captured OTP
     */
    void onCaptureComplete(String capture, byte[] scancodes);
  }
}
