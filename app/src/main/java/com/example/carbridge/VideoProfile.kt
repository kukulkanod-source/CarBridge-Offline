package com.example.carbridge

/** Stable preference identifiers and encoder limits for a new HU session. */
enum class VideoProfile(
    val preferenceValue: String,
    val label: String,
    val maxFps: Int,
    val bitRate: Int,
) {
    LOW_HEAT("low_heat", "Низкая нагрузка — 6 кадров/с, 1 Мбит/с", 6, 1_000_000),
    ECONOMY("economy", "Экономичный — 15 кадров/с, 2 Мбит/с", 15, 2_000_000),
    BALANCED("balanced", "Сбалансированный — 20 кадров/с, 3 Мбит/с", 20, 3_000_000),
    SMOOTH("smooth", "Плавный — 30 кадров/с, 4 Мбит/с", 30, 4_000_000);

    val iFrameIntervalSeconds: Int get() = 2

    companion object {
        // A 15 fps baseline avoids the slideshow caused by the old 6 fps default.
        val DEFAULT: VideoProfile = ECONOMY

        fun fromPreference(value: String?): VideoProfile =
            values().firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}
