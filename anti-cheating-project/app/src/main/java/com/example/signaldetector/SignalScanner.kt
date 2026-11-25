package com.example.signaldetector

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class SignalStore {
    private val map = ConcurrentHashMap<String, Signal>()
    private val baselineIds = ConcurrentHashMap.newKeySet<String>()

    fun upsert(signal: Signal) {
        val existing = map[signal.id]
        val firstSeen = existing?.firstSeenMs ?: signal.firstSeenMs
        map[signal.id] = signal.copy(firstSeenMs = firstSeen)
    }

    fun all(): List<Signal> = map.values.toList()

    fun get(id: String): Signal? = map[id]

    fun clear() {
        map.clear()
        baselineIds.clear()
    }

    fun markCurrentAsBaseline() {
        baselineIds.clear()
        baselineIds.addAll(map.keys)
    }

    fun isNewSignal(id: String): Boolean = !baselineIds.contains(id)

    fun baselineCount(): Int = baselineIds.size
}

class SignalScanner(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onSignalDetected(signal: Signal)
        fun onStatusMessage(msg: String)
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager?.adapter
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var scanJob: Job? = null
    private var isScanning = false
    private var receiversRegistered = false

    private val bleCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val rssi = result.rssi
            val now = System.currentTimeMillis()
            val rawName = try { device.name } catch (_: SecurityException) { null }
            val analysis = PacketAnalyzer.analyzeBle(rawName, address, result.scanRecord, rssi)

            val signal = Signal(
                id = "BLE_$address",
                type = SignalType.BLE,
                name = analysis.displayName,
                address = address,
                rssi = rssi,
                extraInfo = analysis.tags.joinToString(", "),
                lastSeenMs = now,
                firstSeenMs = now,
                analysis = analysis
            )
            listener.onSignalDetected(signal)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onStatusMessage("BLE 스캔 실패 (코드 $errorCode)")
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (BluetoothDevice.ACTION_FOUND == intent?.action) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                if (device != null && rssi != Short.MIN_VALUE.toInt()) {
                    val address = device.address ?: return
                    val rawName = try { device.name } catch (_: SecurityException) { null }
                    val analysis = PacketAnalyzer.analyzeClassicBt(rawName, address, rssi)
                    val now = System.currentTimeMillis()
                    listener.onSignalDetected(
                        Signal(
                            id = "BT_$address",
                            type = SignalType.CLASSIC_BT,
                            name = analysis.displayName,
                            address = address,
                            rssi = rssi,
                            extraInfo = analysis.tags.joinToString(", "),
                            lastSeenMs = now,
                            firstSeenMs = now,
                            analysis = analysis
                        )
                    )
                }
            }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION == intent?.action) {
                readWifiResults()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isScanning) return
        isScanning = true
        registerReceivers()

        if (btAdapter == null) {
            listener.onStatusMessage("이 기기는 블루투스를 지원하지 않습니다.")
        } else if (!btAdapter.isEnabled) {
            listener.onStatusMessage("블루투스가 꺼져 있습니다. 켜야 블루투스 탐색이 됩니다.")
        } else {
            try {
                val scanner = btAdapter.bluetoothLeScanner
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner?.startScan(null, settings, bleCallback)
            } catch (_: SecurityException) {
                listener.onStatusMessage("블루투스 권한이 없습니다.")
            }
        }

        if (wifiManager != null && !wifiManager.isWifiEnabled) {
            listener.onStatusMessage("Wi-Fi가 꺼져 있습니다. 켜야 Wi-Fi 탐색이 됩니다.")
        }

        scanJob = scope.launch(Dispatchers.Default) {
            while (isActive && isScanning) {
                try {
                    @Suppress("DEPRECATION")
                    wifiManager?.startScan()
                    readWifiResults()
                } catch (_: Exception) {
                }
                delay(5000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        isScanning = false
        scanJob?.cancel()
        scanJob = null
        try {
            btAdapter?.bluetoothLeScanner?.stopScan(bleCallback)
        } catch (_: Exception) {
        }
        unregisterReceivers()
    }

    @SuppressLint("MissingPermission")
    private fun readWifiResults() {
        val results = try {
            wifiManager?.scanResults.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val now = System.currentTimeMillis()
        for (res in results) {
            val bssid = res.BSSID ?: continue
            val analysis = PacketAnalyzer.analyzeWifi(res)
            listener.onSignalDetected(
                Signal(
                    id = "WIFI_$bssid",
                    type = SignalType.WIFI,
                    name = analysis.displayName,
                    address = bssid,
                    rssi = res.level,
                    extraInfo = analysis.bandInfo,
                    lastSeenMs = now,
                    firstSeenMs = now,
                    analysis = analysis
                )
            )
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val btFilter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        val wifiFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(btReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(wifiReceiver, wifiFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(btReceiver, btFilter)
            context.registerReceiver(wifiReceiver, wifiFilter)
        }
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        try {
            context.unregisterReceiver(btReceiver)
            context.unregisterReceiver(wifiReceiver)
        } catch (_: Exception) {
        }
        receiversRegistered = false
    }
}
