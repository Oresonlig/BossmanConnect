package se.bossman.bossman_connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

private fun ByteArray.toAddress(): String = joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

/**
 * Talar med Bose-högtalarens kontrollkanal (RFCOMM, SPP) med Boses eget protokoll.
 *
 * Protokollet är avläst ur en btsnoop-logg av Bose Connect (2026-11-26):
 *   ramar = [block] [funktion] [operation] [längd] [payload]
 *   operation: 01 GET, 05 START, 03 STATUS, 07 RESULT, 06 PROCESSING, 04 ERROR
 * Ninja säger ingenting förrän telefonen skickar något.
 */
object BoseLink {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Kanalen Bose Connect använde båda gångerna i loggen. Ninja har två SPP-poster, så UUID kan ge fel kanal. */
    private const val BOSE_CHANNEL = 8

    private const val OP_GET: Int = 0x01
    private const val OP_START: Int = 0x05
    private const val OP_STATUS: Int = 0x03
    private const val OP_ERROR: Int = 0x04
    private const val OP_RESULT: Int = 0x07

    /** Party mode-flaggan Bose Connect skickade i "länka". */
    private const val PARTY_MODE: Int = 0x90

    class Frame(val block: Int, val func: Int, val op: Int, val payload: ByteArray) {
        override fun toString() = byteArrayOf(block.toByte(), func.toByte(), op.toByte(), payload.size.toByte()).toHex() +
            (if (payload.isEmpty()) "" else " " + payload.toHex())
    }

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

    /** Läser Bose-ramar ur strömmen; tål att flera ramar kommer i samma paket. */
    private class Reader(private val input: InputStream, private val log: (String) -> Unit) {
        private var buf = ByteArray(0)

        /** Väntar på en ram som matchar [match]; loggar allt som kommer. null vid timeout. */
        fun await(millis: Long, match: (Frame) -> Boolean): Frame? {
            val end = System.currentTimeMillis() + millis
            while (true) {
                while (true) {
                    val f = next() ?: break
                    log("← $f")
                    if (match(f)) return f
                }
                if (System.currentTimeMillis() >= end) return null
                val n = input.available()
                if (n > 0) {
                    val chunk = ByteArray(minOf(n, 1024))
                    val r = input.read(chunk)
                    if (r > 0) buf += chunk.copyOf(r)
                } else {
                    Thread.sleep(30)
                }
            }
        }

        private fun next(): Frame? {
            if (buf.size < 4) return null
            val len = buf[3].toInt() and 0xFF
            if (buf.size < 4 + len) return null
            val f = Frame(buf[0].toInt() and 0xFF, buf[1].toInt() and 0xFF, buf[2].toInt() and 0xFF, buf.copyOfRange(4, 4 + len))
            buf = buf.copyOfRange(4 + len, buf.size)
            return f
        }
    }

    private fun send(out: OutputStream, log: (String) -> Unit, vararg bytes: Int) {
        val b = ByteArray(bytes.size) { bytes[it].toByte() }
        log("→ ${b.toHex()}")
        out.write(b)
        out.flush()
    }

    private fun isReply(f: Frame, block: Int, func: Int) =
        f.block == block && f.func == func && (f.op == OP_STATUS || f.op == OP_RESULT || f.op == OP_ERROR)

    /**
     * Länkar Ninja (som [device] pekar på) med Lantern i party mode, exakt som Bose Connect gör.
     * Körs på en bakgrundstråd.
     */
    fun partyLink(device: BluetoothDevice, retries: Int = 3, log: (String) -> Unit): Boolean {
        for (attempt in 1..retries) {
            try {
                openVerified(device, log).use { (socket, reader) ->
                    return linkOn(socket.outputStream, reader, log)
                }
            } catch (e: Exception) {
                log("Attempt $attempt/$retries failed: ${e.message}")
                if (attempt < retries) Thread.sleep(1500L * attempt)
            }
        }
        return false
    }

    private fun linkOn(out: OutputStream, reader: Reader, log: (String) -> Unit): Boolean {
        // 1. Telefonens egen adress (Android lämnar inte ut den, men Ninja gör det).
        send(out, log, 0x05, 0x01, OP_GET, 0x00)
        val src = reader.await(3000) { isReply(it, 0x05, 0x01) }
            ?: throw IOException("no answer to 'audio source'")
        if (src.op != OP_STATUS || src.payload.size < 9) throw IOException("unexpected 'audio source' answer: $src")
        val phone = src.payload.copyOfRange(3, 9)
        log("Phone: ${phone.toAddress()}")

        // 2. Ninjas parade enheter, och namnet på var och en.
        send(out, log, 0x04, 0x04, OP_GET, 0x00)
        val list = reader.await(3000) { isReply(it, 0x04, 0x04) }
            ?: throw IOException("no answer to 'paired devices'")
        if (list.op != OP_STATUS || list.payload.size < 7) throw IOException("unexpected 'paired devices' answer: $list")
        val addresses = (1 until list.payload.size step 6)
            .filter { it + 6 <= list.payload.size }
            .map { list.payload.copyOfRange(it, it + 6) }
            .filterNot { it.contentEquals(phone) }

        var lantern: ByteArray? = null
        val names = mutableListOf<String>()
        for (addr in addresses) {
            send(out, log, 0x04, 0x05, OP_GET, 0x06, *addr.map { it.toInt() and 0xFF }.toIntArray())
            val info = reader.await(3000) { isReply(it, 0x04, 0x05) && it.payload.size >= 6 && it.payload.copyOfRange(0, 6).contentEquals(addr) }
                ?: continue
            if (info.payload.size <= 9) continue
            val name = String(info.payload, 9, info.payload.size - 9, Charsets.UTF_8)
            names += name
            if (name.contains("lantern", ignoreCase = true)) lantern = addr
        }
        if (lantern == null) {
            log("Lantern not found among Ninja's paired devices: ${names.joinToString()}")
            return false
        }
        log("Lantern: ${lantern.toAddress()}")

        // 3. Party link: 04 01 05 0D 90 <Lantern> <telefon> — byte för byte som Bose Connect.
        send(out, log, 0x04, 0x01, OP_START, 0x0D, PARTY_MODE,
            *lantern.map { it.toInt() and 0xFF }.toIntArray(),
            *phone.map { it.toInt() and 0xFF }.toIntArray())
        val res = reader.await(15000) { isReply(it, 0x04, 0x01) }
        if (res == null) {
            log("No answer to the link command.")
            return false
        }
        if (res.op == OP_ERROR) {
            log("Speaker refused the link command.")
            return false
        }
        // Ninja rapporterar när Lantern faktiskt anslutit (04 01 06 … 01 i loggen, ~2 s senare). Logga det som kommer.
        reader.await(4000) { false }
        return true
    }

    /**
     * Ansluter, skickar alla kommandon och loggar svaren. Försöker igen vid fel.
     * Används bara när användaren skrivit egna kommandon under Advanced.
     */
    fun run(device: BluetoothDevice, commands: List<ByteArray>, retries: Int = 4, log: (String) -> Unit): Boolean {
        for (attempt in 1..retries) {
            try {
                openVerified(device, log).use { (socket, reader) ->
                    val out = socket.outputStream
                    for (cmd in commands) {
                        log("→ ${cmd.toHex()}")
                        out.write(cmd)
                        out.flush()
                        reader.await(900) { false }
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

    private class Conn(val socket: BluetoothSocket, val reader: Reader) : AutoCloseable {
        operator fun component1() = socket
        operator fun component2() = reader
        override fun close() { try { socket.close() } catch (_: IOException) {} }
    }

    /**
     * Öppnar en kanal och kontrollerar att det är Boses kontrollkanal: frågar efter
     * firmware (00 01 01 00) och kräver svaret 00 01 03 …. Annars nästa sätt.
     */
    @SuppressLint("MissingPermission")
    private fun openVerified(device: BluetoothDevice, log: (String) -> Unit): Conn {
        val ways = listOf<Pair<String, () -> BluetoothSocket>>(
            "SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "channel $BOSE_CHANNEL" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, BOSE_CHANNEL) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for ((name, make) in ways) {
            var socket: BluetoothSocket? = null
            try {
                log("Connecting via $name …")
                socket = make()
                socket.connect()
                val reader = Reader(socket.inputStream, log)
                send(socket.outputStream, log, 0x00, 0x01, OP_GET, 0x00)
                val fw = reader.await(2000) { it.block == 0x00 && it.func == 0x01 }
                if (fw != null && fw.op == OP_STATUS) {
                    log("Bose control channel OK (firmware ${String(fw.payload, Charsets.US_ASCII)})")
                    return Conn(socket, reader)
                }
                log("  not the Bose control channel")
                socket.close()
            } catch (e: Exception) {
                last = e
                log("  didn't work: ${e.message ?: e.javaClass.simpleName}")
                try { socket?.close() } catch (_: IOException) {}
            }
        }
        throw IOException("couldn't open the Bose control channel", last)
    }
}
