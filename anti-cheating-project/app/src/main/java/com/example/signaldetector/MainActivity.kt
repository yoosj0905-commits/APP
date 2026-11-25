package com.example.signaldetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SignalScanner.Listener, SensorEventListener {

    private val store = SignalStore()
    private lateinit var scanner: SignalScanner
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isScanning = false
    private var scanStartMs = 0L
    private var baselineAutoCaptured = false

    private var onlySuspicious = false
    private var onlyNewSignals = false
    private var activeTracker: SignalTracker? = null

    private var sensorManager: SensorManager? = null
    private var currentAzimuthDeg: Float? = null

    // List Screen Views
    private lateinit var rootContainer: FrameLayout
    private lateinit var listScreen: LinearLayout
    private lateinit var trackScreen: LinearLayout

    private lateinit var btnToggleScan: Button
    private lateinit var btnResetBaseline: Button
    private lateinit var btnClearList: Button
    private lateinit var cbOnlySuspicious: CheckBox
    private lateinit var cbOnlyNew: CheckBox
    private lateinit var tvStatusBanner: TextView
    private lateinit var tvSummaryCounts: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: SignalAdapter

    // Track Screen Views
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvTrackBadge: TextView
    private lateinit var tvTrackReason: TextView
    private lateinit var tvTrackRssi: TextView
    private lateinit var tvTrackDistance: TextView
    private lateinit var tvTrackTrend: TextView
    private lateinit var tvTrackDirection: TextView
    private lateinit var compassView: CompassView
    private lateinit var graphView: GraphView

    private val displayedSignals = ArrayList<Signal>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            beginScanning()
        } else {
            tvStatusBanner.text = "권한이 허용되지 않아 탐색할 수 없습니다. 설정 > 앱 > 권한에서 '근처 기기'와 '위치'를 허용해 주세요."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = SignalScanner(this, this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        rootContainer = FrameLayout(this)
        listScreen = buildListScreen()
        trackScreen = buildTrackScreen()
        trackScreen.visibility = View.GONE

        rootContainer.addView(listScreen)
        rootContainer.addView(trackScreen)
        setContentView(rootContainer)

        startUiRefreshLoops()
    }

    override fun onResume() {
        super.onResume()
        val rotSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor != null) {
            sensorManager?.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stop()
        uiScope.cancel()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            currentAzimuthDeg = (deg + 360f) % 360f
            compassView.currentAzimuthDeg = currentAzimuthDeg ?: 0f
            compassView.invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSignalDetected(signal: Signal) {
        store.upsert(signal)
        val tracker = activeTracker
        if (tracker != null && tracker.targetId == signal.id) {
            tracker.addSample(signal.rssi, currentAzimuthDeg, signal.lastSeenMs)
        }
    }

    override fun onStatusMessage(msg: String) {
        runOnUiThread {
            tvStatusBanner.text = msg
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toggleScanning() {
        if (isScanning) {
            isScanning = false
            scanner.stop()
            btnToggleScan.text = "탐색 시작"
            btnToggleScan.setBackgroundColor(Color.parseColor("#1565C0"))
            tvStatusBanner.text = "중지됨 · '탐색 시작'을 눌러 주변 전파를 감지하세요."
        } else {
            if (hasAllPermissions()) {
                beginScanning()
            } else {
                permissionLauncher.launch(requiredPermissions())
            }
        }
    }

    private fun beginScanning() {
        isScanning = true
        scanStartMs = System.currentTimeMillis()
        baselineAutoCaptured = false
        scanner.start(uiScope)
        btnToggleScan.text = "탐색 중지"
        btnToggleScan.setBackgroundColor(Color.parseColor("#C62828"))
        tvStatusBanner.text = "탐색 중 · 의심 신호(이어폰·워치·핫스팟·숨김WiFi)를 상단에 고정합니다."
    }

    private fun startUiRefreshLoops() {
        uiScope.launch {
            while (isActive) {
                if (isScanning && !baselineAutoCaptured) {
                    val elapsed = (System.currentTimeMillis() - scanStartMs) / 1000L
                    if (elapsed >= 8L) {
                        store.markCurrentAsBaseline()
                        baselineAutoCaptured = true
                    }
                }
                refreshList()
                delay(700L)
            }
        }

        uiScope.launch {
            while (isActive) {
                if (trackScreen.visibility == View.VISIBLE) {
                    updateTrackUi()
                }
                delay(400L)
            }
        }
    }

    private fun refreshList() {
        val all = store.all()
        val suspiciousCount = all.count { it.analysis.isSuspicious }
        val newCount = all.count { store.isNewSignal(it.id) }

        val filtered = all
            .filter { !onlySuspicious || it.analysis.isSuspicious }
            .filter { !onlyNewSignals || store.isNewSignal(it.id) }
            .sortedWith(
                compareByDescending<Signal> { it.analysis.isSuspicious }
                    .thenByDescending { store.isNewSignal(it.id) }
                    .thenByDescending { it.rssi }
            )

        displayedSignals.clear()
        displayedSignals.addAll(filtered)
        adapter.notifyDataSetChanged()

        tvSummaryCounts.text = "전체 ${all.size}개 · 🚨의심 ${suspiciousCount}개 · 신규 ${newCount}개"
    }

    private fun openTrackScreen(signal: Signal) {
        val tracker = SignalTracker(signal.id)
        tracker.addSample(signal.rssi, currentAzimuthDeg, signal.lastSeenMs)
        activeTracker = tracker
        compassView.sectorValues = FloatArray(12) { Float.NaN }
        compassView.targetBearingDeg = null

        listScreen.visibility = View.GONE
        trackScreen.visibility = View.VISIBLE
        updateTrackUi()
    }

    private fun closeTrackScreen() {
        activeTracker = null
        trackScreen.visibility = View.GONE
        listScreen.visibility = View.VISIBLE
    }

    private fun updateTrackUi() {
        val tracker = activeTracker ?: return
        val sig = store.get(tracker.targetId) ?: return
        val now = System.currentTimeMillis()

        tvTrackTitle.text = "[${sig.type.label}] ${sig.name}"
        if (sig.analysis.isSuspicious) {
            tvTrackBadge.text = "🚨 의심 기기 · ${sig.analysis.category.label}"
            tvTrackBadge.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            tvTrackBadge.text = sig.analysis.category.label
            tvTrackBadge.setBackgroundColor(Color.parseColor("#455A64"))
        }
        tvTrackReason.text = "${sig.analysis.suspicionReason} (${sig.analysis.bandInfo})"

        val rssi = tracker.latestRssi() ?: sig.rssi
        val ageSec = tracker.lastSampleAgeSec(now) ?: 0L
        tvTrackRssi.text = "최근 측정값: ${rssi} dBm (${ageSec}초 전 수신)"

        val dist = tracker.estimateDistanceMeters(rssi)
        tvTrackDistance.text = String.format("추정 거리: 대략 %.1fm · %s", dist, tracker.distanceLabel(rssi))

        val trend = tracker.trend(now)
        val slope = tracker.slopeDbPerSec()
        tvTrackTrend.text = String.format("접근 추세: %s (변화율 %+.2f dB/초)", trend.label, slope)

        val dir = tracker.direction()
        compassView.sectorValues = dir.sectorMaxRssi
        compassView.targetBearingDeg = dir.bestBearingDeg
        compassView.invalidate()

        val az = currentAzimuthDeg
        if (dir.bestBearingDeg != null && az != null) {
            val diff = ((dir.bestBearingDeg - az + 540f) % 360f) - 180f
            val guide = when {
                abs(diff) <= 20f -> "지금 정면 쪽입니다. 그대로 앞으로 이동하세요."
                diff > 0 -> "오른쪽으로 약 ${diff.roundToInt()}° 회전하세요."
                else -> "왼쪽으로 약 ${abs(diff).roundToInt()}° 회전하세요."
            }
            tvTrackDirection.text = "신호 방향 (${dir.coveredSectors}/12방향 · 정확도 ${dir.confidence}): $guide"
        } else {
            tvTrackDirection.text = "방향 측정 중 (${dir.coveredSectors}/12방향): 휴대폰을 들고 제자리에서 천천히 360° 돌아보세요."
        }

        graphView.samples = tracker.recentSamples()
        graphView.invalidate()
    }

    private fun buildListScreen(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F4F8"))
        }

        // Top App Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A192F"))
            setPadding(36, 36, 36, 28)
        }
        val title = TextView(this).apply {
            text = "🛡️ 안티 커닝 · 부정행위 전파 탐지기"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvStatusBanner = TextView(this).apply {
            text = "'탐색 시작'을 눌러 주변 블루투스·Wi-Fi 의심 신호를 찾아보세요."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 13f
            setPadding(0, 8, 0, 0)
        }
        header.addView(title)
        header.addView(tvStatusBanner)
        layout.addView(header)

        // Control Buttons Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 20, 24, 8)
        }
        btnToggleScan = Button(this).apply {
            text = "탐색 시작"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setOnClickListener { toggleScanning() }
        }
        btnResetBaseline = Button(this).apply {
            text = "기준 재설정"
            setOnClickListener {
                store.markCurrentAsBaseline()
                Toast.makeText(this@MainActivity, "현재 감지된 신호를 기존 정상 신호로 처리했습니다.", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }
        btnClearList = Button(this).apply {
            text = "비우기"
            setOnClickListener {
                store.clear()
                refreshList()
            }
        }
        val lpWeight = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(6, 0, 6, 0)
        }
        btnRow.addView(btnToggleScan, lpWeight)
        btnRow.addView(btnResetBaseline, lpWeight)
        btnRow.addView(btnClearList, lpWeight)
        layout.addView(btnRow)

        // Filter Bar
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 4, 28, 8)
        }
        cbOnlySuspicious = CheckBox(this).apply {
            text = "🚨 의심 신호만"
            setTextColor(Color.parseColor("#B71C1C"))
            typeface = Typeface.DEFAULT_BOLD
            setOnCheckedChangeListener { _, checked ->
                onlySuspicious = checked
                refreshList()
            }
        }
        cbOnlyNew = CheckBox(this).apply {
            text = "새 신호만"
            setPadding(24, 0, 0, 0)
            setOnCheckedChangeListener { _, checked ->
                onlyNewSignals = checked
                refreshList()
            }
        }
        filterRow.addView(cbOnlySuspicious)
        filterRow.addView(cbOnlyNew)
        layout.addView(filterRow)

        tvSummaryCounts = TextView(this).apply {
            text = "전체 0개 · 🚨의심 0개 · 신규 0개"
            setTextColor(Color.parseColor("#37474F"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(32, 0, 32, 12)
        }
        layout.addView(tvSummaryCounts)

        listView = ListView(this).apply {
            divider = null
            dividerHeight = 14
            setPadding(24, 4, 24, 24)
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ ->
                displayedSignals.getOrNull(position)?.let { openTrackScreen(it) }
            }
        }
        adapter = SignalAdapter()
        listView.adapter = adapter
        layout.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        return layout
    }

    private fun buildTrackScreen(): LinearLayout {
        val scrollWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0A192F"))
            setPadding(24, 24, 24, 24)
        }
        val btnBack = Button(this).apply {
            text = "← 목록으로"
            setOnClickListener { closeTrackScreen() }
        }
        tvTrackTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 0, 0, 0)
        }
        topBar.addView(btnBack)
        topBar.addView(tvTrackTitle)
        scrollWrapper.addView(topBar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        tvTrackBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 10, 20, 10)
        }
        body.addView(tvTrackBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        tvTrackReason = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#B71C1C"))
            setPadding(0, 10, 0, 14)
        }
        tvTrackRssi = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#212121"))
        }
        tvTrackDistance = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, 6, 0, 6)
        }
        tvTrackTrend = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#2E7D32"))
            setPadding(0, 0, 0, 12)
        }
        tvTrackDirection = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 4, 0, 12)
        }

        body.addView(tvTrackReason)
        body.addView(tvTrackRssi)
        body.addView(tvTrackDistance)
        body.addView(tvTrackTrend)
        body.addView(tvTrackDirection)

        compassView = CompassView(this)
        body.addView(compassView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 460
        ))

        val btnResetDir = Button(this).apply {
            text = "방향 데이터 초기화"
            setOnClickListener {
                activeTracker?.resetDirection()
                compassView.sectorValues = FloatArray(12) { Float.NaN }
                compassView.targetBearingDeg = null
                compassView.invalidate()
            }
        }
        body.addView(btnResetDir)

        graphView = GraphView(this)
        body.addView(graphView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 240
        ).apply { topMargin = 16 })

        scrollWrapper.addView(body)
        return scrollWrapper
    }

    private inner class SignalAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedSignals.size
        override fun getItem(position: Int): Signal = displayedSignals[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val sig = getItem(position)
            val analysis = sig.analysis
            val isNew = store.isNewSignal(sig.id)

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 22, 28, 22)
                background = GradientDrawable().apply {
                    cornerRadius = 20f
                    if (analysis.isSuspicious) {
                        setColor(Color.parseColor("#FFEBEE"))
                        setStroke(4, Color.parseColor("#D32F2F"))
                    } else {
                        setColor(Color.WHITE)
                        setStroke(2, Color.parseColor("#CFD8DC"))
                    }
                }
            }

            // Top row: Badge + Signal Type + RSSI
            val topRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val badge = TextView(this@MainActivity).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(16, 6, 16, 6)
                background = GradientDrawable().apply {
                    cornerRadius = 12f
                    setColor(
                        if (analysis.isSuspicious) Color.parseColor("#C62828")
                        else Color.parseColor("#546E7A")
                    )
                }
                setTextColor(Color.WHITE)
                text = if (analysis.isSuspicious) {
                    "🚨 의심 · ${analysis.category.label}"
                } else {
                    "${sig.type.label} · ${analysis.category.label}"
                }
            }
            topRow.addView(badge)

            if (isNew) {
                val newBadge = TextView(this@MainActivity).apply {
                    text = "NEW"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding(12, 4, 12, 4)
                    background = GradientDrawable().apply {
                        cornerRadius = 10f
                        setColor(Color.parseColor("#EF6C00"))
                    }
                }
                val lpNew = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 12 }
                topRow.addView(newBadge, lpNew)
            }

            val spacer = View(this@MainActivity)
            topRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

            val rssiView = TextView(this@MainActivity).apply {
                text = "${sig.rssi} dBm"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    if (analysis.isSuspicious) Color.parseColor("#B71C1C")
                    else Color.parseColor("#1565C0")
                )
            }
            topRow.addView(rssiView)
            card.addView(topRow)

            // Device Name
            val nameView = TextView(this@MainActivity).apply {
                text = "[${sig.type.label}] ${sig.name}"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1A202C"))
                setPadding(0, 10, 0, 4)
            }
            card.addView(nameView)

            // Reason & Band Details
            val detailView = TextView(this@MainActivity).apply {
                textSize = 12.5f
                if (analysis.isSuspicious) {
                    text = "⚠ ${analysis.suspicionReason} · ${analysis.bandInfo}"
                    setTextColor(Color.parseColor("#B71C1C"))
                } else {
                    text = "${analysis.bandInfo} · ${sig.address}"
                    setTextColor(Color.parseColor("#607D8B"))
                }
            }
            card.addView(detailView)

            return card
        }
    }
}
