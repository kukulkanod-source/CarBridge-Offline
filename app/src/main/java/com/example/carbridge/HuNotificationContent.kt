package com.example.carbridge

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Read published notification content, including custom RemoteViews. No phone-window scraping. */
internal object HuNotificationContent {
    data class Content(val title: String, val text: String, val icon: Drawable?,
        val roadSign: Drawable? = null, val speedLimitKmh: Int? = null, val navigationHints: String = "",
        val graphicRole: String = "unknown")
    private val cache=object: LinkedHashMap<String,Content>(64,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Content>?) = size>64
    }
    val navigationPackages=setOf("ru.yandex.yandexmaps","ru.yandex.yandexnavi","com.google.android.apps.maps","ru.dublgis.dgismobile")
    fun read(context: Context, item: StatusBarNotification): Content {
        val n=item.notification
        val key="${item.key}:${item.postTime}:${n.`when`}"
        cache[key]?.let { return it }
        val extras=n.extras
        var title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val parts=mutableListOf<String>()
        fun add(value: CharSequence?) { value?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) } }
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)); add(extras.getCharSequence(Notification.EXTRA_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val navigationHints = mutableListOf<String>()
        var roadSign: Drawable? = null
        var explicitLimit: Int? = null
        var icon=runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()
        var graphicRole="unknown"
        var maneuverRank=0
        if(HuMediaNotificationListener.isStrelka(item.packageName) || item.packageName in navigationPackages || n.category==Notification.CATEGORY_NAVIGATION) {
            val custom=n.bigContentView ?: n.contentView ?: n.headsUpContentView
            if(custom!=null) runCatching {
                val parent=FrameLayout(context)
                val root=custom.apply(context,parent)
                var visited=0
                fun collect(view: View) {
                    if(++visited>100 || view.visibility!=View.VISIBLE) return
                    val resourceName=if(view.id!=View.NO_ID) runCatching { view.resources.getResourceEntryName(view.id).lowercase() }.getOrDefault("") else ""
                    val description=view.contentDescription?.toString().orEmpty()
                    if (description.isNotBlank()) navigationHints.add(description.take(256))
                    if(view is TextView) {
                        add(view.text)
                        if(explicitLimit==null) explicitLimit=explicitSpeedLimit(view.text?.toString().orEmpty())
                    }
                    if(view is ImageView) {
                        val semantic="$resourceName $description"
                        val camera=Regex("(?iu)(camera|камер|speed.?trap|speed.?control)").containsMatchIn(semantic)
                        val maneuver=Regex("(?iu)(maneuv|manoeuv|turn|direction|поворот|ман[её]вр|налево|направо)").containsMatchIn(semantic)
                        val other=Regex("(?iu)(logo|avatar|background|speed.?limit|road.?sign|traffic.?sign)").containsMatchIn(semantic)
                        val next=Regex("(?iu)(next.?next|second|after|затем|следующ.*следующ)").containsMatchIn(semantic)
                        val rank=if(maneuver && !camera && !other) (if(next) 1 else 3) else if(camera) 2 else 0
                        if(view.drawable!=null && rank>maneuverRank) {
                            icon=view.drawable; graphicRole=if(camera) "camera" else "maneuver"; maneuverRank=rank
                        } else if(icon==null && !other) { icon=view.drawable }
                        val signResource=Regex("(?i)(speed_?limit|max_?speed|road_?sign|traffic_?sign)").containsMatchIn(resourceName)
                        val signDescription=Regex("(?iu)(ограничение скорости|дорожный знак|speed limit|road sign|traffic sign)").containsMatchIn(description)
                        if(roadSign==null && (signResource || signDescription)) roadSign=view.drawable
                    }
                    if(explicitLimit==null) explicitLimit=explicitSpeedLimit(description)
                    if(view is ViewGroup) for(i in 0 until view.childCount.coerceAtMost(40)) collect(view.getChildAt(i))
                }
                collect(root)
            }
        }
        val unique=parts.filter { it!=title }.distinct().take(12)
        if(title.isBlank() && unique.isNotEmpty()) title=unique.first()
        val text=unique.filter { it!=title }.joinToString("\n").take(3000)
        val limit=explicitLimit ?: explicitSpeedLimit(title+"\n"+text)
        val cameraText=Regex("(?iu)(камера|контроль скорости|speed camera|speed trap)").containsMatchIn(title+"\n"+text)
        if(cameraText && graphicRole!="maneuver") graphicRole="camera"
        val content=Content(title.take(512),text,icon,roadSign,limit,navigationHints.distinct().take(20).joinToString("\n"),graphicRole)
        cache[key]=content
        return content
    }
    // Require an explicit speed-limit label. Distances, current speed and ETA
    // must never become a regulatory sign. Values marked mph are not km/h.
    private val speedLimitPattern=Regex("(?iu)(?:ограничение\\s+скорости|разреш[её]нная\\s+скорость|speed\\s+limit)\\s*[:=—-]?\\s*([0-9]{1,3})(?![0-9])(?=\\s|[.,;!?)\\]]|$)")
    private fun explicitSpeedLimit(value: String): Int? {
        val match=speedLimitPattern.find(value) ?: return null
        val suffix=value.substring(match.range.last+1).trimStart()
        if(Regex("(?iu)^(?:[.,][0-9]+\\s*)?(mph|миль|мили)\\b").containsMatchIn(suffix)) return null
        if(match.value.startsWith("speed",ignoreCase=true) &&
            !Regex("(?iu)^(km\\s*/\\s*h|kmh|kph)\\b").containsMatchIn(suffix)) return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..300 }
    }
    fun isNavigation(item: StatusBarNotification, content: Content): Boolean =
        item.notification.category==Notification.CATEGORY_NAVIGATION ||
            (item.packageName in navigationPackages && item.isOngoing &&
                Regex("(?iu)([0-9]+[.,]?[0-9]*\\s*(км|м|km|m)\\b|поверн|поворот|съезд|разворот|turn|exit)")
                    .containsMatchIn(content.title+" "+content.text))
}
