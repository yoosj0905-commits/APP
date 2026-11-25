package com.example.signaldetector

enum class SignalType(val label: String) {
    BLE("BLE"),
    WIFI("Wi-Fi"),
    CLASSIC_BT("BT")
}

enum class DeviceCategory(val label: String, val isSuspiciousTarget: Boolean) {
    EARBUDS("무선 이어폰/오디오", true),
    SMARTWATCH("스마트워치/웨어러블", true),
    UNKNOWN_SMALL("미확인 소형 BLE 기기", true),
    TETHERING_HOTSPOT("스마트폰 핫스팟 의심", true),
    HIDDEN_WIFI("숨겨진 네트워크(Hidden)", true),
    SPECIAL_CHANNEL_WIFI("특수/비표준 대역 Wi-Fi", true),
    SMARTPHONE("스마트폰", false),
    TABLET_PC("태블릿/노트북", false),
    ROUTER_AP("일반 공유기(AP)", false),
    OTHER("기타 기기", false)
}

data class AnalysisResult(
    val displayName: String,
    val category: DeviceCategory,
    val isSuspicious: Boolean,
    val suspicionReason: String,
    val tags: List<String>,
    val bandInfo: String
)

data class Signal(
    val id: String,
    val type: SignalType,
    val name: String,
    val address: String,
    val rssi: Int,
    val extraInfo: String,
    val lastSeenMs: Long,
    val firstSeenMs: Long,
    val analysis: AnalysisResult
)

enum class Trend(val label: String) {
    APPROACHING("가까워지는 중"),
    RECEDING("멀어지는 중"),
    STABLE("변화 없음"),
    CALCULATING("추세 계산 중"),
    LOST("신호 끊김")
}

data class DirectionResult(
    val bestBearingDeg: Float?,
    val confidence: String,
    val coveredSectors: Int,
    val sectorMaxRssi: FloatArray
)
