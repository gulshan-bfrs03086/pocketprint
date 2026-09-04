package com.gulshan.pocketprint.print

/**
 * How a caller hears about a job while it is running.
 *
 * One object rather than a growing tail of lambda parameters on four engine
 * methods. Each callback is optional, and each is called from whatever thread
 * the job happens to be on, so implementations must not touch anything that
 * demands the main thread without posting to it.
 */
data class JobListener(
    /** Cumulative bytes written, and the total to expect. */
    val onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },

    /**
     * A human-readable stage, for the rare transport that reports one. In
     * practice this means IPP: "processing", "processing stopped - media
     * empty error", and so on.
     */
    val onStatus: (String) -> Unit = {},

    /**
     * True when the job has to queue behind another job on the same printer,
     * false when it stops waiting and starts. The system print dialog turns
     * these into PrintJob.block(reason) and PrintJob.start(), which is the only
     * way a user finds out their job is waiting rather than stuck.
     */
    val onWaitingForPrinter: (waiting: Boolean) -> Unit = {},
)
