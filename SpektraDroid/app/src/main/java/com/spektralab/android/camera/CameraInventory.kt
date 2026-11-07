package com.spektralab.android.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One physical lens behind a camera: what a lens chip (0.6x, 1x, 3x) stands for. */
data class Lens(
    val id: String,
    val focalMm: Float,
    /** the 135 equivalent: focal x 43.27 mm / the sensor's diagonal */
    val equivMm: Float,
    val aperture: Float?,
    val sensorWmm: Float,
    val sensorHmm: Float,
    val megapixels: Float,
)

/**
 * A camera as Camera2 reports it. A *logical* camera (most phones' back
 * camera) fronts several physical lenses and switches between them by zoom
 * ratio; many OEMs expose only those logical cameras to third-party apps, so
 * the lenses are reached through [zoomFor], not by opening their ids.
 */
data class CameraDevice(
    val id: String,
    val facing: Int,                 // CameraCharacteristics.LENS_FACING_*
    val main: Lens,                  // the camera's own (default) optics
    val physical: List<Lens>,        // the lenses behind a logical multi-camera; empty otherwise
    val zoomMin: Float,
    val zoomMax: Float,
    val hardwareLevel: String,
    val raw: Boolean,
    val manualSensor: Boolean,
    val logical: Boolean,
    val hasFlash: Boolean,
    /** The HAL's own multi-frame HDR (CONTROL_SCENE_MODE_HDR), for phones that do not expose the HDR extension */
    val sceneHdr: Boolean = false,
    val isoRange: String? = null,
    val exposureRange: String? = null,
) {
    val facingLabel: String
        get() = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            CameraCharacteristics.LENS_FACING_BACK -> "后置"
            else -> "外接"
        }

    /** Lens chips: each distinct physical lens as the zoom ratio that selects it, clamped to the range. */
    val lensStops: List<Float>
        get() {
            val stops = physical.filter { main.equivMm > 0 && it.equivMm > 0 }.map { it.equivMm / main.equivMm }
                .map { it.coerceIn(zoomMin, zoomMax) }
                .toMutableList()
            stops += 1f
            if (physical.isEmpty() && zoomMax >= 2f) stops += 2f
            return stops.map { (it * 10f).roundToInt() / 10f }.distinct().sorted()
                .fold(mutableListOf<Float>()) { acc, v -> if (acc.none { abs(it - v) < 0.15f }) acc += v; acc }
        }

    /** The lens a zoom ratio lands on (the longest whose equivalent the zoom has reached). */
    fun lensAt(zoom: Float): Lens {
        if (physical.isEmpty()) return main
        val eq = main.equivMm * zoom
        return physical.filter { it.equivMm <= eq * 1.04f }.maxByOrNull { it.equivMm }
            ?: physical.minBy { it.equivMm }
    }
}

object CameraInventory {
    private const val TAG = "CameraInventory"
    private const val FULL_FRAME_DIAG = 43.27f

    fun list(context: Context): List<CameraDevice> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { cm.cameraIdList.toList() }.getOrElse { emptyList() }
        return ids.mapNotNull { id -> runCatching { describe(cm, id) }.onFailure { Log.w(TAG, "camera $id", it) }.getOrNull() }
    }

    private fun lens(cm: CameraManager, id: String, c: CameraCharacteristics): Lens? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = sensor?.let { hypot(it.width, it.height) } ?: 0f
        val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        // The largest JPEG the camera hands over (may be below the pixel array on binned sensors)
        val jpeg = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
        val mp = (jpeg ?: px)?.let { it.width * it.height / 1e6f } ?: 0f
        return Lens(
            id = id,
            focalMm = focal,
            equivMm = if (diag > 0f) focal * FULL_FRAME_DIAG / diag else 0f,
            aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
            sensorWmm = sensor?.width ?: 0f,
            sensorHmm = sensor?.height ?: 0f,
            megapixels = mp,
        )
    }

    private fun describe(cm: CameraManager, id: String): CameraDevice? {
        val c = cm.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        // CameraX binds backward-compatible cameras only (depth-only sensors are not cameras to it)
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) return null
        val main = lens(cm, id, c) ?: return null
        val logical = Build.VERSION.SDK_INT >= 28 &&
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val physical = if (Build.VERSION.SDK_INT >= 28 && logical) {
            c.physicalCameraIds.mapNotNull { pid ->
                runCatching { lens(cm, pid, cm.getCameraCharacteristics(pid)) }.getOrNull()
            }.sortedBy { it.equivMm }
        } else emptyList()
        var zMin = 1f
        var zMax = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        if (Build.VERSION.SDK_INT >= 30) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { zMin = it.lower; zMax = it.upper }
        }
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        return CameraDevice(
            id = id,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_EXTERNAL,
            main = main,
            physical = physical,
            zoomMin = zMin,
            zoomMax = zMax,
            hardwareLevel = level,
            raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            logical = logical,
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            sceneHdr = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                ?.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR) == true,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { "${it.lower}–${it.upper}" },
            exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                fun seconds(ns: Long): String = if (ns >= 1_000_000_000L)
                    String.format(java.util.Locale.US, "%.1f s", ns / 1e9)
                else "1/${(1e9 / ns.coerceAtLeast(1)).roundToInt()} s"
                "${seconds(it.lower)}–${seconds(it.upper)}"
            },
        )
    }
}
