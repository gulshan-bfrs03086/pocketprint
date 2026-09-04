package com.gulshan.pocketprint.print

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gulshan.pocketprint.MainActivity
import com.gulshan.pocketprint.PocketPrintApplication
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.ServiceLocator
import com.gulshan.pocketprint.data.json
import kotlinx.serialization.encodeToString
import com.gulshan.pocketprint.model.JobState
import com.gulshan.pocketprint.model.PrintJobRecord
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrintResult
import com.gulshan.pocketprint.model.SourceDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a print job outside the Activity lifecycle, so leaving the app mid-send
 * does not kill a transfer. Large photos over Bluetooth take a while.
 */
class PrintForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val EXTRA_PRINTER_ID = "printer_id"
        private const val EXTRA_DOCUMENT = "document"
        private const val EXTRA_OPTIONS = "options"
        private const val NOTIFICATION_ID = 4711
        private const val RESULT_NOTIFICATION_ID = 4712

        fun start(
            context: Context,
            printerId: String,
            document: SourceDocument,
            options: PrintOptions,
        ) {
            val intent = Intent(context, PrintForegroundService::class.java).apply {
                putExtra(EXTRA_PRINTER_ID, printerId)
                putExtra(EXTRA_DOCUMENT, json.encodeToString(document))
                putExtra(EXTRA_OPTIONS, json.encodeToString(options))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Jobs run one at a time. A thermal printer holds a single RFCOMM slot, so
     * a second job would fail to connect anyway while the first is still
     * draining, and Android delivers every start on the same service instance.
     */
    private val queue = Mutex()
    private val inFlight = AtomicInteger(0)

    @Volatile
    private var latestStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId

        // Must happen within ~5 s of startForegroundService, before any parsing
        // that might bail out.
        startForeground(NOTIFICATION_ID, buildNotification("Print job", "Preparing", 0))

        val printerId = intent?.getStringExtra(EXTRA_PRINTER_ID)
        val documentRaw = intent?.getStringExtra(EXTRA_DOCUMENT)
        val optionsRaw = intent?.getStringExtra(EXTRA_OPTIONS)

        val document = documentRaw
            ?.let { runCatching { json.decodeFromString<SourceDocument>(it) }.getOrNull() }
        val options = optionsRaw
            ?.let { runCatching { json.decodeFromString<PrintOptions>(it) }.getOrNull() }

        if (printerId == null || document == null || options == null) {
            // A malformed start must not tear the service down under a job that
            // is still running.
            stopIfIdle()
            return START_NOT_STICKY
        }

        inFlight.incrementAndGet()
        scope.launch {
            try {
                queue.withLock { runJob(printerId, document, options) }
            } finally {
                // Runs even on cancellation, so the service always winds down.
                if (inFlight.decrementAndGet() == 0) stopSelf(latestStartId)
            }
        }

        return START_NOT_STICKY
    }

    /** stopSelf(id) is a no-op once a newer start has arrived, which is the point. */
    private fun stopIfIdle() {
        if (inFlight.get() == 0) stopSelf(latestStartId)
    }

    private suspend fun runJob(
        printerId: String,
        document: SourceDocument,
        options: PrintOptions,
    ) {
        val repository = ServiceLocator.printerRepository(applicationContext)
        val jobs = ServiceLocator.jobRepository(applicationContext)
        val printer = repository.find(printerId)

        val jobId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        if (printer == null) {
            jobs.upsert(
                PrintJobRecord(
                    jobId, printerId, "Unknown printer", document.displayName,
                    JobState.FAILED, startedAt, System.currentTimeMillis(),
                    error = "Printer not found",
                ),
            )
            notify(document.displayName, "Printer not found", 0, ongoing = false)
            return
        }

        jobs.upsert(
            PrintJobRecord(
                jobId, printer.id, printer.displayName, document.displayName,
                JobState.SENDING, startedAt,
            ),
        )

        val result = try {
            ServiceLocator.printEngine(applicationContext).print(
                printer = printer,
                source = document,
                options = options,
            ) { sent, total ->
                val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                notifyProgress(document.displayName, "Sending to ${printer.displayName}", percent)
            }
        } catch (cancel: CancellationException) {
            // The history row must not be left stranded at SENDING. Written
            // outside the cancelled scope, or the upsert would itself be
            // cancelled at its first suspension point.
            withContext(NonCancellable) {
                jobs.upsert(
                    PrintJobRecord(
                        jobId, printer.id, printer.displayName, document.displayName,
                        JobState.CANCELLED, startedAt, System.currentTimeMillis(),
                        error = "Cancelled before the job finished",
                    ),
                )
            }
            throw cancel
        }

        val record = when (result) {
            is PrintResult.Success -> PrintJobRecord(
                jobId, printer.id, printer.displayName, document.displayName,
                JobState.COMPLETED, startedAt, System.currentTimeMillis(),
                bytesSent = result.bytesSent,
            )
            is PrintResult.Failure -> PrintJobRecord(
                jobId, printer.id, printer.displayName, document.displayName,
                JobState.FAILED, startedAt, System.currentTimeMillis(),
                error = result.message,
            )
        }
        jobs.upsert(record)

        val summary = when (result) {
            is PrintResult.Success -> "Sent to ${printer.displayName}"
            is PrintResult.Failure -> result.message
        }
        notify(document.displayName, summary, 100, ongoing = false)
    }

    /**
     * A foreground service the system decides has run too long is given a
     * timeout callback, and an app that does not implement it is ANRed. The
     * connectedDevice type this service declares is not one of the capped types
     * today, so this is a backstop rather than a routine path - but the cost of
     * not having it is the whole app dying mid-transfer with the progress
     * notification still on screen, saying nothing.
     *
     * Cancelling the job takes the CancellationException path in runJob, so the
     * history row lands on CANCELLED instead of being stranded at SENDING.
     */
    private fun windDownAfterSystemTimeout() {
        scope.coroutineContext.cancelChildren()
        notify(
            "Print job stopped",
            "Android stopped this job because it ran too long.",
            0,
            ongoing = false,
        )
        stopSelf()
    }

    /** Android 15 calls this one. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) = windDownAfterSystemTimeout()

    /** Android 16 calls this one instead, so both have to be here. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onTimeout(startId: Int, fgsType: Int) = windDownAfterSystemTimeout()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        ongoing: Boolean = true,
    ): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags,
        )

        return NotificationCompat.Builder(this, PocketPrintApplication.CHANNEL_JOBS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(content)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) setProgress(100, progress, progress <= 0) }
            .build()
    }

    private fun notify(title: String, text: String, progress: Int, ongoing: Boolean = true) {
        runCatching {
            // The terminal notification needs its own id: the foreground one is
            // torn down with the service, which would erase the outcome.
            val id = if (ongoing) NOTIFICATION_ID else RESULT_NOTIFICATION_ID
            if (!ongoing) detachForegroundNotification()
            NotificationManagerCompat.from(this)
                .notify(id, buildNotification(title, text, progress, ongoing))
        }
    }

    @Suppress("DEPRECATION")
    private fun detachForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    /** Notifying on every chunk floods NotificationManager and stalls the bar. */
    private var lastProgressAt = 0L
    private var lastProgressPercent = -1

    private fun notifyProgress(title: String, text: String, percent: Int) {
        val now = System.currentTimeMillis()
        if (percent < 100 && now - lastProgressAt < 500 && percent - lastProgressPercent < 5) return
        lastProgressAt = now
        lastProgressPercent = percent
        notify(title, text, percent)
    }
}
