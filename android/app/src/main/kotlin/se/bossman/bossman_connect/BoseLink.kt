package se.bossman.bossman_connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

internal fun ByteArray.toAddress(): String = joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

internal fun parseAddress(s: String?): ByteArray? {
    val parts = s?.split(':') ?: return null
    if (parts.size != 6) return null
    return try { ByteArray(6) { parts[it].toInt(16).toByte() } } catch (_: NumberFormatException) { null }
}

/**
 * Boses kontrollprotokoll (RFCOMM via SPP), avläst ur en btsnoop-logg av Bose Connect (2026-11-26):
 *   ramar = [block] [funktion] [operation] [längd] [payload]
 *   operation: 01 GET, 05 START, 03 STATUS, 07 RESULT, 06 PROCESSING, 04 ERROR
 * Högtalaren säger ingenting förrän telefonen skickar något.
 *
 * Observerat:
 *   00 01  firmware            → 00 01 03 05 "1.0.4"
 *   02 02  batteri             → 02 02 03 01 <procent>   (0x50 = 80 %, stämde med HFP-nivån)
 *   05 01  ljudkälla           → 05 01 03 09 00 02 01 <telefon>   (ERROR 05 01 04 01 06 en stund efter länk)
 *   04 04  parade enheter      → 04 04 03 <n> <bitmask anslutna> <adr>…
 *   04 05  info om en enhet    → 04 05 03 <n> <adr> <3 byte> <namn>
 *   04 01  party link          → 04 01 05 0D 90 <partner> <telefon>; klar när 04 01 06 07 <partner> 01 kommer
 *   04 02  unlink/koppla bort  → 04 02 05 06 <adr>   (partner eller annan ansluten enhet)
 *   05 06  låtinfo             → 05 06 03 <n> <00 titel | 01 artist | 02 album?> <text>
 */
object BoseProtocol {
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Kanalen Bose Connect använde båda gångerna. Ninja har två SPP-poster, så UUID kan ge fel kanal. */
    const val BOSE_CHANNEL = 8

    const val OP_GET = 0x01
    const val OP_STATUS = 0x03
    const val OP_ERROR = 0x04
    const val OP_START = 0x05
    const val OP_PROCESSING = 0x06
    const val OP_RESULT = 0x07

    /** Party mode-flaggan Bose Connect skickade vid länkning. */
    const val PARTY_MODE = 0x90

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
}

/**
 * Högtalaren svarade inte i tid — anslutningen är INTE bruten. Får aldrig stänga sessionen:
 * det kopplade bort party-partnern (fälttest 2026-11-26, Ninja svarade inte på 04 04 direkt efter länk).
 */
class NoAnswer(what: String) : Exception("no answer to '$what' (speaker busy?)")

class Frame(val block: Int, val func: Int, val op: Int, val payload: ByteArray) {
    fun isReply(block: Int, func: Int) =
        this.block == block && this.func == func &&
            (op == BoseProtocol.OP_STATUS || op == BoseProtocol.OP_RESULT || op == BoseProtocol.OP_ERROR)

    override fun toString() =
        byteArrayOf(block.toByte(), func.toByte(), op.toByte(), payload.size.toByte()).toHex() +
            (if (payload.isEmpty()) "" else " " + payload.toHex())
}

/** Läser Bose-ramar ur strömmen; tål att flera ramar kommer i samma paket. */
internal class FrameReader(private val input: InputStream, private val log: (String) -> Unit) {
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
            val n = input.available()
            if (n > 0) {
                val chunk = ByteArray(minOf(n, 1024))
                val r = input.read(chunk)
                if (r < 0) throw IOException("speaker closed the connection")
                if (r > 0) buf += chunk.copyOf(r)
            } else {
                if (System.currentTimeMillis() >= end) return null
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

class PairedDevice(val address: ByteArray, val name: String, val connected: Boolean)

/**
 * En öppen kontrollkanal mot en Bose-högtalare. Hålls öppen så länge appen vill (Bose Connect
 * gjorde likadant) — att stänga för tidigt avbröt party-länken ("connection lost", 2026-11-26).
 * Alla anrop görs från EN bakgrundstråd (MainActivity:s executor).
 */
class BoseSession internal constructor(
    private val socket: BluetoothSocket,
    private val reader: FrameReader,
    private val log: (String) -> Unit,
    val firmware: String,
) : AutoCloseable {
    private val out: OutputStream = socket.outputStream

    val isOpen: Boolean get() = socket.isConnected

    private fun send(vararg bytes: Int) {
        val b = ByteArray(bytes.size) { bytes[it].toByte() }
        log("→ ${b.toHex()}")
        out.write(b)
        out.flush()
    }

    private fun ByteArray.ints() = map { it.toInt() and 0xFF }.toIntArray()

    /** Läser in det högtalaren skickat på eget initiativ (loggas). */
    fun pump() { reader.await(0) { false } }

    /** Batteri i procent, eller null om högtalaren inte svarar. */
    fun battery(): Int? {
        send(0x02, 0x02, BoseProtocol.OP_GET, 0x00)
        val f = reader.await(2500) { it.isReply(0x02, 0x02) } ?: return null
        if (f.op != BoseProtocol.OP_STATUS || f.payload.isEmpty()) return null
        return (f.payload[0].toInt() and 0xFF).takeIf { it in 0..100 }
    }

    /** Telefonens adress enligt högtalaren; null om den är upptagen. */
    fun phone(): ByteArray? {
        send(0x05, 0x01, BoseProtocol.OP_GET, 0x00)
        val f = reader.await(2500) { it.isReply(0x05, 0x01) } ?: return null
        if (f.op != BoseProtocol.OP_STATUS || f.payload.size < 9) return null
        return f.payload.copyOfRange(3, 9)
    }

    /** Högtalarens parade enheter med namn och om de är anslutna just nu. */
    fun paired(): List<PairedDevice> {
        send(0x04, 0x04, BoseProtocol.OP_GET, 0x00)
        val list = reader.await(3000) { it.isReply(0x04, 0x04) } ?: throw NoAnswer("paired devices")
        if (list.op != BoseProtocol.OP_STATUS || list.payload.isEmpty()) throw NoAnswer("paired devices: $list")
        val mask = list.payload[0].toInt() and 0xFF
        val result = mutableListOf<PairedDevice>()
        var index = 0
        var i = 1
        while (i + 6 <= list.payload.size) {
            val addr = list.payload.copyOfRange(i, i + 6)
            send(0x04, 0x05, BoseProtocol.OP_GET, 0x06, *addr.ints())
            val info = reader.await(3000) {
                it.isReply(0x04, 0x05) && it.payload.size >= 6 && it.payload.copyOfRange(0, 6).contentEquals(addr)
            }
            val name = if (info != null && info.op == BoseProtocol.OP_STATUS && info.payload.size > 9)
                String(info.payload, 9, info.payload.size - 9, Charsets.UTF_8) else addr.toAddress()
            result += PairedDevice(addr, name, (mask shr index) and 1 == 1)
            index++
            i += 6
        }
        return result
    }

    /** Party link med [partner]. Väntar tills högtalaren bekräftar att partnern anslutit. */
    fun party(partner: ByteArray, phone: ByteArray): Boolean {
        send(0x04, 0x01, BoseProtocol.OP_START, 0x0D, BoseProtocol.PARTY_MODE, *partner.ints(), *phone.ints())
        val res = reader.await(15000) { it.isReply(0x04, 0x01) }
        if (res == null) { log("No answer to the link command."); return false }
        if (res.op == BoseProtocol.OP_ERROR) { log("Speaker refused the link command."); return false }
        // 2–6 s efter RESULT i Bose Connects logg.
        log("Waiting for the partner speaker to connect …")
        val ok = reader.await(25000) {
            it.block == 0x04 && it.func == 0x01 && it.op == BoseProtocol.OP_PROCESSING && it.payload.size >= 7 &&
                it.payload.copyOfRange(0, 6).contentEquals(partner) && it.payload[6].toInt() == 0x01
        }
        log(if (ok != null) "Partner connected." else "Partner never confirmed the connection.")
        return ok != null
    }

    fun unlink(partner: ByteArray): Boolean {
        send(0x04, 0x02, BoseProtocol.OP_START, 0x06, *partner.ints())
        val res = reader.await(8000) { it.isReply(0x04, 0x02) }
        if (res == null) { log("No answer to the unlink command."); return false }
        if (res.op == BoseProtocol.OP_ERROR) { log("Speaker refused the unlink command."); return false }
        reader.await(1500) { false }
        return true
    }

    /**
     * Kopplar bort en annan enhet (t.ex. en telefon) från högtalaren. Samma kommando som unlink (04 02).
     * Fälttest 2026-11-27: Ninja bekräftade bortkopplingen av en telefon med 04 02 06 <adr> 3 s efter RESULT.
     */
    fun disconnectDevice(address: ByteArray): Boolean {
        send(0x04, 0x02, BoseProtocol.OP_START, 0x06, *address.ints())
        val res = reader.await(8000) { it.isReply(0x04, 0x02) }
        if (res == null) { log("No answer to the disconnect command."); return false }
        if (res.op == BoseProtocol.OP_ERROR) { log("Speaker refused the disconnect command."); return false }
        reader.await(5000) {
            it.block == 0x04 && it.func == 0x02 && it.op == BoseProtocol.OP_PROCESSING && it.payload.size >= 6 &&
                it.payload.copyOfRange(0, 6).contentEquals(address)
        }
        reader.await(1000) { false }
        return true
    }

    fun sendRaw(cmd: ByteArray) {
        log("→ ${cmd.toHex()}")
        out.write(cmd)
        out.flush()
        reader.await(900) { false }
    }

    override fun close() { try { socket.close() } catch (_: IOException) {} }

    companion object {
        /**
         * Öppnar en kanal och kontrollerar att det är Boses kontrollkanal: frågar efter firmware
         * (00 01 01 00) och kräver svaret 00 01 03 …. null = ingen Bose-kanal (t.ex. Jabra).
         */
        @SuppressLint("MissingPermission")
        fun open(device: BluetoothDevice, log: (String) -> Unit): BoseSession? {
            val ways = listOf<Pair<String, () -> BluetoothSocket>>(
                "SPP" to { device.createRfcommSocketToServiceRecord(BoseProtocol.SPP_UUID) },
                "channel ${BoseProtocol.BOSE_CHANNEL}" to {
                    device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, BoseProtocol.BOSE_CHANNEL) as BluetoothSocket
                },
            )
            for ((name, make) in ways) {
                var socket: BluetoothSocket? = null
                try {
                    log("Connecting via $name …")
                    socket = make()
                    socket.connect()
                    val reader = FrameReader(socket.inputStream, log)
                    val b = byteArrayOf(0x00, 0x01, BoseProtocol.OP_GET.toByte(), 0x00)
                    log("→ ${b.toHex()}")
                    socket.outputStream.write(b)
                    socket.outputStream.flush()
                    val fw = reader.await(2000) { it.block == 0x00 && it.func == 0x01 }
                    if (fw != null && fw.op == BoseProtocol.OP_STATUS) {
                        val version = String(fw.payload, Charsets.US_ASCII)
                        log("Bose control channel OK (firmware $version)")
                        return BoseSession(socket, reader, log, version)
                    }
                    log("  not the Bose control channel")
                    socket.close()
                } catch (e: Exception) {
                    log("  didn't work: ${e.message ?: e.javaClass.simpleName}")
                    try { socket?.close() } catch (_: IOException) {}
                }
            }
            return null
        }
    }
}
