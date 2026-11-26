package se.bossman.bossman_connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/** Bryggan mellan Flutter-gränssnittet (lib/main.dart) och Bluetooth (BoseLink.kt). */
class MainActivity : FlutterActivity() {

    private lateinit var channel: MethodChannel
    private var pendingPermission: MethodChannel.Result? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bossman", Context.MODE_PRIVATE) }

    /** All Bluetooth-I/O på EN tråd, så att sessionen aldrig används från två håll samtidigt. */
    private val io = Executors.newSingleThreadExecutor()
    private var session: BoseSession? = null
    private var sessionAddress: String? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var audioReceiver: BroadcastReceiver? = null

    /** A2DP-proxyn: LÄSER om telefonens ljud går till en enhet (publikt API). Ansluta ljud kan en app inte. */
    private var a2dp: BluetoothProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeOldUpdateFiles()
    }

    override fun onDestroy() {
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        audioReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        a2dp?.let { p -> try { adapter()?.closeProfileProxy(BluetoothProfile.A2DP, p) } catch (_: Exception) {} }
        io.execute { session?.close(); session = null }
        io.shutdown()
        super.onDestroy()
    }

    /** ota_update lämnar den nedladdade APK:n kvar i files/ota_update/ — den har gjort sitt när appen startat. */
    private fun removeOldUpdateFiles() {
        try {
            File(filesDir, "ota_update").listFiles()?.forEach { f -> if (f.name.endsWith(".apk")) f.delete() }
        } catch (_: Exception) {
            // Städning får aldrig fälla appen.
        }
    }

    private fun log(msg: String) {
        mainHandler.post { channel.invokeMethod("log", msg) }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "bossman/bose")
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermission" -> requestBtPermission(result)
                "openBluetoothSettings" -> {
                    try {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("NO_SETTINGS", "Couldn't open Bluetooth settings.", null)
                    }
                }
                "bondedDevices" -> bondedDevices(result)
                "loadSettings" -> result.success(
                    mapOf(
                        "primary" to prefs.getString("primary", null),
                        "partner" to prefs.getString("partner", null),
                        "commands" to prefs.getString("commands", ""),
                    )
                )
                "saveSettings" -> {
                    val e = prefs.edit()
                    for (key in listOf("primary", "partner", "commands")) {
                        if (call.hasArgument(key)) e.putString(key, call.argument<String>(key))
                    }
                    e.apply()
                    result.success(null)
                }
                "connect" -> connect(call.argument<String>("address") ?: "", result)
                "status" -> onSession(result) { s -> statusOf(s) }
                "battery" -> onSession(result) { s -> s.battery() ?: -1 }
                "party" -> onSession(result) { s -> party(s, call.argument<String>("partner")) }
                "unlink" -> onSession(result) { s -> unlink(s, call.argument<String>("partner")) }
                "sendRaw" -> {
                    val cmds = try {
                        BoseProtocol.parseCommands(call.argument<String>("commands") ?: "")
                    } catch (e: IllegalArgumentException) {
                        result.error("BAD_COMMANDS", e.message, null)
                        return@setMethodCallHandler
                    }
                    onSession(result) { s -> cmds.forEach { s.sendRaw(it) }; true }
                }
                "disconnect" -> io.execute {
                    session?.close(); session = null; sessionAddress = null
                    mainHandler.post { result.success(null) }
                }
                else -> result.notImplemented()
            }
        }
        listenForBattery()
        listenForAudio()
    }

    // ---- Ljudanslutning (A2DP): bara läsning ----

    @SuppressLint("MissingPermission")
    private fun connectA2dpProxy() {
        if (a2dp != null) return
        try {
            adapter()?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    a2dp = proxy
                    // Gränssnittet kan ha frågat innan proxyn var klar — skicka läget för anslutna enheter.
                    for (d in proxy.connectedDevices) {
                        channel.invokeMethod("audio", mapOf("address" to d.address, "connected" to true))
                    }
                }
                override fun onServiceDisconnected(profile: Int) { a2dp = null }
            }, BluetoothProfile.A2DP)
        } catch (_: Exception) {
            // Utan proxy visas ljudläget som okänt.
        }
    }

    /** true/false, eller null om det inte går att avgöra (proxyn inte klar / saknar behörighet). */
    @SuppressLint("MissingPermission")
    private fun audioConnected(d: BluetoothDevice): Boolean? = try {
        a2dp?.getConnectionState(d)?.let { it == BluetoothProfile.STATE_CONNECTED }
    } catch (_: Exception) { null }

    private fun listenForAudio() {
        if (audioReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) return
                channel.invokeMethod(
                    "audio",
                    mapOf("address" to d.address, "connected" to (state == BluetoothProfile.STATE_CONNECTED)),
                )
            }
        }
        try {
            // Systemets broadcast — kräver ingen exported-flagga, men Android 13+ vill ha en uttrycklig.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(r, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(r, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))
            }
            audioReceiver = r
        } catch (_: Exception) {
            // Ljudläget läses ändå vid CONNECT och uppdatering av listan.
        }
    }

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun requestBtPermission(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            result.success(true)
            return
        }
        pendingPermission = result
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            pendingPermission?.success(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            pendingPermission = null
        }
    }

    // ---- Androids egen batterinivå (HFP/BLE-rapporter) — fungerar för alla enheter, t.ex. Jabra ----

    /** Dold men tillgänglig API (BluetoothDevice.getBatteryLevel); -1 = okänd. */
    private fun androidBattery(d: BluetoothDevice): Int = try {
        d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
    } catch (_: Exception) { -1 }

    private fun androidConnected(d: BluetoothDevice): Boolean = try {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    } catch (_: Exception) { false }

    private fun listenForBattery() {
        if (batteryReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                channel.invokeMethod("battery", mapOf("address" to d.address, "level" to level))
            }
        }
        val filter = IntentFilter(ACTION_BATTERY_LEVEL_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(r, filter)
            }
            batteryReceiver = r
        } catch (_: Exception) {
            // Utan live-uppdatering läses nivån ändå vid uppdatering av listan.
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(result: MethodChannel.Result) {
        val adapter = adapter()
        if (adapter == null) {
            result.error("NO_BT", "This phone has no Bluetooth.", null)
            return
        }
        if (!adapter.isEnabled) {
            result.error("BT_OFF", "Bluetooth is off – turn it on and tap refresh.", null)
            return
        }
        connectA2dpProxy()
        // Läser bara telefonens lista över parkopplade enheter — ingen anslutning, ingen sökning.
        // Alias = namnet du själv gett enheten i telefonens Bluetooth-inställningar (Android 11+).
        result.success(adapter.bondedDevices.map {
            val name = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.alias else null) ?: it.name ?: "Unknown"
            mapOf(
                "name" to name,
                "address" to it.address,
                "battery" to androidBattery(it),
                "connected" to androidConnected(it),
                "audio" to audioConnected(it),
            )
        })
    }

    // ---- Session mot vald högtalare ----

    private fun connect(address: String, result: MethodChannel.Result) {
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            result.error("BT_OFF", "Bluetooth is off.", null)
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            result.error("BAD_ADDRESS", "Invalid speaker address: $address", null)
            return
        }
        io.execute {
            session?.close(); session = null; sessionAddress = null
            val s = BoseSession.open(device, ::log)
            val status: Map<String, Any?> = if (s == null) {
                // Ingen Bose-kanal (t.ex. Jabra): bara Androids batterinivå.
                mapOf("bose" to false, "battery" to androidBattery(device), "connected" to androidConnected(device))
            } else {
                session = s
                sessionAddress = address
                try {
                    statusOf(s)
                } catch (e: NoAnswer) {
                    log(e.message ?: "no answer")
                    mapOf("bose" to true, "firmware" to s.firmware, "battery" to -1, "partners" to emptyList<Any>())
                } catch (e: Exception) {
                    closeAfterError(e); null
                } ?: mapOf("bose" to false)
            }
            mainHandler.post { result.success(status + ("audio" to audioConnected(device))) }
        }
    }

    /** Kör [work] på I/O-tråden mot den öppna sessionen. Tappad anslutning → "disconnected" till Flutter. */
    private fun onSession(result: MethodChannel.Result, work: (BoseSession) -> Any?) {
        io.execute {
            val s = session
            if (s == null) {
                mainHandler.post { result.error("NOT_CONNECTED", "Not connected – tap CONNECT first.", null) }
                return@execute
            }
            try {
                val value = work(s)
                mainHandler.post { result.success(value) }
            } catch (e: NoAnswer) {
                // Upptagen högtalare ≠ bruten anslutning: sessionen (och party-länken) lever kvar.
                log(e.message ?: "no answer")
                mainHandler.post { result.error("NO_ANSWER", e.message, null) }
            } catch (e: Exception) {
                closeAfterError(e)
                mainHandler.post { result.error("LOST", "Lost the connection to the speaker.", null) }
            }
        }
    }

    private fun closeAfterError(e: Exception) {
        log("Connection error: ${e.message ?: e.javaClass.simpleName}")
        session?.close(); session = null; sessionAddress = null
        mainHandler.post { channel.invokeMethod("disconnected", null) }
    }

    private fun statusOf(s: BoseSession): Map<String, Any?> {
        s.pump()
        val phone = s.phone()?.also { prefs.edit().putString("phone", it.toAddress()).apply() }
            ?: parseAddress(prefs.getString("phone", null))
        val paired = s.paired()
        return mapOf(
            "bose" to true,
            "firmware" to s.firmware,
            "battery" to (s.battery() ?: -1),
            "phone" to phone?.toAddress(),
            "partners" to paired
                .filterNot { phone != null && it.address.contentEquals(phone) }
                .map { mapOf("name" to it.name, "address" to it.address.toAddress(), "linked" to it.connected) },
        )
    }

    private fun party(s: BoseSession, partnerAddress: String?): Boolean {
        val partner = parseAddress(partnerAddress) ?: return false.also { log("Pick a partner speaker first.") }
        val phone = s.phone()?.also { prefs.edit().putString("phone", it.toAddress()).apply() }
            ?: parseAddress(prefs.getString("phone", null))?.also { log("Speaker is busy – using the saved phone address") }
            ?: return false.also { log("Speaker is busy and the phone address isn't known yet – try again in a minute.") }
        return s.party(partner, phone)
    }

    private fun unlink(s: BoseSession, partnerAddress: String?): Boolean {
        val partner = parseAddress(partnerAddress) ?: return false.also { log("Pick a partner speaker first.") }
        return s.unlink(partner)
    }

    companion object {
        private const val REQ_BT = 4711
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
