package acab.naiveha.subrosa.ui.nitrokey3updater

import acab.naiveha.subrosa.MainActivity
import acab.naiveha.subrosa.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirmwareFlashService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    private var job: Job? = null

    private var notifiedPhase: FlashPhase? = null
    private var notifiedPercent = -1
    private var notifiedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val promoted = enterForeground(notificationFor(FlashProgress(FlashPhase.CONNECTING)))
        if (job?.isActive == true) {
            Log.w(TAG, "An update is already running; ignoring the extra start command")
            return START_NOT_STICKY
        }
        val request = FlashSession.takeRequest()
        when {
            !promoted -> {
                if (request != null) FlashSession.finish(refusedOutcome(request))
                stopSelf()
            }
            request == null -> {
                Log.w(TAG, "Started without a pending update; stopping")
                leaveForeground()
            }
            else -> {
                notifiedPhase = null
                notifiedPercent = -1
                notifiedAtMs = 0L
                acquireWakeLock()
                job = scope.launch { runUpdate(request) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runUpdate(request: FlashRequest) {
        var outcome: FlashState? = null
        try {
            outcome = withContext(NonCancellable) { performUpdate(request) }
        } finally {
            FlashSession.finish(outcome ?: stoppedUnexpectedly(request))
            releaseWakeLock()
            mainHandler.post { leaveForeground() }
        }
    }

    private suspend fun performUpdate(request: FlashRequest): FlashState {
        val image = request.image
        return try {
            install(applicationContext, request.device, image)
            FlashState.Succeeded(image.displayVersion ?: image.fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FlashException) {
            Log.w(UPDATER_TAG, "Firmware update failed: ${e.message}", e)
            FlashState.Failed.from(e)
        } catch (e: Exception) {
            Log.e(UPDATER_TAG, "Unexpected error during the firmware update", e)
            FlashState.Failed("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun install(context: Context, device: BootloaderDevice, image: FirmwareImage) {
        when (image.variant) {
            Nk3Variant.LPC55 -> withBootloaderTransport(context, device) { transport ->
                Lpc55Flasher(
                    transport,
                    log = { line -> Log.d(UPDATER_TAG, "mboot: $line") },
                    declaredDataReportBytes = transport.linkInfo().declaredDataReportBytes(),
                ).flash(image, ::onProgress)
            }
            Nk3Variant.NRF52 -> withContext(Dispatchers.IO) {
                Nrf52Flasher().flash(image, ::onProgress)
            }
        }
    }

    private fun onProgress(progress: FlashProgress) {
        FlashSession.publishProgress(progress)
        updateNotification(progress)
    }

    private fun stoppedUnexpectedly(request: FlashRequest): FlashState.Failed {
        val last = FlashSession.lastProgress()
        val stage = when (last?.phase) {
            FlashPhase.RESTARTING -> FlashStage.RESET
            FlashPhase.SENDING -> FlashStage.TRANSFER
            else -> FlashStage.CONNECT
        }
        return FlashState.Failed(
            message = getString(R.string.nitrokey3updater_service_stopped),
            stage = stage,
            bytesSent = if (last != null && last.phase == FlashPhase.SENDING) last.done else 0L,
            total = request.image.data.size.toLong(),
            status = null,
            replugRequired = true,
        )
    }

    private fun refusedOutcome(request: FlashRequest): FlashState.Failed = FlashState.Failed(
        message = getString(R.string.nitrokey3updater_service_failed),
        stage = FlashStage.CONNECT,
        bytesSent = 0L,
        total = request.image.data.size.toLong(),
        status = null,
        replugRequired = false,
    )

    private fun enterForeground(notification: Notification): Boolean = try {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: RuntimeException) {
        Log.e(TAG, "The system refused to run the update service in the foreground", e)
        false
    }

    private fun leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        try {
            val lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
        } catch (e: RuntimeException) {
            Log.w(TAG, "The wake lock could not be acquired; the update continues without it", e)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "The wake lock could not be released", e)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nitrokey3updater_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.nitrokey3updater_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(progress: FlashProgress) {
        val now = SystemClock.elapsedRealtime()
        val phaseChanged = progress.phase != notifiedPhase
        val percentChanged = progress.percent != notifiedPercent
        if (!phaseChanged && (!percentChanged || now - notifiedAtMs < NOTIFICATION_MIN_INTERVAL_MS)) return
        notifiedPhase = progress.phase
        notifiedPercent = progress.percent
        notifiedAtMs = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notificationFor(progress))
        } catch (e: RuntimeException) {
            Log.w(TAG, "The notification could not be updated", e)
        }
    }

    private fun notificationFor(progress: FlashProgress): Notification {
        val text = when (progress.phase) {
            FlashPhase.CONNECTING -> getString(R.string.nitrokey3updater_status_connecting)
            FlashPhase.SENDING -> getString(R.string.nitrokey3updater_status_installing, progress.percent.coerceAtLeast(0))
            FlashPhase.RESTARTING -> getString(R.string.nitrokey3updater_status_restarting)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_firmware_update)
            .setContentTitle(getString(R.string.nitrokey3updater_notification_title))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress.phase == FlashPhase.SENDING) {
            builder.setProgress(100, progress.percent.coerceAtLeast(0), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val TAG = "FirmwareFlashService"

        private const val UPDATER_TAG = "Nitrokey3UpdaterVM"

        private const val CHANNEL_ID = "firmware_update"
        private const val NOTIFICATION_ID = 3001
        private const val NOTIFICATION_MIN_INTERVAL_MS = 400L
        private const val WAKE_LOCK_TAG = "subrosa:firmware-update"
        private const val WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1000L

        @Throws(FlashException::class)
        internal fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FirmwareFlashService::class.java))
            } catch (e: RuntimeException) {
                throw FlashException(context.getString(R.string.nitrokey3updater_service_failed), e)
            }
        }
    }
}
