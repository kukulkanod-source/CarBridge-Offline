package com.example.carbridge.link

import com.example.carbridge.link.proto.PbReader

/** Decodes CarLife's single- and two-pointer touch payloads. */
object CarLifeTouch {
    fun parse(type: Int, body: ByteArray): HuLink.TouchEvent? {
        val fields = PbReader.parse(body) ?: return null
        val transparent = type == Msg.TOUCH_ACTION
        val rawAction = if (transparent) PbReader.int(fields, 1) ?: return null else when (type) {
            Msg.TOUCH_ACTION_DOWN, Msg.TOUCH_ACTION_BEGIN -> 0
            Msg.TOUCH_ACTION_UP -> 1
            Msg.TOUCH_ACTION_MOVE -> 2
            Msg.TOUCH_ACTION_POINTER_DOWN -> 5 or (1 shl 8)
            Msg.TOUCH_ACTION_POINTER_UP -> 6
            Msg.TOUCH_ACTION_OTHERPOINTER_UP -> 6 or (1 shl 8)
            else -> return null
        }
        val masked = rawAction and 0xff
        if (masked !in setOf(0, 1, 2, 3, 5, 6)) return null
        val actionIndex = (rawAction ushr 8) and 0xff
        val offset = if (transparent) 1 else 0
        val x = PbReader.int(fields, 1 + offset)
        val y = PbReader.int(fields, 2 + offset)
        if (masked == 3) {
            return HuLink.TouchEvent(masked, actionIndex, -1, -1)
        }
        if (x == null || y == null || x < 0 || y < 0) return null
        val pointerX = PbReader.int(fields, 3 + offset)?.takeIf { it >= 0 }
        val pointerY = PbReader.int(fields, 4 + offset)?.takeIf { it >= 0 }
        return HuLink.TouchEvent(masked, actionIndex, x, y, pointerX, pointerY)
    }
}
