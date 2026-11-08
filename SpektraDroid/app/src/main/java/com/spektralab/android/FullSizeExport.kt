package com.spektralab.android

import android.app.ActivityManager
import android.content.Context
import android.os.Process

/** Advisory peak estimate, checked against currently available memory, not advertised RAM. */
object FullSizeExport {
    fun estimatedBytes(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int): Long {
        val input = sourceWidth.toLong() * sourceHeight
        val canvas = outputWidth.toLong() * outputHeight
        require(input in 1..200_000_000 && canvas in 1..250_000_000) { "图片像素数量超出全尺寸处理范围" }
        return input * 24 + canvas * 80 + 512L * 1024 * 1024
    }
    fun checkMemory(context: Context, estimate: Long) {
        check(Process.is64Bit()) { "全尺寸处理需要 64 位设备" }
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        check(!info.lowMemory && estimate < info.availMem - info.threshold) {
            "全尺寸处理预计需要约 %.1f GB，当前可用约 %.1f GB；请释放内存或选择较小尺寸".format(estimate/1e9,info.availMem/1e9)
        }
    }
}
