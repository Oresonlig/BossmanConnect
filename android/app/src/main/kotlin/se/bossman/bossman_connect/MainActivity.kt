package se.bossman.bossman_connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

/** Bryggan mellan Flutter-gränssnittet (lib/main.dart) och Bluetooth (BoseLink.kt). */
class MainActivity : FlutterActivity() {

    private lateinit var channel: MethodChannel
    private var pendingPermission: MethodChannel.Result? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("bossman", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeOldUpdateFiles()
    }

    /** ota_update lämnar den nedladdade APK:n kvar i files/ota_update/ — den har gjort sitt när appen startat. */
    private fun removeOldUpdateFiles() {
        try {
            File(filesDir, "ota_update").listFiles()?.forEach { f -> if (f.name.endsWith(".apk")) f.delete() }
        } catch (_: Exception) {
            // Städning får aldrig fälla appen.
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "bossman/bose")
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermission" -> requestBtPermission(result)
                "bondedDevices" -> bondedDevices(result)
                "loadSettings" -> result.success(
                    mapOf(
                        "primary" to prefs.getString("primary", null),
                        "commands" to prefs.getString("commands", ""),
                    )
                )
                "saveSettings" -> {
                    prefs.edit()
                        .putString("primary", call.argument<String>("primary"))
                        .putString("commands", call.argument<String>("commands"))
                        .apply()
                    result.success(null)
                }
                "connect" -> connect(
                    call.argument<String>("address") ?: "",
                    call.argument<String>("commands") ?: "",
                    result,
                )
                else -> result.notImplemented()
            }
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
        // Läser bara telefonens lista över parkopplade enheter — ingen anslutning, ingen sökning.
        // Alias = namnet du själv gett enheten i telefonens Bluetooth-inställningar (Android 11+).
        result.success(adapter.bondedDevices.map {
            val name = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.alias else null) ?: it.name ?: "Unknown"
            mapOf("name" to name, "address" to it.address)
        })
    }

    private fun connect(address: String, commandsText: String, result: MethodChannel.Result) {
        val commands = try {
            BoseLink.parseCommands(commandsText)
        } catch (e: IllegalArgumentException) {
            result.error("BAD_COMMANDS", e.message, null)
            return
        }
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
        Thread {
            val ok = BoseLink.run(device, commands) { msg -> mainHandler.post { channel.invokeMethod("log", msg) } }
            mainHandler.post { result.success(ok) }
        }.start()
    }

    companion object {
        private const val REQ_BT = 4711
    }
}
