package com.syncwatch.screenshare

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.syncwatch.App
import com.syncwatch.MainActivity
import com.syncwatch.R

/**
 * Foreground service required by Android 10+ before requesting MediaProjection.
 *
 * On Android 14+ the service must declare `foregroundServiceType="mediaProjection"`
 * in the manifest (already done) and be started before the capture intent is launched.
 *
 * Start it with [ScreenShareService.start] BEFORE calling
 * `MediaProjectionManager.createScreenCaptureIntent()`.
 */
class ScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundWithNotification()
            ACTION_STOP  -> stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val stopIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ACTION_STOP
        }.let {
            PendingIntent.getService(
                this, 1, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, App.CHANNEL_SCREEN_SHARE)
            .setContentTitle("SyncWatch")
            .setContentText("Screen sharing is active")
            .setSmallIcon(R.drawable.ic_screen_share)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_START      = "com.syncwatch.SCREEN_SHARE_START"
        private const val ACTION_STOP       = "com.syncwatch.SCREEN_SHARE_STOP"
        private const val NOTIFICATION_ID   = 1001

        fun start(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
