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

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

abstract class YubiKeyViewModel<Session : Closeable> : ViewModel() {
    private val logger = LoggerFactory.getLogger(YubiKeyViewModel::class.java)

    private val resultEvents = EventChannel<Result<String?>>()
    val results: Flow<Result<String?>> = resultEvents.flow

    private val queued = AtomicReference<(Session.() -> String?)?>()
    private val queuedSecrets = AtomicReference<QueuedSecrets?>()
    private val _pendingAction = MutableLiveData<(Session.() -> String?)?>()
    val pendingAction: LiveData<(Session.() -> String?)?> = _pendingAction

    private val pendingHandler = Handler(Looper.getMainLooper())

    fun setPendingAction(action: Session.() -> String?) {
        dropQueuedSecrets()
        publishPendingAction(action)
    }

    fun queueWithSecrets(secrets: QueuedSecrets, action: Session.() -> String?) {
        val previous = queuedSecrets.getAndSet(secrets)
        if (previous !== secrets) previous?.cancel()
        val task: Session.() -> String? = {
            val session = this
            try {
                secrets.runOnce { action(session) }
            } finally {
                queuedSecrets.compareAndSet(secrets, null)
            }
        }
        publishPendingAction(task)
    }

    private fun publishPendingAction(action: Session.() -> String?) {
        queued.set(action)
        _pendingAction.value = action
    }

    private fun dropQueuedSecrets() {
        queuedSecrets.getAndSet(null)?.cancel()
    }

    val hasQueuedAction: Boolean
        get() = queued.get() != null

    protected fun claimPendingAction(expected: Session.() -> String?): Boolean =
        queued.compareAndSet(expected, null)

    protected fun finishPendingAction() {
        pendingHandler.post {
            if (queued.get() == null) _pendingAction.value = null
        }
    }

    abstract fun getSession(device: YubiKeyDevice, onError: (Throwable) -> Unit, callback: (Session) -> Unit)
    abstract fun Session.updateState()

    fun onYubiKeyDevice(device: YubiKeyDevice) {
        logger.debug("onYubiKeyDevice — pendingAction=${if (hasQueuedAction) "present" else "none"}")
        getSession(device, onError = {
            logger.error("onYubiKeyDevice — getSession failed: ${it.message}", it)
            val isDisconnect = device is UsbYubiKeyDevice && !device.hasPermission()
            val dropped = queued.getAndSet(null)
            if (dropped != null) dropQueuedSecrets()
            if (dropped != null || !isDisconnect) {
                postResult(Result.failure(it))
            }
            finishPendingAction()
            onPendingActionDropped()
            if (isDisconnect) {
                onDeviceDisconnected()
            }
        }) { session ->
            queued.getAndSet(null)?.let {
                val actionResult = Result.runCatching { it(session) }
                actionResult.exceptionOrNull()?.let { e ->
                    logger.error("onYubiKeyDevice — pendingAction failed: ${e.message}", e)
                    onPendingActionFailed(e)
                    if (device is UsbYubiKeyDevice && !device.hasPermission()) {
                        onDeviceDisconnected()
                    }
                }
                postResult(actionResult)
                finishPendingAction()
            }

            try {
                session.updateState()
            } catch (e: Throwable) {
                logger.error("onYubiKeyDevice — session.updateState() failed: ${e.message}", e)
                if (device is UsbYubiKeyDevice && !device.hasPermission()) {
                    onDeviceDisconnected()
                }
            }
        }
    }

    fun postResult(result: Result<String?>) {
        resultEvents.post(result)
    }

    fun clearResult() {
        resultEvents.discardPending()
    }

    fun cancelPendingAction() {
        queued.set(null)
        _pendingAction.value = null
        dropQueuedSecrets()
        onPendingActionDropped()
    }

    override fun onCleared() {
        dropQueuedSecrets()
    }

    protected open fun onPendingActionDropped() {}

    protected open fun onPendingActionFailed(error: Throwable) {}

    open fun onDeviceDisconnected() {}

    protected fun transportLabel(device: YubiKeyDevice): String =
        if (device is NfcYubiKeyDevice) "NFC" else "USB"

    protected fun shouldIgnoreTap(device: YubiKeyDevice): Boolean =
        device is NfcYubiKeyDevice && !hasQueuedAction
}