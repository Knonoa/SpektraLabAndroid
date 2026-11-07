package com.spektralab.android

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.engine.FilmStock
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Film Edge (RFC-032) and the Date Back (RFC-031), as SpektraLab's desktop app
 * keeps them (modern_UI/.../Model/FilmEdge.swift, Session+FilmEdge.swift).
 * Both are exposure on the negative: the engine draws them (overscan.cpp);
 * this side only resolves what the engine is told -- the stock's edge print,
 * the date and the shooting data from EXIF, the seeds.
 */

/** The film and its gate. [wire] is the engine's `overscan_format`. */
enum class FilmEdgeFormat(val wire: String, val label: String, val is120: Boolean) {
    F135("135", "135", false),
    F135_HALF("135_half", "半格", false),
    XPAN("135_xpan", "XPan", false),
    F645("120_645", "645", true),
    F66("120_6x6", "6×6", true),
    F67("120_6x7", "6×7", true),
    F68("120_6x8", "6×8", true),
    F69("120_6x9", "6×9", true),
    F612("120_6x12", "6×12", true),
    F617("120_6x17", "6×17", true);

    /** Nothing on the panoramic formats (the desktop's answer C7). */
    val hasDateBack: Boolean get() = this != XPAN && this != F612 && this != F617
    val perforated: Boolean get() = !is120

    companion object {
        /** The Film Edge format a Camera › Film Format preset means. */
        fun of(format: FilmFormat): FilmEdgeFormat = when (format) {
            FilmFormat.HALF -> F135_HALF
            FilmFormat.F135 -> F135
            FilmFormat.F645 -> F645
            FilmFormat.F66 -> F66
            FilmFormat.F67 -> F67
            FilmFormat.F69, FilmFormat.F45 -> F69
        }
    }
}

enum class FilmEdgeView(val wire: String, val label: String) { STRIP("strip", "整条"), FILED("filed", "窄边") }

enum class FilmEdgeGate(val wire: String, val label: String) {
    AUTO("auto", "自动"), SQUARE("square", "方角"), ROUNDED("rounded", "圆角"),
    EARED("eared", "耳形"), SHOULDERED("shouldered", "肩形"), KICKED("kicked", "折角"),
}

enum class FilmEdgeHoles(val wire: String, val label: String) { WHITE("white", "透光"), BLACK("black", "黑底") }

data class FilmEdgeSettings(
    val active: Boolean = false,
    val format: FilmEdgeFormat = FilmEdgeFormat.F135,
    val view: FilmEdgeView = FilmEdgeView.STRIP,
    val gate: FilmEdgeGate = FilmEdgeGate.AUTO,
    val holes: FilmEdgeHoles = FilmEdgeHoles.WHITE,
    /** The body: per user, not per photo -- the same camera shot the roll. */
    val cameraSeed: Int = 0,
    /** The frame's number on the edge print, 1..99; 0 = the frame seed's draw. */
    val frameNumber: Int = 0,
    val fog: Float = 1f,
    val leaks: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("active", active).put("format", format.name).put("view", view.name).put("gate", gate.name)
        .put("holes", holes.name).put("cameraSeed", cameraSeed).put("frameNumber", frameNumber)
        .put("fog", fog.toDouble()).put("leaks", leaks.toDouble())

    companion object {
        val FOG_RANGE = 0f..4f
        val LEAKS_RANGE = 0f..4f

        fun fromJson(o: JSONObject?): FilmEdgeSettings {
            val d = FilmEdgeSettings()
            if (o == null) return d
            return FilmEdgeSettings(
                active = o.optBoolean("active", d.active),
                format = enumOr(o.optString("format"), d.format),
                view = enumOr(o.optString("view"), d.view),
                gate = enumOr(o.optString("gate"), d.gate),
                holes = enumOr(o.optString("holes"), d.holes),
                cameraSeed = o.optInt("cameraSeed", d.cameraSeed),
                frameNumber = o.optInt("frameNumber", d.frameNumber).coerceIn(0, 99),
                fog = o.optDouble("fog", d.fog.toDouble()).toFloat().coerceIn(FOG_RANGE),
                leaks = o.optDouble("leaks", d.leaks.toDouble()).toFloat().coerceIn(LEAKS_RANGE),
            )
        }
    }
}

/** The date back's three faces of one mechanism (`date_imprint_style`). */
enum class DateBackFace(val wire: String, val label: String) { LCD("lcd", "液晶"), DOTS("dots", "点阵"), DATA("data", "仅数据") }

/** `between` is the wire's `rebate`: between frames on 135, which needs the film edge. */
enum class DateBackPlacement(val wire: String, val label: String) { FRAME("frame", "画面内"), BETWEEN("rebate", "帧间") }

enum class DateBackCorner(val wire: String, val label: String) {
    BR("br", "右下"), BL("bl", "左下"), TR("tr", "右上"), TL("tl", "左上"),
}

/** The order the date is written in (US and Japan first, as the desktop). */
enum class DateBackOrder(val label: String) { JAPAN("年 月 日"), US("月 日 年") }

data class DateBackSettings(
    val active: Boolean = false,
    val face: DateBackFace = DateBackFace.LCD,
    val order: DateBackOrder = DateBackOrder.JAPAN,
    val placement: DateBackPlacement = DateBackPlacement.FRAME,
    val corner: DateBackCorner = DateBackCorner.BR,
    val size: Float = 1f,
    val brightnessEv: Float = 3.5f,
    /** Print the shooting data as well as the date. */
    val withData: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("active", active).put("face", face.name).put("order", order.name).put("placement", placement.name)
        .put("corner", corner.name).put("size", size.toDouble()).put("brightnessEv", brightnessEv.toDouble())
        .put("withData", withData)

    companion object {
        val SIZE_RANGE = 0.4f..3f
        val BRIGHTNESS_RANGE = -2f..8f

        /** The date as a date back writes it: no leading zeros, a two-digit year behind an apostrophe. */
        fun format(year: Int, month: Int, day: Int, order: DateBackOrder): String {
            val yy = "'" + String.format(Locale.US, "%02d", ((year % 100) + 100) % 100)
            return when (order) {
                DateBackOrder.US -> "$month $day $yy"
                DateBackOrder.JAPAN -> "$yy $month $day"
            }
        }

        fun fromJson(o: JSONObject?): DateBackSettings {
            val d = DateBackSettings()
            if (o == null) return d
            return DateBackSettings(
                active = o.optBoolean("active", d.active),
                face = enumOr(o.optString("face"), d.face),
                order = enumOr(o.optString("order"), d.order),
                placement = enumOr(o.optString("placement"), d.placement),
                corner = enumOr(o.optString("corner"), d.corner),
                size = o.optDouble("size", d.size.toDouble()).toFloat().coerceIn(SIZE_RANGE),
                brightnessEv = o.optDouble("brightnessEv", d.brightnessEv.toDouble()).toFloat().coerceIn(BRIGHTNESS_RANGE),
                withData = o.optBoolean("withData", d.withData),
            )
        }
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
    runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(def)

/**
 * The handful of EXIF values the imprint may print (the desktop's
 * ShootingData, RFC-033): a field the file does not carry is left out, never
 * invented.
 */
data class ShootingData(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val exposureTime: Double? = null,
    val fNumber: Double? = null,
    /** ExposureProgram: 1 manual, 2 program, 3 aperture priority, 4 shutter priority. */
    val program: Int? = null,
    /** ExposureBiasValue: the camera's own compensation, never the Film Exposure slider. */
    val bias: Double? = null,
    /** FocalLengthIn35mmFilm: the 135 equivalent, which is what a 135 data back would print. */
    val focal35: Double? = null,
) {
    val hasDate: Boolean get() = year != null && month != null && day != null

    /** The date as the lcd and dots faces print it, or "" with no date. */
    fun dateText(order: DateBackOrder): String =
        if (hasDate) DateBackSettings.format(year!!, month!!, day!!, order) else ""

    /**
     * The data face's line, after the F6: shutter, aperture, mode,
     * compensation, focal length. No ISO and no name line (the name is on the
     * film's edge). Fields drop from the right until the line fits.
     */
    val dataText: String
        get() {
            val fields = mutableListOf<String>()
            exposureTime?.let { fields += shutter(it) }
            fNumber?.let { fields += "F" + trimmed(it) }
            program?.let { mode(it) }?.let { fields += it }
            bias?.let { compensation(it) }?.let { fields += it }
            focal35?.let { fields += "${it.roundToInt()}mm" }
            while (fields.joinToString(" ").length > DATA_LINE_MAX) fields.removeAt(fields.lastIndex)
            return fields.joinToString(" ")
        }

    companion object {
        /** The longest line Nikon's between-frames imprint holds: `1/250 F5.6 A +0.3 24mm`. */
        const val DATA_LINE_MAX = 22

        fun shutter(t: Double): String = if (t >= 1) trimmed(t) + "s" else "1/${(1 / t).roundToInt()}"

        fun mode(program: Int): String? = when (program) {
            1 -> "M"; 2 -> "P"; 3 -> "A"; 4 -> "S"; else -> null
        }

        /** `+0.3`, `-1.0`; null at zero. */
        fun compensation(ev: Double): String? {
            val r = Math.round(ev * 10) / 10.0
            if (r == 0.0) return null
            return String.format(Locale.US, "%+.1f", r)
        }

        /** One decimal, without a trailing `.0`: `1.8`, `2`, `11`. */
        fun trimmed(v: Double): String {
            val s = String.format(Locale.US, "%.1f", v)
            return if (s.endsWith(".0")) s.dropLast(2) else s
        }

        private fun positive(v: Double): Double? = v.takeIf { it.isFinite() && it > 0 }

        fun read(context: Context, uri: Uri): ShootingData = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val e = ExifInterface(s)
                val dt = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: e.getAttribute(ExifInterface.TAG_DATETIME)
                val parts = dt?.take(10)?.split(":")?.mapNotNull { it.toIntOrNull() }
                val ok = parts != null && parts.size == 3 && parts[1] in 1..12 && parts[2] in 1..31 && parts[0] > 1800
                ShootingData(
                    year = if (ok) parts!![0] else null,
                    month = if (ok) parts!![1] else null,
                    day = if (ok) parts!![2] else null,
                    exposureTime = positive(e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)),
                    fNumber = positive(e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)),
                    program = e.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, -1).takeIf { it > 0 },
                    bias = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN).takeIf { it.isFinite() },
                    focal35 = positive(e.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1).toDouble()),
                )
            }
        }.getOrNull() ?: ShootingData()
    }
}

/**
 * The stock's own edge print, as the desktop app resolves it
 * (Session+FilmEdge.swift `edgeText`): what the film carries where a strip
 * was measured, else the stock's name.
 */
object EdgeText {
    private val own = mapOf( // (135, 120)
        "kodak_gold_200" to ("KODAK GB 200-7" to "KODAK 200"),
        "kodak_portra_800" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
        "kodak_portra_800_push1" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
        "kodak_portra_800_push2" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
        "kodak_ultramax_400" to ("KODAK GC 400" to "KODAK GC 400"),
        "kodak_ektachrome_100" to ("KODAK E100" to "KODAK E100"),
        "fujifilm_c200" to ("FUJI 200" to "FUJI 200"),
        "fujifilm_xtra_400" to ("S-400" to "FUJI S-400"),
        "fujifilm_pro_400h" to ("PRO400H" to "FUJI PRO400H"),
        "fujifilm_provia_100f" to ("FUJI RDPIII" to "FUJI RDPIII"),
        "fujifilm_velvia_100" to ("RVP100" to "FUJI RVP100"),
        "kodak_vision3_50d" to ("EASTMAN 5203" to "EASTMAN 5203"),
        "kodak_vision3_250d" to ("EASTMAN 5207" to "EASTMAN 5207"),
        "kodak_vision3_200t" to ("EASTMAN 5213" to "EASTMAN 5213"),
        "kodak_vision3_500t" to ("EASTMAN 5219" to "EASTMAN 5219"),
        "cinestill_800t" to ("CINESTILL 800T" to "CINESTILL 800T"),
        "kodak_tri_x_400" to ("KODAK 400TX" to "KODAK 400TX"),
        "kodak_tmax_100" to ("KODAK 100TMX" to "KODAK 100TMX"),
        "fujifilm_neopan_acros_100_ii" to ("FUJI 100 ACROS II" to "FUJI 100 ACROS II"),
        "ilford_hp5_plus_400" to ("ILFORD HP5 PLUS" to "ILFORD HP5 PLUS"),
    )

    fun of(stock: String, film: FilmStock?, format: FilmEdgeFormat): String {
        own[stock]?.let { return if (format.is120) it.second else it.first }
        var name = film?.name ?: stock.replace('_', ' ')
        val open = name.indexOf('(')
        if (open >= 0) name = name.substring(0, open)
        return name.trim().uppercase(Locale.US)
    }
}
