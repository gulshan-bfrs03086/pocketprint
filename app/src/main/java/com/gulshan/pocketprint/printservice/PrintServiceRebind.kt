package com.gulshan.pocketprint.printservice

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * The Android side of [Rebind]: when the app is opened after it may have been
 * stopped, makes the print framework drop and re-create its binding to
 * PocketPrintService, which is the only thing that clears the wedge described
 * there short of the user switching the service off and on in Settings.
 */
object PrintServiceRebind {

    private const val TAG = "PrintServiceRebind"

    /**
     * How long after the activity starts to decide. When the framework had a
     * bind waiting on this package, opening the app starts the process and the
     * waiting bind then completes - healthy, and nothing to repair - but the
     * service is created after the activity, not before it. Deciding at once
     * would see no service and tear down a binding that had just worked.
     */
    private const val SETTLE_MS = 5_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var rebind: Rebind? = null

    private fun rebind(context: Context): Rebind =
        rebind ?: synchronized(this) {
            rebind ?: Rebind(ServiceComponent(context.applicationContext)) { delayMs, action ->
                main.postDelayed(action, delayMs)
            }.also { rebind = it }
        }

    /** From Application.onCreate. See [Rebind.repairIfLeftDisabled]. */
    fun onProcessStart(context: Context) {
        val repaired = runCatching { rebind(context).repairIfLeftDisabled() }
            .onFailure { Log.w(TAG, "could not read the print service's state", it) }
            .getOrDefault(false)
        if (repaired) Log.w(TAG, "print service was left disabled by an interrupted rebind; enabled it")
    }

    /** From MainActivity, on a fresh launch by the user. */
    fun onUserLaunch(context: Context) {
        val app = context.applicationContext
        main.postDelayed({ decide(app) }, SETTLE_MS)
    }

    private fun decide(context: Context) {
        val canaryAlive = StopCanary.isScheduled(context)
        if (!shouldRebind(mayHaveBeenStopped = !canaryAlive, serviceRunning = PocketPrintService.running)) {
            if (!canaryAlive) StopCanary.schedule(context)
            return
        }
        val started = runCatching {
            rebind(context).start {
                Log.i(TAG, "print service enabled again; the framework will bind it afresh")
                StopCanary.schedule(context)
            }
        }.onFailure { Log.w(TAG, "could not rebind the print service", it) }.getOrDefault(false)
        if (started) {
            Log.i(TAG, "app may have been stopped since it was last opened; rebinding the print service")
        }
    }

    private class ServiceComponent(private val context: Context) : Rebind.ComponentSwitch {
        private val name = ComponentName(context, PocketPrintService::class.java)

        override fun isDisabled(): Boolean =
            context.packageManager.getComponentEnabledSetting(name) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        // DONT_KILL_APP: without it the package manager kills this process,
        // and the restore below would never run.
        override fun disable() = set(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)

        override fun restore() = set(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        private fun set(state: Int) =
            context.packageManager.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
    }
}

/**
 * Says whether the app has been stopped since it was last opened, which no
 * API says on Android 9.
 *
 * A job scheduled a year out that is never meant to run. Force-stopping a
 * package cancels its jobs, and a fresh install has none, so a missing job
 * means the app may have been stopped: exactly the precondition for the wedge
 * in [Rebind]. It is not persisted, because that would need the boot
 * permission, so a reboot also loses it - and the reboot has already cleared
 * any wedge, so the cost is one unneeded rebind on the next launch.
 */
class StopCanary : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean = false

    override fun onStopJob(params: JobParameters?): Boolean = false

    internal companion object {
        private const val JOB_ID = 0x50505243 // "PPRC"

        fun isScheduled(context: Context): Boolean =
            runCatching { scheduler(context)?.getPendingJob(JOB_ID) != null }.getOrDefault(true)

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, StopCanary::class.java))
                .setMinimumLatency(TimeUnit.DAYS.toMillis(365))
                .build()
            runCatching { scheduler(context)?.schedule(job) }
        }

        private fun scheduler(context: Context): JobScheduler? =
            context.getSystemService(JobScheduler::class.java)
    }
}
