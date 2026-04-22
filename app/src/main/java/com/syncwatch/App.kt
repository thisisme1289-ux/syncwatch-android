package com.syncwatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.syncwatch.network.ApiClient
import com.syncwatch.network.SocketManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialise singletons eagerly so the first fragment never waits
        ApiClient.init(this)
        SocketManager.init(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)

            // Channel for the screen-share foreground service notification
            val screenShareChannel = NotificationChannel(
                CHANNEL_SCREEN_SHARE,
                "Screen Share",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while screen sharing is active"
                setShowBadge(false)
            }

            mgr.createNotificationChannel(screenShareChannel)
        }
    }

    companion object {
        lateinit var instance: App
            private set

        const val CHANNEL_SCREEN_SHARE = "screen_share"
    }
}
