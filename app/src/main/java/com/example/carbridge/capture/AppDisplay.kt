package com.example.carbridge.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface

/** Состояние для UI: id текущего виртуального дисплея ГУ (-1, если его нет). */
object AppDisplayState {
    @Volatile var displayId: Int = -1
}

/**
 * Собственный виртуальный дисплей под ГУ. Окно Presentation рисуется на нём, физический экран
 * телефона не участвует (не нужен MediaProjection, телефон остаётся в портрете).
 *
 * Флаги пробуются от расширенных к базовым: разные прошивки по-разному относятся к
 * непубличным флагам, поэтому отказ системы не должен ронять сессию.
 */
class AppDisplay(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val dpi: Int
) {
    private val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun create(surface: Surface): VirtualDisplay? {
        val attempts = listOf("full" to FLAGS_FULL, "basic" to FLAGS_BASIC)
        for ((name, flags) in attempts) {
            try {
                val d = dm.createVirtualDisplay("CarBridge", width, height, dpi, surface, flags)
                if (d != null) {
                    AppDisplayState.displayId = d.display.displayId
                    Log.i(TAG, "виртуальный дисплей id=${d.display.displayId} ${width}x$height " +
                        "dpi=$dpi flags=$name (0x${flags.toString(16)})")
                    return d
                }
            } catch (e: Exception) {
                Log.w(TAG, "флаги '$name' отклонены: $e")
            }
        }
        Log.e(TAG, "не удалось создать виртуальный дисплей")
        return null
    }

    private companion object {
        const val TAG = "CarBridge/Display"
        // Приватный дисплей приложения: Presentation откроет TYPE_PRIVATE_PRESENTATION.
        // PUBLIC не даёт права запуска Activity и здесь не требуется.
        const val FLAGS_BASIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        // скрытые: SUPPORTS_TOUCH = 1 shl 6, DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAGS_FULL = FLAGS_BASIC or (1 shl 6) or (1 shl 8)
    }
}
