/*
 * Copyright (C) 2023-2026 Yubico.
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

package acab.naiveha.subrosa.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.yubico.yubikit.core.smartcard.ApduException
import acab.naiveha.subrosa.R

fun Throwable.describeChain(maxDepth: Int = 8): String {
    val chain = StringBuilder()
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth < maxDepth) {
        chain.append("\n  [$depth] ${cause::class.simpleName}: ${cause.message}")
        if (cause is ApduException) {
            chain.append(" (SW=0x${"%04X".format(cause.sw)})")
        }
        cause = cause.cause
        depth++
    }
    return chain.toString()
}

fun Fragment.setupCoffeeTipsClipboard(container: View) {
    container.setOnClickListener {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val btcAddress = getString(R.string.btc_address)
        clipboard.setPrimaryClip(ClipData.newPlainText("BTC address", btcAddress))
        Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard_msg, btcAddress), Toast.LENGTH_SHORT).show()
    }
}
