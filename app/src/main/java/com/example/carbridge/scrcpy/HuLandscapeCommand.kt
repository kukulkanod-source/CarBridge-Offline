package com.example.carbridge.scrcpy

/** Rotation zero is landscape for our naturally wide virtual display. */
internal object HuLandscapeCommand {
    fun commands(displayId: Int): List<String> {
        require(displayId > 0) { "Ориентацию разрешено менять только на дисплее ГУ" }
        return listOf(
            "wm user-rotation -d $displayId lock 0",
            "wm fixed-to-user-rotation -d $displayId enabled",
            "wm set-ignore-orientation-request -d $displayId true",
        )
    }
}
