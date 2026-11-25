package com.example.signaldetector

import android.bluetooth.le.ScanRecord
import android.net.wifi.ScanResult

object PacketAnalyzer {

    private const val APPLE_COMPANY_ID = 0x004C
    private const val SAMSUNG_COMPANY_ID = 0x0075

    private val SUSPICIOUS_SERVICE_UUIDS = setOf(
        "0000110b", // Audio Sink
        "0000111e", // Handsfree
        "00001812", // HID (소형 리모컨/입력기)
        "0000fd6f", // Exposure / Beacon
        "0000fe2c", // Google Fast Pair (무선 이어폰)
        "0000fd5a"  // Samsung Buds / SmartTag
    )

    fun analyzeBle(
        deviceName: String?,
        address: String,
        scanRecord: ScanRecord?,
        rssi: Int
    ): AnalysisResult {
        val tags = mutableListOf<String>()
        val localName = scanRecord?.deviceName ?: deviceName
        val hasLocalName = !localName.isNullOrBlank()

        val mfgData = scanRecord?.manufacturerSpecificData
        val appleBytes = mfgData?.get(APPLE_COMPANY_ID)
        val samsungBytes = mfgData?.get(SAMSUNG_COMPANY_ID)
        val serviceUuids = scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty()

        var category = DeviceCategory.OTHER
        val reasons = mutableListOf<String>()

        if (appleBytes != null && appleBytes.isNotEmpty()) {
            val packetType = appleBytes[0].toInt() and 0xFF
            when (packetType) {
                0x07 -> {
                    category = DeviceCategory.EARBUDS
                    tags.add("Apple 이어폰(AirPods/Beats)")
                    reasons.add("Apple 오디오 페어링 패킷 감지")
                }
                0x0C, 0x0E -> {
                    if (appleBytes.size >= 3 && (appleBytes[1].toInt() and 0x0F) == 0x0A) {
                        category = DeviceCategory.SMARTWATCH
                        tags.add("Apple Watch")
                        reasons.add("스마트워치 근접 신호")
                    } else {
                        category = DeviceCategory.SMARTPHONE
                        tags.add("iPhone/iPad")
                    }
                }
                0x05 -> {
                    category = DeviceCategory.SMARTPHONE
                    tags.add("Apple AirDrop 활성")
                    reasons.add("파일 전송(AirDrop) 브로드캐스트")
                }
                0x09 -> {
                    category = DeviceCategory.TABLET_PC
                    tags.add("Apple AirPlay 기기")
                }
                0x10 -> {
                    val actionCode = if (appleBytes.size > 2) appleBytes[2].toInt() and 0xFF else 0
                    if (actionCode in listOf(0x0B, 0x0D, 0x13)) {
                        category = DeviceCategory.SMARTWATCH
                        tags.add("Apple Watch")
                        reasons.add("웨어러블 상태 패킷")
                    } else {
                        category = DeviceCategory.SMARTPHONE
                        tags.add("Apple 모바일 기기")
                    }
                }
                0x12 -> {
                    category = DeviceCategory.UNKNOWN_SMALL
                    tags.add("소형 비콘/AirTag")
                    reasons.add("초소형 추적/비콘 패킷")
                }
                else -> {
                    tags.add("Apple(0x${packetType.toString(16)})")
                }
            }
        } else if (samsungBytes != null && samsungBytes.isNotEmpty()) {
            val typeByte = samsungBytes[0].toInt() and 0xFF
            if (typeByte == 0x01 || serviceUuids.any { it.startsWith("0000fd5a") }) {
                category = DeviceCategory.EARBUDS
                tags.add("Galaxy Buds/웨어러블")
                reasons.add("삼성 오디오/웨어러블 패킷")
            } else {
                category = DeviceCategory.SMARTPHONE
                tags.add("Samsung Galaxy")
            }
        }

        if (category == DeviceCategory.OTHER && serviceUuids.isNotEmpty()) {
            if (serviceUuids.any { u -> SUSPICIOUS_SERVICE_UUIDS.any { u.startsWith(it) } }) {
                category = DeviceCategory.EARBUDS
                tags.add("오디오/HID 프로파일")
                reasons.add("무선 오디오·입력장치 UUID 감지")
            }
        }

        if (!hasLocalName) {
            tags.add("이름 없음(No Local Name)")
            if (category == DeviceCategory.OTHER) {
                category = DeviceCategory.UNKNOWN_SMALL
            }
            if (rssi > -68) {
                reasons.add("근거리 미확인 무명 BLE 브로드캐스트")
            }
        } else {
            val lower = localName!!.lowercase()
            when {
                listOf("airpods", "buds", "pod", "ear", "headset", "bt-", "mini", "spy").any { it in lower } -> {
                    category = DeviceCategory.EARBUDS
                    reasons.add("이어폰/소형기기 명칭 일치")
                }
                listOf("watch", "band", "fit", "garmin", "mi band").any { it in lower } -> {
                    category = DeviceCategory.SMARTWATCH
                    reasons.add("스마트워치 명칭 일치")
                }
                listOf("ipad", "tab", "macbook", "book").any { it in lower } -> {
                    category = DeviceCategory.TABLET_PC
                }
                localName.matches(Regex("^[A-Fa-f0-9:-]{6,}$")) || localName.length <= 3 -> {
                    category = DeviceCategory.UNKNOWN_SMALL
                    tags.add("비정상 식별자 형식")
                    reasons.add("고유 식별자 없는 축약/HEX 이름")
                }
            }
        }

        if (rssi >= -55) {
            tags.add("초근접(${rssi}dBm)")
            reasons.add("신호 매우 강함(근접 소지 의심)")
        }

        val isSuspicious = category.isSuspiciousTarget || reasons.isNotEmpty()
        val finalName = if (hasLocalName) localName!! else "미확인 BLE [${category.label}]"

        return AnalysisResult(
            displayName = finalName,
            category = category,
            isSuspicious = isSuspicious,
            suspicionReason = if (reasons.isEmpty()) "일반 주변 신호" else reasons.joinToString(" · "),
            tags = tags,
            bandInfo = "2.4GHz BLE"
        )
    }

    fun analyzeWifi(result: ScanResult): AnalysisResult {
        @Suppress("DEPRECATION")
        val ssid = result.SSID?.trim().orEmpty()
        val bssid = result.BSSID.orEmpty()
        val freq = result.frequency
        val rssi = result.level
        val tags = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        val ch = freqToChannel(freq)
        val bandInfo = when {
            freq in 2400..2500 -> "2.4GHz (Ch.$ch)"
            freq in 5150..5895 -> "5GHz (Ch.$ch)"
            freq >= 5925 -> "6GHz (Wi-Fi 6E)"
            else -> "${freq}MHz"
        }

        var category = DeviceCategory.ROUTER_AP

        if (ssid.isEmpty()) {
            category = DeviceCategory.HIDDEN_WIFI
            tags.add("Hidden SSID")
            reasons.add("이름이 숨겨진 비공개 네트워크")
        }

        val isLocallyAdministeredMac = isRandomOrHotspotMac(bssid)
        val hotspotKeywords = listOf("iphone", "galaxy", "android", "hotspot", "갤럭시", "아이폰", "휴대용", "direct-")
        val matchesHotspotName = hotspotKeywords.any { ssid.lowercase().contains(it) }

        if (matchesHotspotName || (isLocallyAdministeredMac && ssid.isNotEmpty())) {
            category = DeviceCategory.TETHERING_HOTSPOT
            tags.add("모바일 핫스팟 의심")
            reasons.add(if (matchesHotspotName) "스마트폰 테더링 SSID 패턴" else "사설/랜덤 MAC(테더링 특성)")
        }

        if (ch == 14 || ch in 100..144 || ch == 165 || freq >= 5925) {
            if (category == DeviceCategory.ROUTER_AP) {
                category = DeviceCategory.SPECIAL_CHANNEL_WIFI
            }
            tags.add("특수 채널 Ch.$ch")
            reasons.add("일반 공유기 외 특수/P2P 대역 채널")
        }

        if (rssi >= -52) {
            tags.add("초근접 AP")
            reasons.add("바로 인접한 발신원(${rssi}dBm)")
        }

        val isSuspicious = category.isSuspiciousTarget || reasons.isNotEmpty()
        val displayName = if (ssid.isEmpty()) "(숨겨진 네트워크 - Hidden SSID)" else ssid

        return AnalysisResult(
            displayName = displayName,
            category = category,
            isSuspicious = isSuspicious,
            suspicionReason = if (reasons.isEmpty()) "일반 고정 공유기" else reasons.joinToString(" · "),
            tags = tags,
            bandInfo = bandInfo
        )
    }

    fun analyzeClassicBt(deviceName: String?, address: String, rssi: Int): AnalysisResult {
        val hasName = !deviceName.isNullOrBlank()
        val tags = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        var category = DeviceCategory.OTHER

        if (!hasName) {
            category = DeviceCategory.UNKNOWN_SMALL
            tags.add("이름 없음")
            reasons.add("미확인 클래식 블루투스 기기")
        } else {
            val lower = deviceName!!.lowercase()
            if (listOf("airpods", "buds", "ear", "headset", "pod").any { it in lower }) {
                category = DeviceCategory.EARBUDS
                reasons.add("무선 오디오/이어폰 기기")
            } else if (listOf("watch", "band", "fit").any { it in lower }) {
                category = DeviceCategory.SMARTWATCH
                reasons.add("스마트워치 기기")
            }
        }

        if (rssi >= -55) {
            tags.add("초근접(${rssi}dBm)")
            reasons.add("근접 신호 감지")
        }

        val isSuspicious = category.isSuspiciousTarget || reasons.isNotEmpty()
        return AnalysisResult(
            displayName = if (hasName) deviceName!! else "미확인 BT 기기",
            category = category,
            isSuspicious = isSuspicious,
            suspicionReason = if (reasons.isEmpty()) "일반 클래식 BT" else reasons.joinToString(" · "),
            tags = tags,
            bandInfo = "2.4GHz Classic BT"
        )
    }

    private fun isRandomOrHotspotMac(bssid: String): Boolean {
        if (bssid.length < 2) return false
        val secondChar = bssid[1].uppercaseChar()
        return secondChar in setOf('2', '6', 'A', 'E')
    }

    private fun freqToChannel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5180..5885 -> (freq - 5000) / 5
        else -> 0
    }
}
