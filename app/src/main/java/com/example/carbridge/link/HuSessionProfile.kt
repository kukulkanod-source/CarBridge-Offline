package com.example.carbridge.link

import com.example.carbridge.link.proto.PbReader

/** Protocol identifiers only: no serial number, Bluetooth address or phone fingerprint. */
internal class HuSessionProfile {
    @Volatile var manufacturer = "unknown"
        private set
    @Volatile var model = "unknown"
        private set
    @Volatile var product = "unknown"
        private set
    @Volatile var channel = "unknown"
        private set
    @Volatile var voiceMic: Int? = null
        private set
    @Volatile var voiceWakeup: Int? = null
        private set
    private fun printable(value: String?) = value?.filter { !it.isISOControl() }?.take(96)?.ifBlank { "unknown" } ?: "unknown"
    fun readDevice(body: ByteArray) {
        val fields = PbReader.parse(body) ?: return
        manufacturer = printable(PbReader.str(fields, 13))
        model = printable(PbReader.str(fields, 14))
        product = printable(PbReader.str(fields, 15))
    }
    fun readStatistics(body: ByteArray) {
        val fields = PbReader.parse(body) ?: return
        channel = PbReader.str(fields, 4)?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,32}")) } ?: "unknown"
    }
    fun readFeatures(body: ByteArray): Map<String, Int> {
        val fields = PbReader.parse(body) ?: return emptyMap()
        val selected = LinkedHashMap<String, Int>()
        for (item in PbReader.all(fields, 2).take(64)) {
            val entry = item.data?.let(PbReader::parse) ?: continue
            val key = PbReader.str(entry, 1) ?: continue
            val value = PbReader.int(entry, 2) ?: continue
            if (key in setOf("VOICE_MIC", "VOICE_WAKEUP", "ALL_VOICE_WAKEUP_DISABLE", "MEDIA_SAMPLE_RATE", "AUDIO_TRANSMISSION_MODE")) selected[key] = value
        }
        voiceMic = selected["VOICE_MIC"] ?: voiceMic
        voiceWakeup = selected["VOICE_WAKEUP"] ?: voiceWakeup
        return selected
    }
    // Exact advertised channel IDs. An unknown HU keeps the existing conservative path.
    val pauseHeartbeat: Boolean get() = channel in setOf("20082120", "20082122", "20082114", "21290002")
    val idleHeartbeat: Boolean get() = channel in setOf("20082114", "21290002")
    val name: String get() = when {
        idleHeartbeat -> "cns-idle-heartbeat"
        pauseHeartbeat -> "cns-pause-heartbeat"
        else -> "standard"
    }
    fun summary() = "manufacturer=$manufacturer; model=$model; product=$product; channel=$channel; profile=$name; pauseMs=${if (pauseHeartbeat) 100 else 2000}; idleHeartbeat=$idleHeartbeat; voiceMic=${voiceMic ?: "unknown"}; voiceWakeup=${voiceWakeup ?: "unknown"}"
}
