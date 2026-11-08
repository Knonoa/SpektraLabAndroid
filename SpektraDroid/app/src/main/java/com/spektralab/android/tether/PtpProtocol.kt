package com.spektralab.android.tether

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** USB bulk byte stream; implementations may return fragmented or coalesced containers. */
interface PtpLink : Closeable {
    fun read(buffer: ByteArray): Int
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
}

class PtpFailure(val response: Int) : IOException("相机响应 0x${response.toString(16)}")

class PtpSession(private val link: PtpLink) : Closeable {
    private var transaction = 0
    private val pending = ByteArray(16384)
    private var cursor = 0
    private var available = 0

    private fun readExact(out: ByteArray, offset: Int, count: Int) {
        var done = 0
        var emptyPackets = 0
        while (done < count) {
            if (cursor == available) {
                available = link.read(pending); cursor = 0
                if (available < 0 || (available == 0 && ++emptyPackets > 8))
                    throw IOException("USB 读取超时或已断开，请重新连接")
                // PTP USB can terminate an exact max-packet transfer with a ZLP.
                // The response follows that empty packet; it is not EOF.
                if (available == 0) continue
                emptyPackets = 0
            }
            val n = minOf(count - done, available - cursor)
            pending.copyInto(out, offset + done, cursor, cursor + n)
            cursor += n; done += n
        }
    }

    private fun writeAll(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = link.write(bytes, offset, minOf(16384, bytes.size - offset))
            if (n <= 0) throw IOException("USB 写入失败，请重新连接")
            offset += n
        }
    }

    /** All commands run on one owner thread. Photos stream to disk, never a full-size byte array. */
    fun execute(code: Int, vararg params: Int, send: ByteArray? = null, sink: OutputStream? = null,
                limit: Long = 16L * 1024 * 1024, progress: (Long) -> Unit = {}): ByteArray {
        require(params.size <= 5)
        // PTP OpenSession must use transaction 0, including after reconnect.
        if (code == 0x1002) transaction = -1
        val id = ++transaction
        fun header(size: Int, type: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(size).putShort(type.toShort()).putShort(code.toShort()).putInt(id)
        writeAll(header(12 + params.size * 4, 1).apply { params.forEach { putInt(it) } }.array())
        if (send != null) writeAll(header(12 + send.size, 2).put(send).array())
        val bytes = if (sink == null) ByteArrayOutputStream() else null
        var gotData = false
        while (true) {
            val raw = ByteArray(12); readExact(raw, 0, 12)
            val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val length = h.int.toLong() and 0xffffffffL
            val type = h.short.toInt() and 65535
            val response = h.short.toInt() and 65535
            checkIo(h.int == id && length >= 12, "PTP 数据序号或长度异常，请重新连接")
            if (type == 3) {
                checkIo(length <= 32, "PTP 响应过长")
                val extra = ByteArray((length - 12).toInt()); readExact(extra, 0, extra.size)
                if (response != 0x2001) throw PtpFailure(response)
                return bytes?.toByteArray() ?: byteArrayOf()
            }
            checkIo(type == 2 && response == code && !gotData && length - 12 <= limit, "PTP 数据格式或大小异常")
            gotData = true
            var left = length - 12
            var received = 0L
            val block = ByteArray(16384)
            while (left > 0) {
                val n = minOf(left, block.size.toLong()).toInt()
                readExact(block, 0, n)
                (sink ?: bytes!!).write(block, 0, n)
                left -= n; received += n; progress(received)
            }
        }
    }

    override fun close() = link.close()
    private fun checkIo(ok: Boolean, message: String) { if (!ok) throw IOException(message) }
}

/** Bounded PTP dataset reader. Sony's 2024+ second enumeration is significant. */
class PtpReader(private val bytes: ByteArray) {
    var offset = 0
    val remaining get() = bytes.size - offset
    fun u8(): Int { require(remaining >= 1) { "PTP 数据截断" }; return bytes[offset++].toInt() and 255 }
    fun u16() = u8() or (u8() shl 8)
    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
    fun skip(n: Int) { require(n >= 0 && n <= remaining); offset += n }
    fun string(): String {
        val n = u8()
        if (n == 0) return ""
        require(n * 2 <= remaining)
        return buildString { repeat(n) { val c = u16(); if (c != 0) append(c.toChar()) } }
    }
    fun codes(): List<Int> {
        val n = u32(); require(n <= remaining / 2)
        return List(n.toInt()) { u16() }
    }
    fun value(type: Int): Long {
        if (type == 0xffff) { string(); return 0 }
        if (type and 0x4000 != 0) {
            val n = u32(); require(n <= 65536)
            repeat(n.toInt()) { value(type and 0x3fff) }; return 0
        }
        return when (type) {
            1 -> u8().toByte().toLong(); 2 -> u8().toLong()
            3 -> u16().toShort().toLong(); 4 -> u16().toLong()
            5 -> u32().toInt().toLong(); 6 -> u32()
            7, 8 -> { val lo = u32(); lo or (u32() shl 32) }
            9, 10 -> { skip(16); 0 }
            else -> error("未知 PTP 属性类型 $type")
        }
    }
    fun peek16(): Int { val old = offset; return u16().also { offset = old } }
}

data class SonyProperty(val code: Int, val type: Int, val writable: Boolean, val value: Long, val choices: List<Long>)
data class CameraObject(val name: String, val bytes: Long, val format: Int, val width: Long, val height: Long)
data class SonyInfo(val model: String, val operations: Set<Int>)

object SonyDatasets {
    fun deviceInfo(data: ByteArray): SonyInfo = PtpReader(data).run {
        u16(); u32(); u16(); string(); u16()
        val ops = codes().toSet(); repeat(4) { codes() }
        string(); val model = string(); string(); string()
        SonyInfo(model, ops)
    }
    fun properties(data: ByteArray, version: Int = 3): Map<Int, SonyProperty> = PtpReader(data).run {
        val count = u32(); u32(); require(count <= 4096)
        val result = mutableMapOf<Int, SonyProperty>()
        repeat(count.toInt()) {
            val code = u16(); val type = u16(); val access = u8(); val enabled = u8()
            value(type); val current = value(type)
            val form = u8()
            var choices: List<Long> = when (form) {
                0 -> emptyList(); 1 -> { repeat(3) { value(type) }; emptyList() }
                2 -> { val n = u16(); require(n <= 16384); List(n) { value(type) } }
                else -> error("未知 Sony 属性范围 $form")
            }
            if (remaining >= 2 && peek16() < 0x200) {
                val n = u16()
                if (form == 2) choices = List(n) { value(type) }
                else { require(n == 0) { "未知 Sony 二级属性列表" } }
            }
            result[code] = SonyProperty(code, type, enabled == 1 && (version == 2 || access != 0), current, choices)
        }
        result
    }
    fun objectInfo(data: ByteArray): CameraObject = PtpReader(data).run {
        u32(); val format = u16(); u16(); val length = u32()
        u16(); repeat(3) { u32() }; val w = u32(); val h = u32()
        u32(); u32(); u16(); u32(); u32()
        CameraObject(string(), length, format, w, h)
    }
    fun jpeg(data: ByteArray): ByteArray? {
        val offset = if (data.size >= 4) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int else -1
        fun start(i: Int) = i >= 0 && i + 2 < data.size && data[i] == 0xff.toByte() && data[i+1] == 0xd8.toByte() && data[i+2] == 0xff.toByte()
        val from = if (start(offset)) offset else (0 until data.size - 2).firstOrNull(::start) ?: return null
        val end = (data.size - 2 downTo from + 2).firstOrNull { data[it] == 0xff.toByte() && data[it+1] == 0xd9.toByte() } ?: return null
        return data.copyOfRange(from, end + 2)
    }
    val models = linkedMapOf(0x0f56 to "A7 V", 0x0eed to "A1 II", 0x0e0c to "A7R V", 0x0da7 to "A7 IV",
        0x0d1c to "A1", 0x0e78 to "A6700", 0x0ccc to "A7R IV", 0x0d9f to "A7R IVA",
        0x0c33 to "A7R III", 0x0d9b to "A7R IIIA", 0x0c34 to "A7 III")
}
