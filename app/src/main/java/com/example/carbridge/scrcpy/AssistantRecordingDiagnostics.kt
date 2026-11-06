package com.example.carbridge.scrcpy

/** Read-only, current state only. Unknown OEM formats remain unknown, never "microphone works". */
internal object AssistantRecordingDiagnostics {
    const val COMMAND = "dumpsys audio | sed -n '/^[[:space:]]*RecordActivityMonitor dump time:/,/^$/p' | head -n 120"

    fun summary(output: String, packageName: String): String {
        if (!output.contains("RecordActivityMonitor dump time:")) return "recorderState=unavailable"
        var active = "unknown"
        val matching = mutableListOf<String>()
        var parsed = 0
        for (line in output.lineSequence()) {
            Regex("active\\?\\s*(true|false)").find(line)?.let { active = it.groupValues[1] }
            if (!line.contains("session:") || !line.contains(" -- pack:")) continue
            parsed++
            val entryActive = active
            active = "unknown"
            val pkg = Regex("-- pack:([^\\s]+)").find(line)?.groupValues?.get(1)
            if (pkg != packageName) continue
            val session = Regex("session:(\\d+)").find(line)?.groupValues?.get(1) ?: "unknown"
            val source = Regex("-- source(?: client=|:)([A-Za-z0-9_]+)").find(line)?.groupValues?.get(1) ?: "unknown"
            val silenced = Regex("-- silenced:(true|false)").find(line)?.groupValues?.get(1) ?: "unknown"
            val patch = Regex("-- patch:(\\d+)").find(line)?.groupValues?.get(1) ?: "unknown"
            matching += "session=$session active=$entryActive source=$source silenced=$silenced patch=$patch"
            active = "unknown"
        }
        // An absent configuration is not proof of a denied permission or a broken microphone.
        return "recorderState=${if (matching.isEmpty()) "not_listed" else "listed"}; " +
            "parsedConfigs=$parsed; targetConfigs=${matching.take(4)}"
    }
}
