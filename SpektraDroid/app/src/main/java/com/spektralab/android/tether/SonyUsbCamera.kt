package com.spektralab.android.tether

import android.content.Context
import android.hardware.usb.*
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Android owns USB permission and the connection; no root or native USB driver. */
class AndroidPtpLink(manager: UsbManager, device: UsbDevice) : PtpLink {
    private val connection = manager.openDevice(device) ?: throw IOException("无法打开 USB 相机")
    private val intf = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { i ->
        i.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE && (0 until i.endpointCount).map(i::getEndpoint)
            .count { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2
    } ?: run { connection.close(); throw IOException("请将相机 USB 连接模式切换为 PC 遥控") }
    private val input = (0 until intf.endpointCount).map(intf::getEndpoint)
        .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
    private val output = (0 until intf.endpointCount).map(intf::getEndpoint)
        .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
    init { if (!connection.claimInterface(intf, true)) { connection.close(); throw IOException("USB 相机已被其他应用占用") } }
    override fun read(buffer: ByteArray) = connection.bulkTransfer(input, buffer, buffer.size, 5000)
    override fun write(buffer: ByteArray, offset: Int, length: Int) = connection.bulkTransfer(output, buffer, offset, length, 5000)
    override fun close() { connection.releaseInterface(intf); connection.close() }
}

/** Protocol facts adapted from libgphoto2 ptp2 (LGPL-2.0-or-later); see docs/SONY_USB.md. */
class SonyUsbCamera(private val ptp: PtpSession) : Closeable {
    private var version = 3
    private val restore = mutableListOf<SonyProperty>()
    var info = SonyInfo("Sony", emptySet()); private set
    var properties = emptyMap<Int, SonyProperty>(); private set
    var setupMessage = ""; private set
    fun connect() {
        ptp.execute(0x1002, 1)
        info = SonyDatasets.deviceInfo(ptp.execute(0x1001))
        require(0x9202 in info.operations) { "当前不是 Sony PC 遥控模式，请在相机菜单切换后重新连接" }
        ptp.execute(0x9201, 1, 0, 0)
        ptp.execute(0x9201, 2, 0, 0)
        var extension = byteArrayOf()
        repeat(3) { if (extension.isEmpty()) extension = ptp.execute(0x9202, 0x12c, 1) }
        require(extension.isNotEmpty()) { "相机未返回遥控能力，请重新连接" }
        version = if (PtpReader(extension).u16() == 0x12c) 3 else 2
        ptp.execute(0x9201, 3, 0, 0)
        poll()
        // Keep a copy on the card. Never select PC-only, delete an object, or change RAW crop/compression.
        val destination = properties[0xd222]
        val original = properties[0xd268]
        if (destination != null && destination.value != 17L && destination.writable && 17L in destination.choices) set(destination, 17)
        if (original != null && original.value != 1L && original.writable && 1L in original.choices) set(original, 1)
        poll()
        setupMessage = if (properties[0xd222]?.value == 16L)
            "相机保存目标仍为存储卡，请在 PC 遥控设置选择「电脑＋相机」，传输尺寸选择原始大小" else
            "在相机上按快门即可接收；请在相机 PC 遥控设置选择需要传输的 RAW / JPEG"
    }
    private fun set(p: SonyProperty, value: Long) {
        val size = when (p.type) { 2 -> 1; 4 -> 2; 6 -> 4; else -> return }
        val bytes = ByteArray(size) { (value shr (it * 8)).toByte() }
        try { ptp.execute(0x9205, p.code, send = bytes); restore += p }
        catch (e: PtpFailure) { if (e.response !in listOf(0x2005, 0x200f, 0x2019)) throw e }
    }
    fun poll(): Map<Int, SonyProperty> {
        properties = SonyDatasets.properties(ptp.execute(0x9209),version); return properties
    }
    fun hasPhoto() = (properties[0xd215]?.value ?: 0) > 0x8000L
    fun photoInfo(): CameraObject {
        check(hasPhoto()) { "相机尚未确认照片就绪" }
        return SonyDatasets.objectInfo(ptp.execute(0x1008, 0xffffc001.toInt()))
    }
    fun receive(file: File, objectInfo: CameraObject, progress: (Long) -> Unit) {
        require(objectInfo.bytes in 1..(1024L * 1024 * 1024)) { "照片大小无效或超过 1 GB" }
        file.outputStream().use { out ->
            ptp.execute(0x1009, 0xffffc001.toInt(), sink = out, limit = objectInfo.bytes, progress = progress)
        }
        check(file.length() == objectInfo.bytes) { "照片传输不完整" }
    }
    fun liveJpeg(): ByteArray? = try {
        ptp.execute(0x1008, 0xffffc002.toInt())
        SonyDatasets.jpeg(ptp.execute(0x1009, 0xffffc002.toInt(), limit = 12L * 1024 * 1024))
    } catch (e: PtpFailure) {
        if (e.response in listOf(0x2009, 0x200f, 0x2019)) null else throw e
    }
    /** Only call from the command thread after the loop exits. */
    fun finish() {
        try {
            restore.asReversed().forEach { p ->
                val n = when (p.type) { 2 -> 1; 4 -> 2; else -> 4 }
                ptp.execute(0x9205, p.code, send = ByteArray(n) { (p.value shr (it * 8)).toByte() })
            }
            ptp.execute(0x1003)
        } catch (_: Exception) { /* unplugged devices cannot restore remote properties */ }
        finally { close() }
    }
    override fun close() = ptp.close()
    companion object {
        fun devices(context: Context): List<UsbDevice> =
            (context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.filter { it.vendorId == 0x054c }
    }
}
