package com.example.carbridge.link

import android.os.Build
import com.example.carbridge.link.proto.PbField
import com.example.carbridge.link.proto.PbReader
import com.example.carbridge.link.proto.PbWriter

object Msg {
    const val HU_PROTOCOL_VERSION = 98305
    const val PROTOCOL_VERSION_MATCH_STATUS = 65538
    const val HU_INFO = 98307
    const val MD_INFO = 65540
    const val VIDEO_ENCODER_INIT = 98311
    const val VIDEO_ENCODER_INIT_DONE = 65544
    const val VIDEO_ENCODER_START = 98313
    const val VIDEO_ENCODER_PAUSE = 98314
    const val VIDEO_ENCODER_RESET = 98315
    const val VIDEO_ENCODER_FRAME_RATE_CHANGE = 98316
    const val VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 65549
    const val SCREEN_ON = 65560
    const val FOREGROUND = 65563
    const val MODULE_STATUS = 65574
    const val STATISTIC_INFO = 98343
    const val CAR_DATA_SUBSCRIBE = 65585
    const val CARLIFE_DATA_SUBSCRIBE = 98371
    const val CARLIFE_DATA_SUBSCRIBE_DONE = 65604
    const val CARLIFE_DATA_SUBSCRIBE_START = 0x18045
    const val CARLIFE_DATA_SUBSCRIBE_STOP = 0x18046
    const val MIC_RECORD_END = 0x10023
    const val MIC_RECORD_RECOG_START = 0x10024
    const val VR_MIC_DATA = 0x58001
    const val HU_AUTHEN_REQUEST = 98376
    const val MD_AUTHEN_RESPONSE = 65609
    const val HU_AUTHEN_RESULT = 98378
    const val MD_AUTHEN_RESULT = 65611
    const val MD_FEATURE_CONFIG_REQUEST = 65617
    const val HU_FEATURE_CONFIG_RESPONSE = 98386
    const val ERROR_CODE = 98389
    const val CONNECT_EXCEPTION = 65591
    const val MD_EXIT = 65625
    const val MODULE_CONTROL = 98344
    const val NAVI_NEXT_TURN_INFO = 65584
    const val MEDIA_INFO = 65589
    const val MEDIA_PROGRESS_BAR = 65590
    const val MEDIA_INIT = 196609
    const val MEDIA_STOP = 196610
    const val MEDIA_PAUSE = 196611
    const val MEDIA_RESUME = 196612
    const val MEDIA_INIT_DONE = 196613
    const val MEDIA_DATA = 196614
    const val VIDEO_DATA = 131073
    const val VIDEO_HEARTBEAT = 131074
    const val TOUCH_ACTION = 425985
    const val TOUCH_ACTION_DOWN = 425986
    const val TOUCH_ACTION_UP = 425987
    const val TOUCH_ACTION_MOVE = 425988
    const val TOUCH_SINGLE_CLICK = 425989
    const val TOUCH_DOUBLE_CLICK = 425990
    const val TOUCH_LONG_PRESS = 425991
    const val TOUCH_CAR_HARD_KEY_CODE = 425992
    const val TOUCH_ACTION_BEGIN = 425994
    const val TOUCH_ACTION_POINTER_DOWN = 425995
    const val TOUCH_ACTION_POINTER_UP = 425996
    const val TOUCH_ACTION_OTHERPOINTER_UP = 425997
}

object Msgs {
    // headswap DeviceInfo.reportedSdk: старые ГУ получают Android 10 даже на API 33+.
    internal fun reportedSdk(realSdk: Int) = if (realSdk >= 33) 29 else realSdk
    val deviceSdk: Int get() = reportedSdk(Build.VERSION.SDK_INT)
    fun deviceAuthKey() = Build.BRAND + Build.MODEL + deviceSdk.toString()
    fun matchStatus(v: Int) = PbWriter().int32(1, v).toByteArray()
    fun videoEncoderInfo(w: Int, h: Int, fps: Int) =
        PbWriter().int32(1, w).int32(2, h).int32(3, fps).toByteArray()
    fun frameRate(fps: Int) = PbWriter().int32(1, fps).toByteArray()
    fun authenResponse(value: String) = PbWriter().string(1, value).toByteArray()
    fun authenResult(ok: Boolean) = PbWriter().bool(1, ok).toByteArray()

    fun moduleStatusList(protocolVersion: Int): ByteArray {
        val ids = if (protocolVersion >= 2) intArrayOf(1, 2, 3, 4, 6, 8, 9) else intArrayOf(1, 2, 3, 4, 6)
        val w = PbWriter().int32(1, ids.size)
        for (id in ids) w.bytes(2, PbWriter().int32(1, id).int32(2, 0).toByteArray())
        return w.toByteArray()
    }

    fun mediaInfo(info: HuLink.MediaInfo) = PbWriter().string(1, info.source.take(160))
        .string(2, info.title.take(512)).string(3, info.artist.take(512)).string(4, info.album.take(512))
        .bytes(5, info.artwork).int32(6, info.durationSeconds.coerceAtLeast(0)).int32(7, 0)
        .string(8, info.mediaId.take(512)).int32(9, 0).toByteArray()
    // Layout verified against the supplied Headswap 2.5.0 Msgs.naviNextTurn (icon variant).
    fun navigationInfo(info: HuLink.NavigationInfo): ByteArray {
        val w = PbWriter().int32(1, info.action).int32(2, info.nextTurn)
            .string(3, info.roadName.take(160)).int32(4, info.totalDistance.coerceAtLeast(0))
            .int32(5, info.remainingDistance.coerceAtLeast(0))
        if (info.turnImage.isNotEmpty()) w.bytes(6, info.turnImage)
        return w.toByteArray()
    }
    fun mediaProgress(seconds: Int) = PbWriter().int32(1, seconds.coerceAtLeast(0)).toByteArray()
    fun mediaAudioInit() = PbWriter().int32(1, 48000).int32(2, 2).int32(3, 16).toByteArray()
    fun musicStatus(active: Boolean) = PbWriter().int32(1, 1)
        .bytes(2, PbWriter().int32(1, 3).int32(2, if (active) 1 else 0).toByteArray()).toByteArray()
    fun moduleStatus(id: Int, active: Boolean) = PbWriter().int32(1, 1)
        .bytes(2, PbWriter().int32(1, id).int32(2, if (active) 1 else 0).toByteArray()).toByteArray()
    fun subscriptionIds(body: ByteArray): List<Int>? {
        val fields = PbReader.parse(body) ?: return null
        val entries = PbReader.all(fields, 2)
        if (entries.size > 64) return null
        return entries.map { entry ->
            val nested = entry.data?.let(PbReader::parse) ?: return null
            PbReader.int(nested, 1) ?: return null
        }.distinct()
    }

    /** Ответ на CARLIFE_DATA_SUBSCRIBE: для каждого id флаг «принято». */
    fun subscribeDone(req: ByteArray): ByteArray? {
        val f = PbReader.parse(req) ?: return null
        val items = PbReader.all(f, 2)
        val w = PbWriter().int32(1, items.size)
        for (it in items) {
            val inner: List<PbField> = it.data?.let { d -> PbReader.parse(d) } ?: return null
            val id = PbReader.int(inner, 1) ?: return null
            w.bytes(2, PbWriter().int32(1, id).bool(2, id == 0 || id == 2).toByteArray())
        }
        return w.toByteArray()
    }

    /** Информация об устройстве для MD_INFO. */
    fun deviceInfo(): ByteArray {
        val w = PbWriter()
        fun s(n: Int, v: String?) { if (!v.isNullOrEmpty()) w.string(n, v) }
        s(1, "Android"); s(2, Build.BOARD); s(3, Build.BOOTLOADER); s(4, Build.BRAND)
        s(5, Build.SUPPORTED_ABIS.getOrNull(0)); s(6, Build.SUPPORTED_ABIS.getOrNull(1))
        s(7, Build.DEVICE); s(8, Build.DISPLAY); s(9, Build.FINGERPRINT); s(10, Build.HARDWARE)
        s(11, Build.HOST); s(13, Build.MANUFACTURER); s(14, Build.MODEL); s(15, Build.PRODUCT)
        s(17, Build.VERSION.CODENAME); s(18, Build.VERSION.INCREMENTAL)
        s(19, Build.VERSION.RELEASE); s(20, deviceSdk.toString())
        w.int32(21, deviceSdk)
        s(24, MD_VERSION)
        w.int32(25, MD_TYPE)
        return w.toByteArray()
    }

    // Значения, которые ожидает ГУ в MD_INFO (взяты из рабочего приложения).
    const val MD_VERSION = "8.6.7"
    const val MD_TYPE = 1
}

