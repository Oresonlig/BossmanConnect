package se.bossman.bossman_connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Talar med Bose-högtalarens kontrollkanal (RFCOMM) och skickar råa kommandon.
 * Kommandona är exakt de byte som Bose Connect skickar – se README för hur de fångas.
 */
object BoseLink {
    /** Boses kontrolltjänst (används av omvänt utvecklade Bose-klienter). */
    private val BOSE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
    /** Vanlig seriell port, som reserv. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** En rad per kommando. Allt efter '#' är kommentar. Mellanslag/kolon/komma ignoreras. */
    fun parseCommands(text: String): List<ByteArray> =
        text.lines()
            .map { it.substringBefore('#').replace(Regex("[\\s:,]"), "") }
            .filter { it.isNotEmpty() }
            .map { hex ->
                require(hex.matches(Regex("[0-9A-Fa-f]+"))) { "Invalid characters in command: $hex" }
                require(hex.length % 2 == 0) { "Odd number of hex digits in command: $hex" }
                ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }

    fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * Ansluter, skickar alla kommandon och loggar svaren. Försöker igen vid fel.
     * Körs på en bakgrundstråd.
     */
    fun run(device: BluetoothDevice, commands: List<ByteArray>, retries: Int = 4, log: (String) -> Unit): Boolean {
        for (attempt in 1..retries) {
            try {
                open(device, log).use { socket ->
                    val out = socket.outputStream
                    val input = socket.inputStream
                    drain(input, 600, log)          // högtalaren skickar ibland något direkt
                    for (cmd in commands) {
                        log("→ ${cmd.toHex()}")
                        out.write(cmd)
                        out.flush()
                        drain(input, 900, log)
                    }
                }
                return true
            } catch (e: Exception) {
                log("Attempt $attempt/$retries failed: ${e.message}")
                if (attempt < retries) Thread.sleep(1500L * attempt)
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun open(device: BluetoothDevice, log: (String) -> Unit): BluetoothSocket {
        val ways = listOf<Pair<String, () -> BluetoothSocket>>(
            "Bose channel" to { device.createRfcommSocketToServiceRecord(BOSE_UUID) },
            "Bose channel (insecure)" to { device.createInsecureRfcommSocketToServiceRecord(BOSE_UUID) },
            "SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
        )
        var last: Exception? = null
        for ((name, make) in ways) {
            var socket: BluetoothSocket? = null
            try {
                log("Connecting via $name …")
                socket = make()
                socket.connect()
                log("Connected via $name")
                return socket
            } catch (e: Exception) {
                last = e
                log("  didn't work: ${e.message}")
                try { socket?.close() } catch (_: IOException) {}
            }
        }
        throw IOException("couldn't open the control channel", last)
    }

    private fun drain(input: InputStream, millis: Long, log: (String) -> Unit) {
        val end = System.currentTimeMillis() + millis
        val buf = ByteArray(1024)
        while (System.currentTimeMillis() < end) {
            val n = input.available()
            if (n > 0) {
                val r = input.read(buf, 0, minOf(n, buf.size))
                if (r > 0) log("← ${buf.copyOf(r).toHex()}")
            } else {
                Thread.sleep(50)
            }
        }
    }
}
