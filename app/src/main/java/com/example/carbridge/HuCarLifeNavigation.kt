package com.example.carbridge

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import com.example.carbridge.link.HuLink
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Notification adapter. Unknown/ambiguous instructions never become guessed manoeuvres. */
internal class HuCarLifeNavigation(private val context: Context) {
    private var lastLink: HuLink? = null
    private var previous: Turn? = null
    private var total = 0
    private var sentAt = 0L
    private var missingAt = 0L
    private var diagnosticAt = -30000L
    private var diagnosticReason = ""
    private val icons = HashMap<Int, ByteArray>()
    private data class Turn(val code: Int, val meters: Int, val road: String, val source: String)
    private data class Parsed(val turn: Turn?, val reason: String)

    fun update(link: HuLink?, enabled: Boolean) {
        if (link !== lastLink) {
            clear(lastLink, "connection changed")
            previous = null; total = 0; sentAt = 0L; missingAt = 0L
            lastLink = link
        }
        if (link == null) return
        if (!enabled) { clear(link, "disabled"); return }
        val now = SystemClock.elapsedRealtime()
        val published = HuMediaNotificationListener.snapshot().filter { it.packageName in HuNotificationContent.navigationPackages }
        val candidates = HuMediaNotificationListener.navigationSnapshot().filter {
            it.isOngoing || it.notification.category == Notification.CATEGORY_NAVIGATION
        }
        if (candidates.isEmpty()) HuMediaNotificationListener.requestSnapshotRefresh()
        var parsed = Parsed(null, if (HuMediaNotificationListener.connected) "no navigation notification" else "listener disconnected")
        for (item in candidates) {
            val result = runCatching {
                val content = HuNotificationContent.read(context, item)
                if (HuNotificationContent.isNavigation(item, content)) parse(content, item.packageName)
                else Parsed(null, "not an active guidance notification")
            }.getOrElse { Parsed(null, "notification decode failed") }
            parsed = result
            if (result.turn != null) break
        }
        val turn = parsed.turn
        if (turn == null) {
            if (now - diagnosticAt >= 30000 || diagnosticReason != parsed.reason) {
                diagnosticAt = now; diagnosticReason = parsed.reason
                SessionDiagnostics.event("CarLife navigation waiting; reason=${parsed.reason}; candidates=${candidates.size}; published=${published.size}; summaries=${published.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }}")
            }
            if (previous != null) {
                if (missingAt == 0L) missingAt = now
                if (now - missingAt >= 2000) clear(link, parsed.reason)
            }
            return
        }
        missingAt = 0L
        val old = previous
        val newLeg = old == null || old.source != turn.source || old.code != turn.code || old.road != turn.road ||
            turn.meters > old.meters + 50
        if (turn == old && now - sentAt < 5000) return
        val nextTotal = if (newLeg) turn.meters else maxOf(total, turn.meters)
        val info = HuLink.NavigationInfo(if (newLeg) 1 else 2, turn.code, turn.road,
            nextTotal, turn.meters, icons.getOrPut(turn.code) { drawIcon(turn.code) })
        if (link.sendNavigation(info)) {
            previous = turn; total = nextTotal; sentAt = now
            SessionDiagnostics.event("CarLife navigation queued; action=${info.action}; turn=${turn.code}; meters=${turn.meters}; roadPresent=${turn.road.isNotBlank()}; iconBytes=${info.turnImage.size}; source=${turn.source}")
        }
    }

    private fun clear(link: HuLink?, reason: String) {
        if (previous != null && link != null) {
            if (!link.sendNavigation(HuLink.NavigationInfo(3, 24, "", 0, 0))) return
            SessionDiagnostics.event("CarLife navigation cleared; action=3; reason=$reason; queued=true")
        }
        previous = null; total = 0; sentAt = 0L; missingAt = 0L
    }

    private fun parse(content: HuNotificationContent.Content, source: String): Parsed {
        val lines = (listOf(content.title) + content.text.lines() + content.navigationHints.lines())
            .map { it.replace(' ', ' ').replace(' ', ' ').trim() }.filter { it.isNotBlank() }.distinct()
        val combined = lines.joinToString("\n").lowercase()
        // Complex intersections and roundabout exits require structured data we do not have.
        if (Regex("(?iu)(кругов|кольц|roundabout|съезд|выезд|развяз|ramp|exit|затем|потом|then)").containsMatchIn(combined))
            return Parsed(null, "complex instruction unsupported")
        val mentionsLeft = Regex("(?iu)(налево|влево|левее|turn\\s+left|bear\\s+left|keep\\s+left)").containsMatchIn(combined)
        val mentionsRight = Regex("(?iu)(направо|вправо|правее|turn\\s+right|bear\\s+right|keep\\s+right)").containsMatchIn(combined)
        if (mentionsLeft && mentionsRight) return Parsed(null, "ambiguous left/right instructions")
        val codes = lines.mapNotNull(::direction).distinct()
        if (codes.size != 1) return Parsed(null, if (codes.isEmpty()) "no explicit manoeuvre" else "ambiguous manoeuvres")
        val code = codes.single()
        val local = lines.filter { direction(it) == code }.flatMap(::distances).distinct()
        val meters = when {
            local.size == 1 -> local.single()
            local.size > 1 -> return Parsed(null, "ambiguous manoeuvre distance")
            else -> {
                val all = lines.flatMap(::distances).distinct()
                if (all.size != 1) return Parsed(null, "missing or ambiguous distance")
                all.single()
            }
        }
        // Only an explicit 'onto/on/на …' following the instruction is a road name.
        val instruction = lines.firstOrNull { direction(it) == code }.orEmpty()
        val road = Regex("(?iu)(?:налево|направо|left|right)\\s+(?:на|onto)\\s+(.+)")
            .find(instruction)?.groupValues?.get(1)?.substringBefore("через")?.trim()?.take(160).orEmpty()
            .takeUnless { distance.containsMatchIn(it) }.orEmpty()
        return Parsed(Turn(code, meters, road, source), "ready")
    }
    private fun direction(text: String): Int? {
        val value = text.lowercase()
        if (Regex("(?iu)(развернитесь|разворот|u.turn)").containsMatchIn(value)) {
            return if (Regex("(?iu)(направо|right)").containsMatchIn(value)) null else 5
        }
        val left = Regex("(?iu)(налево|влево|левее|turn\\s+left|bear\\s+left|keep\\s+left)").containsMatchIn(value)
        val right = Regex("(?iu)(направо|вправо|правее|turn\\s+right|bear\\s+right|keep\\s+right)").containsMatchIn(value)
        if (left && right) return null
        val slight = Regex("(?iu)(плавн|слегка|левее|правее|slight|bear|keep)").containsMatchIn(value)
        val sharp = Regex("(?iu)(резк|sharp)").containsMatchIn(value)
        if (left) return if (slight) 8 else if (sharp) 6 else 7
        if (right) return if (slight) 2 else if (sharp) 4 else 3
        if (value.trim() == "прямо") return 1
        if (Regex("(?iu)(движение прямо|двигайтесь прямо|продолжайте прямо|езжайте прямо|continue straight|go straight)").containsMatchIn(value)) return 1
        return null
    }
    private val distance = Regex("(?iu)(?<![\\p{L}\\d.,])((?:[0-9]{1,3}(?: [0-9]{3})+|[0-9]{1,5})(?:[.,][0-9]{1,3})?)\\s*(км|km|м|m)(?![\\p{L}/])")
    private fun distances(text: String): List<Int> {
        if (Regex("(?iu)(до финиша|осталось|remaining|destination|скорост|speed)").containsMatchIn(text)) return emptyList()
        return distance.findAll(text).mapNotNull { match ->
            val raw = match.groupValues[1].replace(" ", "")
            // 1,200 / 1.200 may be a decimal or a thousands separator; do not guess.
            if (Regex("[.,][0-9]{3}$").containsMatchIn(raw)) return@mapNotNull null
            if (text.substring(match.range.last + 1).trimStart().startsWith("/")) return@mapNotNull null
            val number = raw.replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val value = number * if (match.groupValues[2].lowercase() in setOf("км", "km")) 1000 else 1
            value.takeIf { it.isFinite() && it in 0.0..100000.0 }?.roundToInt()
        }.toList()
    }

    private fun drawIcon(code: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; strokeWidth = 11f; style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            }
            val path = Path()
            if (code == 5) {
                path.moveTo(94f, 112f); path.lineTo(94f, 48f)
                path.cubicTo(94f, 10f, 32f, 10f, 32f, 48f); path.lineTo(32f, 90f)
                path.moveTo(16f, 72f); path.lineTo(32f, 90f); path.lineTo(48f, 72f)
            } else if (code == 1) {
                path.moveTo(64f, 112f); path.lineTo(64f, 18f)
                path.moveTo(44f, 38f); path.lineTo(64f, 18f); path.lineTo(84f, 38f)
            } else {
                val right = code in setOf(2, 3, 4)
                if (right) canvas.scale(-1f, 1f, 64f, 64f)
                path.moveTo(72f, 112f)
                when (code) {
                    8, 2 -> {
                        path.lineTo(72f, 78f); path.lineTo(26f, 30f)
                        path.moveTo(26f, 56f); path.lineTo(26f, 30f); path.lineTo(52f, 30f)
                    }
                    6, 4 -> {
                        path.lineTo(72f, 34f); path.lineTo(26f, 80f)
                        path.moveTo(26f, 54f); path.lineTo(26f, 80f); path.lineTo(52f, 80f)
                    }
                    else -> {
                        path.lineTo(72f, 54f); path.lineTo(20f, 54f)
                        path.moveTo(40f, 34f); path.lineTo(20f, 54f); path.lineTo(40f, 74f)
                    }
                }
            }
            canvas.drawPath(path, paint)
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            return bytes.toByteArray().takeIf { it.size <= 8192 } ?: ByteArray(0)
        } finally { bitmap.recycle() }
    }
}
