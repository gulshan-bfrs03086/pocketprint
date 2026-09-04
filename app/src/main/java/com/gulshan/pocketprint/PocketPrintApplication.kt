package com.gulshan.pocketprint

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.gulshan.pocketprint.net.LocalNetwork
import com.gulshan.pocketprint.render.Spool

class PocketPrintApplication : Application() {

    companion object {
        const val CHANNEL_JOBS = "print_jobs"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Registered here rather than at print time because a network callback
        // takes a moment to deliver its first answer, and the first thing this
        // app does after launch is often print.
        LocalNetwork.start(this)
        // Spool files are cache-only; anything left behind is from a crash.
        Spool.clear(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_JOBS,
                getString(R.string.channel_jobs),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_jobs_description) },
        )
    }
}
