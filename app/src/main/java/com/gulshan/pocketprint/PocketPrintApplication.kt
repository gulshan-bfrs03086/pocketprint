package com.gulshan.pocketprint

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.gulshan.pocketprint.net.LocalNetwork
import com.gulshan.pocketprint.render.Spool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PocketPrintApplication : Application() {

    companion object {
        const val CHANNEL_JOBS = "print_jobs"
    }

    /** Lives as long as the process, which is the point of everything on it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Taken before anything else runs, so nothing this process goes on to
        // print can carry a creation time earlier than it. That is what makes
        // it safe to treat "older than this" as "belongs to a dead process".
        val processStartedAt = System.currentTimeMillis()

        createNotificationChannel()
        // Registered here rather than at print time because a network callback
        // takes a moment to deliver its first answer, and the first thing this
        // app does after launch is often print.
        LocalNetwork.start(this)
        // Spool files are cache-only; anything left behind is from a crash.
        Spool.clear(this)

        // The history's equivalent of that stale spool file. A job dies with
        // the process running it - every writer is in this one, the manifest
        // declares no android:process - so a row still marked SENDING here
        // belongs to a job that ended when the app did. Left alone it sits in
        // the list looking live for good, offering a Cancel button that has
        // nothing to cancel.
        scope.launch {
            ServiceLocator.jobRepository(this@PocketPrintApplication)
                .interruptStale(startedBefore = processStartedAt)
        }
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
