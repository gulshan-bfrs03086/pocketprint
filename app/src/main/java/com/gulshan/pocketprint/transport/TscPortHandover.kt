package com.gulshan.pocketprint.transport

import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Hands TSC's library a socket that PocketPrint connected itself, so it can be
 * used on Android 12 and newer without the app asking to scan.
 *
 * Every `openport` variant in the library calls `BluetoothAdapter.cancelDiscovery()`
 * before it dials, and does not catch the refusal. From Android 12 that call needs
 * `BLUETOOTH_SCAN`, which this app does not hold and does not want: it never
 * scans, and the call is there only in case something else is scanning. So on
 * those versions the app does what `openport` does, minus that one call - an
 * RFCOMM socket to the serial-port UUID, connected, which needs only the
 * `BLUETOOTH_CONNECT` the app already has - and puts the result where `openport`
 * would have put it.
 *
 * Where that is was read out of the library's own code (`TSCActivity.openport`):
 * it stores the socket in `btSocket`, its two streams in `OutStream` and
 * `InStream`, and sets `IsConnected`. The calls PocketPrint uses afterwards -
 * `sendcommand`, `status`, `printername`, `closeport` - read nothing else.
 *
 * Those are private fields of someone else's class, so [of] checks every one of
 * them is there, an instance field, and of a type that can take what will be put
 * in it. A jar where they are not gets null, and the transport says plainly that
 * this copy of the library cannot be used on this Android version, rather than
 * failing somewhere inside it later. proguard-rules.pro keeps the names through R8.
 *
 * Plain reflection over a [Class], with no Android types in it, so it is tested
 * on the JVM - against a stand-in, and against the real jar when the build has it.
 */
class TscPortHandover private constructor(
    private val socket: Field,
    private val output: Field,
    private val input: Field,
    private val connected: Field,
) {

    /**
     * Puts [socket] and its two streams into [library], and marks it connected,
     * exactly as a successful `openport` leaves it.
     */
    fun install(library: Any, socket: Any?, output: OutputStream, input: InputStream) {
        this.socket.set(library, socket)
        this.output.set(library, output)
        this.input.set(library, input)
        this.connected.setBoolean(library, true)
    }

    companion object {
        const val SOCKET_FIELD = "btSocket"
        const val OUTPUT_FIELD = "OutStream"
        const val INPUT_FIELD = "InStream"
        const val CONNECTED_FIELD = "IsConnected"

        /** The fields of [libraryClass] a socket can be handed over through, or null if any is missing. */
        fun of(libraryClass: Class<*>): TscPortHandover? = runCatching {
            TscPortHandover(
                socket = field(libraryClass, SOCKET_FIELD, null),
                output = field(libraryClass, OUTPUT_FIELD, OutputStream::class.java),
                input = field(libraryClass, INPUT_FIELD, InputStream::class.java),
                connected = field(libraryClass, CONNECTED_FIELD, java.lang.Boolean.TYPE),
            )
        }.getOrNull()

        /**
         * One instance field by name, made accessible. [mustTake] is a type the field
         * has to be able to hold - an exact match for the boolean - or null to accept
         * any object type (the socket's is BluetoothSocket, which a JVM test cannot make).
         */
        private fun field(cls: Class<*>, name: String, mustTake: Class<*>?): Field {
            val f = cls.getDeclaredField(name)
            require(!Modifier.isStatic(f.modifiers)) { "$name is static" }
            when {
                mustTake == null -> require(!f.type.isPrimitive) { "$name is a ${f.type}" }
                mustTake.isPrimitive -> require(f.type == mustTake) { "$name is a ${f.type}" }
                else -> require(f.type.isAssignableFrom(mustTake)) { "$name is a ${f.type}" }
            }
            f.isAccessible = true
            return f
        }
    }
}
