package com.gulshan.pocketprint.print

/**
 * Turns what the transport said into something the user can do something about.
 *
 * Job history showed raw exception text. "read failed, socket might closed or
 * timeout, read ret: -1" is an accurate description of a file descriptor and
 * tells the person holding the printer nothing at all — least of all that the
 * printer has probably gone to sleep and the fix is to press its power button.
 *
 * The original message is never replaced, only explained: it is what makes a
 * printer report useful, and a guess dressed up as a diagnosis would be worse
 * than the raw text. Anything not recognised gets no explanation rather than a
 * generic one.
 */
object JobError {

    fun explain(raw: String?): String? {
        val message = raw?.lowercase() ?: return null
        return when {
            "socket might closed" in message ||
                "broken pipe" in message ||
                "connection reset" in message ->
                "The printer closed the connection part way through. Thermal " +
                    "printers sleep aggressively - wake it and try again."

            "stopped accepting data" in message ->
                "The printer stopped taking bytes without closing the connection, " +
                    "which usually means it is out of paper, its cover is open, or " +
                    "it is waiting at a fault it has not reported."

            "bluetooth is turned off" in message ->
                "Turn Bluetooth on and try again."

            "permission" in message && "bluetooth" in message ->
                "PocketPrint needs the Bluetooth permission to reach this printer. " +
                    "Grant it in Android's app settings."

            "econnrefused" in message || "connection refused" in message ->
                "The printer answered but is not listening on that port. Check the " +
                    "port number - raw printing is usually 9100, IPP is 631."

            "etimedout" in message || "timed out" in message || "timeout" in message ->
                "The printer did not answer. Check it is switched on, and that it is " +
                    "in range or on the same network."

            "ehostunreach" in message || "enetunreach" in message ->
                "That address cannot be reached from this network. A printer on a " +
                    "different subnet, or a phone on mobile data rather than Wi-Fi, " +
                    "will both look like this."

            "device or resource busy" in message || "resource busy" in message ->
                "Something else is already connected to this printer. These printers " +
                    "accept one connection at a time."

            "not bonded" in message || "authentication" in message ->
                "Pairing failed. Remove the printer in Android's Bluetooth settings " +
                    "and pair it again - the PIN is usually 0000."

            "no printer" in message || "printer not found" in message ->
                "That printer is no longer saved. Set it up again."

            "out of paper" in message || "media" in message && "empty" in message ->
                "The printer reports it is out of media."

            else -> null
        }
    }
}
