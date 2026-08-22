package acab.naiveha.subrosa.by.naiveha.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import acab.naiveha.subrosa.by.naiveha.MainViewModel
import acab.naiveha.subrosa.by.naiveha.R
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

fun <S : Closeable> Fragment.bindDeviceActions(
    viewModel: YubiKeyViewModel<S>,
    activityViewModel: MainViewModel,
    prompt: YubiKeyPromptDialog,
    alwaysReadOnConnect: Boolean = false,
    isTapSuspended: () -> Boolean = { false },
    shouldClearOnDisconnect: () -> Boolean = { true },
    onDisconnected: () -> Unit = { viewModel.onDeviceDisconnected() },
) {
    var lastDevice: YubiKeyDevice? = null

    fun dispatch(device: YubiKeyDevice) {
        if (prompt.isShowing) {
            prompt.dismiss()
        }

        lifecycleScope.launch {
            withContext(activityViewModel.singleDispatcher) {
                viewModel.onYubiKeyDevice(device)

                if (device is NfcYubiKeyDevice) {
                    device.remove {
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (shouldClearOnDisconnect()) {
                                onDisconnected()
                            }
                        }
                    }
                }
                Unit
            }
        }
    }

    activityViewModel.yubiKey.observe(viewLifecycleOwner) { device ->
        if (device != null) {
            if (alwaysReadOnConnect) {
                if (isTapSuspended()) return@observe
                lastDevice = device
                dispatch(device)
            } else if (viewModel.pendingAction.value != null) {
                dispatch(device)
            }
        } else {
            if (alwaysReadOnConnect && lastDevice != null && lastDevice !is NfcYubiKeyDevice) {
                onDisconnected()
            }
            lastDevice = null
        }
    }

    viewModel.pendingAction.observe(viewLifecycleOwner) { action ->
        if (action != null) {
            val device = activityViewModel.yubiKey.value
            if (device != null) {
                dispatch(device)
            } else {
                prompt.setHelpText(getString(R.string.yubikit_prompt_plug_in_or_tap))
                prompt.show()
            }
        }
    }
}
