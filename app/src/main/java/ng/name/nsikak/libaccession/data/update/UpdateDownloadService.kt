package ng.name.nsikak.libaccession.data.update

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ng.name.nsikak.libaccession.MainActivity
import ng.name.nsikak.libaccession.TallyApp
import ng.name.nsikak.libaccession.data.settings.ServerConfig
import ng.name.nsikak.libaccession.data.settings.parseServerAddress
import java.util.Locale

class UpdateDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START_DOWNLOAD) {
            val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
            val updateInfoJson = intent.getStringExtra(EXTRA_UPDATE_INFO)
            val patchInfoJson = intent.getStringExtra(EXTRA_PATCH_INFO)

            if (serverUrl != null && updateInfoJson != null) {
                val app = application as? TallyApp
                val repository = app?.container?.updateRepository

                if (repository != null) {
                    val serverConfig = parseServerAddress(serverUrl) ?: ServerConfig(serverUrl)
                    val updateInfo = repository.json.decodeFromString<UpdateInfoResponse>(updateInfoJson)
                    val patchInfo = patchInfoJson?.let { repository.json.decodeFromString<PatchInfo>(it) }

                    startForegroundServiceWithNotification()

                    downloadJob?.cancel()
                    downloadJob = serviceScope.launch {
                        val observeJob = launch {
                            repository.updateStatus.collectLatest { status ->
                                updateNotification(status)
                            }
                        }

                        repository.downloadAndPrepareUpdate(serverConfig, updateInfo, patchInfo)
                        observeJob.cancel()
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }
            }
        } else if (action == ACTION_STOP_DOWNLOAD) {
            downloadJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress when downloading Tally app updates in the background."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification("Downloading Tally Update", "Starting...", 0, 0, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: UpdateStatus) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        when (status) {
            is UpdateStatus.Downloading -> {
                val downloadedMb = String.format(Locale.US, "%.1f", status.bytesDownloaded / (1024f * 1024f))
                val totalMb = String.format(Locale.US, "%.1f", status.totalBytes / (1024f * 1024f))
                val content = if (status.totalBytes > 0) {
                    "$downloadedMb MB / $totalMb MB (${status.progressPercent}%)"
                } else {
                    "${status.progressPercent}%"
                }
                val notification = buildNotification(
                    title = "Downloading Tally Update",
                    contentText = content,
                    progress = status.progressPercent,
                    maxProgress = 100,
                    indeterminate = false
                )
                manager.notify(NOTIFICATION_ID, notification)
            }
            is UpdateStatus.Patching -> {
                val notification = buildNotification(
                    title = "Applying Update",
                    contentText = status.statusText,
                    progress = 0,
                    maxProgress = 0,
                    indeterminate = true
                )
                manager.notify(NOTIFICATION_ID, notification)
            }
            is UpdateStatus.ReadyToInstall -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.stat_sys_download_done)
                    .setContentTitle("Update Ready to Install")
                    .setContentText("Tap to complete installation.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                manager.notify(NOTIFICATION_ID, notification)
            }
            is UpdateStatus.Error -> {
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.stat_notify_error)
                    .setContentTitle("Update Failed")
                    .setContentText(status.message)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .build()

                manager.notify(NOTIFICATION_ID, notification)
            }
            else -> {}
        }
    }

    private fun buildNotification(
        title: String,
        contentText: String,
        progress: Int,
        maxProgress: Int,
        indeterminate: Boolean
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(contentText)
        .setProgress(maxProgress, progress, indeterminate)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        private const val CHANNEL_ID = "tally_update_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START_DOWNLOAD = "ng.name.nsikak.libaccession.START_UPDATE_DOWNLOAD"
        private const val ACTION_STOP_DOWNLOAD = "ng.name.nsikak.libaccession.STOP_UPDATE_DOWNLOAD"

        private const val EXTRA_SERVER_URL = "extra_server_url"
        private const val EXTRA_UPDATE_INFO = "extra_update_info"
        private const val EXTRA_PATCH_INFO = "extra_patch_info"

        fun startDownload(
            context: Context,
            serverConfig: ServerConfig,
            updateInfo: UpdateInfoResponse,
            patchInfo: PatchInfo?,
            json: Json
        ) {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_SERVER_URL, serverConfig.baseUrl)
                putExtra(EXTRA_UPDATE_INFO, json.encodeToString(updateInfo))
                if (patchInfo != null) {
                    putExtra(EXTRA_PATCH_INFO, json.encodeToString(patchInfo))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
