package com.gulshan.pocketprint.printservice

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintJobInfo
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import androidx.annotation.MainThread
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.print.Diagnostics
import com.gulshan.pocketprint.print.JobError
import com.gulshan.pocketprint.print.PrinterAvailability

/**
 * The only file in this package that may touch the print framework's
 * main-thread-only API. scripts/check-printservice-threading.sh enforces that,
 * and enforces that nothing in here can dispatch onto another thread.
 *
 * Every accessor and transition on PrintJob and PrintDocument, generatePrinterId
 * and getActivePrintJobs on PrintService, and addPrinters, removePrinters and
 * their siblings on PrinterDiscoverySession begin with
 * PrintService.throwIfNotCalledOnMainThread(). Only PrintJob's methods carry an
 * @MainThread annotation; the rest simply throw. That is why Android Lint's
 * WrongThread check cannot see them, and why a structural rule is needed
 * instead of an annotation.
 *
 * What they throw is an IllegalAccessError: an Error, not an Exception. A
 * catch (e: Exception) lets it through; runCatching does not. This package used
 * runCatching around those calls, so the error was logged at warn level and
 * the system print dialog simply searched forever. That presented three times,
 * in three places, before this file existed.
 *
 * The rule: a framework handle never leaves the callback it arrived in. A
 * PrintJob becomes a QueuedJob - plain values plus a JobHandle whose every
 * method posts to the main looper - before any coroutine can see it. The
 * discovery session never calls addPrinters or generatePrinterId itself; it
 * asks Publisher, which does both on the looper. Since nothing here may start
 * a coroutine or a thread, there is no path to a framework call that is not
 * either a Handler post to the main looper or an @MainThread entry point
 * invoked from a framework callback - and those check the looper first, so a
 * future misuse fails with a sentence rather than a swallowed Error.
 */
internal object PrintFramework {

    private const val TAG = "PrintFramework"
    private val main = Handler(Looper.getMainLooper())

    /**
     * Snapshots a queued job on the callback's own thread.
     *
     * Returns null when the job could not be taken. In that case it has already
     * been failed with a message the print dialog can show, and the caller has
     * nothing left to do with it.
     */
    @MainThread
    fun take(service: PrintService, printJob: PrintJob): QueuedJob? {
        requireMain("take")
        val info = printJob.info
        val localId = info.printerId?.localId
        if (localId == null) {
            printJob.fail(service.getString(R.string.system_no_printer_selected))
            return null
        }

        // Before start(), so the one read of the document sits with the rest of
        // the handle access rather than after a state change.
        val descriptor = runCatching { printJob.document?.data }.getOrNull()
        if (descriptor == null) {
            printJob.fail(service.getString(R.string.system_document_unreadable))
            return null
        }

        printJob.start()
        val key = printJob.id.toString()
        return QueuedJob(
            key = key,
            localId = localId,
            label = info.label?.toString() ?: "Print job",
            info = info,
            descriptor = descriptor,
            handle = JobHandle(service, printJob, key),
        )
    }

    /** The key a job was filed under, for finding the work that belongs to it. */
    @MainThread
    fun keyOf(printJob: PrintJob): String {
        requireMain("keyOf")
        return printJob.id.toString()
    }

    /** Cancels the framework's side of a job, if it is still in a state that can be. */
    @MainThread
    fun cancel(printJob: PrintJob) {
        requireMain("cancel")
        if (printJob.isStarted || printJob.isQueued) printJob.cancel()
    }

    private fun requireMain(what: String) {
        check(Looper.getMainLooper().isCurrentThread) {
            "PrintFramework.$what called off the main thread. The framework would " +
                "throw IllegalAccessError one line later and something would swallow " +
                "it; this fails first, where it can be read."
        }
    }

    /**
     * Everything a print job needs once it has left the main thread.
     *
     * PrintJobInfo is a plain parcelable from android.print - reading its
     * attributes and copies off the main thread is fine. It is the PrintJob
     * that must not travel, and it does not: it lives inside the handle.
     */
    internal class QueuedJob(
        val key: String,
        val localId: String,
        val label: String,
        val info: PrintJobInfo,
        val descriptor: ParcelFileDescriptor,
        val handle: JobHandle,
    )

    /**
     * The four state transitions the engine is allowed to make, each posted to
     * the main looper. There is deliberately no accessor: nothing off the main
     * thread ever needs to ask the framework a question about a job.
     */
    internal class JobHandle internal constructor(
        private val service: PrintService,
        private val printJob: PrintJob,
        private val key: String,
    ) {
        fun complete() = onMain("complete") {
            if (!printJob.isCompleted) printJob.complete()
        }

        fun block(reason: String) = onMain("block") {
            if (printJob.isStarted) printJob.block(reason)
        }

        /** The framework's name for leaving the blocked state is start(). */
        fun resume() = onMain("resume") {
            if (printJob.isBlocked) printJob.start()
        }

        /**
         * The dialog this lands in belongs to another app, which has no idea
         * what a closed RFCOMM socket is. A recognised failure goes out as the
         * sentence a person can act on; anything else goes out unchanged rather
         * than replaced by something vaguer.
         */
        fun fail(message: String) = onMain("fail") {
            val readable = JobError.explain(message)?.let { service.getString(it) } ?: message
            if (!printJob.isFailed) printJob.fail(readable)
        }

        // A failure here used to vanish: runCatching with no onFailure. It is
        // recorded now, where the printer report can show it.
        private fun onMain(what: String, block: () -> Unit) {
            main.post {
                runCatching(block).onFailure {
                    Diagnostics.record(TAG, "$what on job $key failed: $it")
                }
            }
        }
    }

    /**
     * Owns every PrinterId and every addPrinters and removePrinters call made on
     * behalf of one discovery session.
     */
    internal class Publisher(
        private val service: PrintService,
        private val session: PrinterDiscoverySession,
    ) {
        /**
         * Availability is decided by the caller, off the main thread, because
         * the radio and USB checks are binder calls. Only the verdict crosses
         * onto the looper with the rest of the PrinterInfo build.
         */
        fun publish(printers: List<Printer>, availability: Map<String, PrinterAvailability>) {
            if (printers.isEmpty()) return
            main.post {
                val infos: List<PrinterInfo> = printers.mapNotNull { printer ->
                    runCatching {
                        buildPrinterInfo(
                            printer = printer,
                            printerId = service.generatePrinterId(printer.id),
                            packageName = service.packageName,
                            availability = availability[printer.id] ?: PrinterAvailability.IDLE,
                        )
                    }.onFailure {
                        Diagnostics.record(TAG, "could not describe ${printer.displayName}: $it")
                    }.getOrNull()
                }
                Log.i(TAG, "publishing ${infos.size} printers to the framework")
                if (infos.isEmpty()) return@post
                runCatching { session.addPrinters(infos) }.onFailure {
                    Diagnostics.record(TAG, "addPrinters rejected ${infos.size} printers: $it")
                }
            }
        }

        fun retire(localIds: List<String>) {
            if (localIds.isEmpty()) return
            main.post {
                runCatching {
                    session.removePrinters(localIds.map { service.generatePrinterId(it) })
                }.onFailure {
                    Diagnostics.record(TAG, "removePrinters failed for ${localIds.size}: $it")
                }
            }
        }
    }
}
