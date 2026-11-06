package com.spektralab.android

import android.content.SharedPreferences
import com.spektralab.android.engine.NativeParams
import org.json.JSONObject
import kotlin.math.pow

/** Film format presets: the frame's long edge in millimetres (camera.film_format_mm). */
enum class FilmFormat(val label: String, val longEdgeMm: Float) {
    HALF("半格", 24f),
    F135("135", 36f),
    F645("645", 56f),
    F66("6×6", 56f),
    F67("6×7", 70f),
    F69("6×9", 84f),
    F45("4×5", 120f),
}

/**
 * Everything the user edits. One immutable value, so each change triggers one
 * coherent render. Units follow SpektraLab's controls:
 *  - printBrightness: enlarger stops, brighter positive (print_exposure = 2^-stops)
 *  - y/mFilter: enlarger head CC relative to the neutral filter pack, signed
 *    in the print's visual direction (+ = yellower / more magenta print)
 */
data class EditState(
    val filmStock: String = "kodak_portra_400",
    val printStock: String = "kodak_portra_endura",
    val scanFilm: Boolean = false,
    // camera
    val autoExposure: Boolean = true,
    val exposureEv: Float = 0f,
    val format: FilmFormat = FilmFormat.F135,
    // enlarger
    val printBrightness: Float = 0f,
    val yFilter: Float = 0f,
    val mFilter: Float = 0f,
    // effects
    val grainActive: Boolean = true,
    val grainAmount: Float = 1f,
    val halationActive: Boolean = true,
    val halationAmount: Float = 1f,
    val scatterAmount: Float = 1f,
    val couplersActive: Boolean = true,
    val couplersAmount: Float = 1f,
    val glareActive: Boolean = true,
    val glareAmount: Float = 1f,
    val sharpen: Boolean = true,
) {
    /** True when the change from [old] leaves the developed negative intact. */
    fun printSideOnlyChangeFrom(old: EditState): Boolean =
        copy(
            printStock = old.printStock, scanFilm = old.scanFilm,
            printBrightness = old.printBrightness, yFilter = old.yFilter, mFilter = old.mFilter,
            glareActive = old.glareActive, glareAmount = old.glareAmount, sharpen = old.sharpen,
        ) == old

    fun toNative(seed: Long) = NativeParams(
        filmStock = filmStock,
        printStock = printStock,
        scanFilm = scanFilm,
        exposureEv = exposureEv,
        autoExposure = autoExposure,
        filmFormatMm = format.longEdgeMm,
        halationActive = halationActive,
        halationAmount = halationAmount,
        scatterAmount = scatterAmount,
        couplersActive = couplersActive,
        couplersAmount = couplersAmount,
        grainActive = grainActive,
        grainSublayers = true,
        grainAmount = grainAmount,
        printExposure = 2f.pow(-printBrightness).coerceIn(0.05f, 20f),
        // the sliders read in the photo's direction (right = yellower /
        // more magenta); in the enlarger that is *less* Y / M filtration
        yFilterShift = -yFilter,
        mFilterShift = -mFilter,
        printExposureCompensation = false,
        glareActive = glareActive,
        glareAmount = glareAmount,
        unsharpMask = sharpen,
        seed = seed,
    )

    fun save(prefs: SharedPreferences) {
        val o = JSONObject()
            .put("filmStock", filmStock).put("printStock", printStock).put("scanFilm", scanFilm)
            .put("autoExposure", autoExposure).put("exposureEv", exposureEv.toDouble())
            .put("format", format.name)
            .put("printBrightness", printBrightness.toDouble())
            .put("yFilter", yFilter.toDouble()).put("mFilter", mFilter.toDouble())
            .put("grainActive", grainActive).put("grainAmount", grainAmount.toDouble())
            .put("halationActive", halationActive).put("halationAmount", halationAmount.toDouble())
            .put("scatterAmount", scatterAmount.toDouble())
            .put("couplersActive", couplersActive).put("couplersAmount", couplersAmount.toDouble())
            .put("glareActive", glareActive).put("glareAmount", glareAmount.toDouble())
            .put("sharpen", sharpen)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    companion object {
        private const val KEY = "edit_state_v2"

        // slider ranges (SpektraLab's EffectStrengths / FilmParams ranges)
        val EV_RANGE = -3f..3f
        val BRIGHTNESS_RANGE = -3f..3f
        val FILTER_RANGE = -40f..40f
        val GRAIN_RANGE = 0f..2f
        val HALATION_RANGE = 0f..4f
        val SCATTER_RANGE = 0f..1f
        val COUPLERS_RANGE = 0f..1.5f
        val GLARE_RANGE = 0f..10f

        fun load(prefs: SharedPreferences): EditState {
            val s = prefs.getString(KEY, null) ?: return EditState()
            return runCatching {
                val o = JSONObject(s)
                val d = EditState()
                fun f(k: String, def: Float) = o.optDouble(k, def.toDouble()).toFloat()
                EditState(
                    filmStock = o.optString("filmStock", d.filmStock),
                    printStock = o.optString("printStock", d.printStock),
                    scanFilm = o.optBoolean("scanFilm", d.scanFilm),
                    autoExposure = o.optBoolean("autoExposure", d.autoExposure),
                    exposureEv = f("exposureEv", d.exposureEv).coerceIn(EV_RANGE),
                    format = runCatching { FilmFormat.valueOf(o.optString("format")) }
                        .getOrDefault(d.format),
                    printBrightness = f("printBrightness", 0f).coerceIn(BRIGHTNESS_RANGE),
                    yFilter = f("yFilter", 0f).coerceIn(FILTER_RANGE),
                    mFilter = f("mFilter", 0f).coerceIn(FILTER_RANGE),
                    grainActive = o.optBoolean("grainActive", true),
                    grainAmount = f("grainAmount", 1f).coerceIn(GRAIN_RANGE),
                    halationActive = o.optBoolean("halationActive", true),
                    halationAmount = f("halationAmount", 1f).coerceIn(HALATION_RANGE),
                    scatterAmount = f("scatterAmount", 1f).coerceIn(SCATTER_RANGE),
                    couplersActive = o.optBoolean("couplersActive", true),
                    couplersAmount = f("couplersAmount", 1f).coerceIn(COUPLERS_RANGE),
                    glareActive = o.optBoolean("glareActive", true),
                    glareAmount = f("glareAmount", 1f).coerceIn(GLARE_RANGE),
                    sharpen = o.optBoolean("sharpen", true),
                )
            }.getOrDefault(EditState())
        }
    }
}
