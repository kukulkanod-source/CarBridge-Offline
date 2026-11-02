package com.example.carbridge.scrcpy

internal object HuWindowSnapshot {
    fun foregroundPackage(snapshot: String, displayId: Int): String? {
        if (displayId < 0) return null
        val sections = Regex("(?m)^\\s*Display: mDisplayId=(\\d+)\\b.*$").findAll(snapshot).toList()
        val index = sections.indexOfFirst { it.groupValues[1].toIntOrNull() == displayId }
        if (index < 0) return null
        val section = snapshot.substring(sections[index].range.last + 1,
            sections.getOrNull(index + 1)?.range?.first ?: snapshot.length)
        return Regex("mFocusedApp=ActivityRecord\\{[^\\r\\n]*?\\s([A-Za-z0-9_.]+)/")
            .find(section)?.groupValues?.get(1)
    }
    const val COMMAND = "dumpsys window displays | " +
        "grep -E 'Display:|mDisplayId=|mCurrentFocus|mFocusedApp|mTopFullscreenOpaqueWindowState'"

    /** Unknown OEM dump formats must never be mistaken for an empty screen. */
    fun isEmpty(snapshot: String, displayId: Int): Boolean {
        if (displayId <= 0) return false
        val sections = Regex("(?m)^\\s*Display: mDisplayId=(\\d+)\\b.*$").findAll(snapshot).toList()
        val index = sections.indexOfFirst { it.groupValues[1].toIntOrNull() == displayId }
        if (index < 0) return false
        val section = snapshot.substring(sections[index].range.last + 1,
            sections.getOrNull(index + 1)?.range?.first ?: snapshot.length)
        return Regex("(?m)^\\s*mFocusedApp=null\\s*$").containsMatchIn(section)
    }
}
