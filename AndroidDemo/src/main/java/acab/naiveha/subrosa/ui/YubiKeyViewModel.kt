/*
 * Copyright (C) 2022 Yubico.
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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import org.slf4j.LoggerFactory
import java.io.Closeable

abstract class YubiKeyViewModel<Session : Closeable> : ViewModel() {
    private val logger = LoggerFactory.getLogger(YubiKeyViewModel::class.java)

    private val _result = MutableLiveData<Result<String?>>(Result.success(null))
    val result: LiveData<Result<String?>> = _result

    val pendingAction = MutableLiveData<(Session.() -> String?)?>()

    abstract fun getSession(device: YubiKeyDevice, onError: (Throwable) -> Unit, callback: (Session) -> Unit)
    abstract fun Session.updateState()

    fun onYubiKeyDevice(device: YubiKeyDevice) {
        logger.debug("onYubiKeyDevice — pendingAction=${if (pendingAction.value != null) "present" else "none"}")
        getSession(device, onError = {
            logger.error("onYubiKeyDevice — getSession failed: ${it.message}", it)
            _result.postValue(Result.failure(it))
            pendingAction.postValue(null)
        }) { session ->
            pendingAction.value?.let {
                val actionResult = Result.runCatching { it(session) }
                actionResult.exceptionOrNull()?.let { e ->
                    logger.error("onYubiKeyDevice — pendingAction failed: ${e.message}", e)
                }
                _result.postValue(actionResult)
                pendingAction.postValue(null)
            }

            try {
                session.updateState()
            } catch (e: Throwable) {
                logger.error("onYubiKeyDevice — session.updateState() failed: ${e.message}", e)
            }
        }
    }

    fun postResult(result: Result<String?>) {
        _result.postValue(result)
    }

    fun clearResult() {
        _result.value.let {
            if (it != null && (it.isFailure || it.getOrNull() != null)) {
                _result.postValue(Result.success(null))
            }
        }
    }

    open fun onDeviceDisconnected() {}

    protected fun transportLabel(device: YubiKeyDevice): String =
        if (device is NfcYubiKeyDevice) "NFC" else "USB"

    protected fun shouldIgnoreTap(device: YubiKeyDevice): Boolean =
        device is NfcYubiKeyDevice && pendingAction.value == null
}