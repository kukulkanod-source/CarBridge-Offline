package com.example.carbridge

/** Presentation only: never feeds inferred information into CarLife guidance packets. */
internal data class HuNavigationSceneContent(val turn: Turn?, val distance: String?) {
    enum class Turn { LEFT, RIGHT, STRAIGHT, U_TURN, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT }

    companion object {
        private val complex = Regex("(?iu)(кругов|кольц|roundabout|развяз|затем|потом|then)")
        private val left = Regex("(?iu)(налево|влево|левее|turn\\s+left|bear\\s+left|keep\\s+left)")
        private val right = Regex("(?iu)(направо|вправо|правее|turn\\s+right|bear\\s+right|keep\\s+right)")
        private val back = Regex("(?iu)(развернитесь|разворот|u[ -]?turn)")
        private val straight = Regex("(?iu)^(?:через\\s+[0-9.,]+\\s*(?:км|м|km|m)\\s*[,—-]?\\s*)?(?:прямо|движение прямо|двигайтесь прямо|продолжайте прямо|езжайте прямо|continue straight|go straight)[.!]?$")
        private val slight = Regex("(?iu)(плавн|слегка|левее|правее|slight|bear|keep|съезд|выезд|ramp|exit)")
        private val sharp = Regex("(?iu)(резк|sharp)")
        private val distance = Regex("(?iu)(?<![\\p{L}\\d.,])([0-9]{1,5}(?:[.,][0-9]{1,2})?)\\s*(км|km|м|m)(?![\\p{L}/])")
        private val distanceOnly = Regex("(?iu)^(?:через\\s+)?[0-9]{1,5}(?:[.,][0-9]{1,2})?\\s*(?:км|km|м|m)[.!]?$")

        fun read(content: HuNotificationContent.Content): HuNavigationSceneContent {
            fun normalize(s: String) = s.replace('\u00a0', ' ').replace('\u202f', ' ').trim()
            val lines = (listOf(content.title) + content.text.lines() + content.navigationHints.lines())
                .map(::normalize).filter { it.isNotBlank() }.distinct()
            val combined = lines.joinToString("\n")
            val turns = lines.mapNotNull(::direction).distinct()
            val turn = if (content.graphicRole=="camera" || complex.containsMatchIn(combined) || turns.size != 1) null else turns.single()
            // Prefer an isolated title (e.g. '300 м'), then a distance in the
            // manoeuvre itself. Never turn remaining trip distance/ETA into a countdown.
            val heading = normalize(content.title)
            val explicit = lines.filter { direction(it) != null }.flatMap { distance.findAll(it).toList() }
                .distinctBy { it.value.lowercase().replace(" ","") }
            val label = when {
                explicit.size == 1 -> explicit.single()
                explicit.isEmpty() && distanceOnly.matches(heading) -> distance.find(heading)
                else -> null
            }?.let { match ->
                val unit = if (match.groupValues[2].lowercase() in setOf("км", "km")) "км" else "м"
                "${match.groupValues[1]} $unit"
            }
            return HuNavigationSceneContent(turn, label)
        }

        private fun direction(value: String): Turn? {
            if (back.containsMatchIn(value)) return if (right.containsMatchIn(value)) null else Turn.U_TURN
            val l = left.containsMatchIn(value)
            val r = right.containsMatchIn(value)
            if (l && r) return null
            if (l) return when {
                slight.containsMatchIn(value) -> Turn.SLIGHT_LEFT
                sharp.containsMatchIn(value) -> Turn.SHARP_LEFT
                else -> Turn.LEFT
            }
            if (r) return when {
                slight.containsMatchIn(value) -> Turn.SLIGHT_RIGHT
                sharp.containsMatchIn(value) -> Turn.SHARP_RIGHT
                else -> Turn.RIGHT
            }
            return if (straight.matches(value)) Turn.STRAIGHT else null
        }
    }
}
