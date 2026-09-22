package com.branchbase.ui.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备档案格式化的单测。
 *
 * 这一层出错的后果是「日志里少了一行、或者多了一行换行」——前者让远程排查缺一块拼图，
 * 后者破坏**行式日志**的约定（`logs/<日期>/branchbase.log` 一行一条，按行过滤/解析），
 * 所以两件事都钉住：**行数固定**、**每行不含换行**。
 */
class DeviceProfileFormatTest {

    private fun fields(
        refreshHz: Float = 60f,
        animatorScale: Float = 1f,
        alwaysFinish: Boolean = false,
        powerSave: Boolean = false,
        debuggable: Boolean = false,
        cpuMaxMhz: Long? = 2841L,
        lowMemory: Boolean = false,
    ) = DeviceFields(
        brand = "OnePlus",
        manufacturer = "OnePlus",
        model = "PJD110",
        device = "OP594DL1",
        androidRelease = "16",
        sdkInt = 36,
        buildDisplay = "CPH2581_16.0.0.1",
        fingerprint = "OnePlus/PJD110/PJD110:16/AP3A",
        widthPx = 1440,
        heightPx = 3168,
        densityDpi = 640,
        widthDp = 360,
        heightDp = 792,
        fontScale = 1f,
        darkMode = true,
        refreshHz = refreshHz,
        modeHz = listOf(60f, 120f),
        peakHz = 120f,
        memTotalMb = 23142,
        memAvailMb = 10547,
        lowMemory = lowMemory,
        heapMaxMb = 512,
        cores = 8,
        abi = "arm64-v8a",
        cpuMaxMhz = cpuMaxMhz,
        storageFreeGb = 539,
        storageTotalGb = 932,
        locale = "zh_CN",
        timeZone = "Asia/Shanghai",
        animatorScale = animatorScale,
        transitionScale = animatorScale,
        windowScale = animatorScale,
        alwaysFinish = alwaysFinish,
        developmentEnabled = true,
        powerSave = powerSave,
        appVersion = "1.0.60-20260922-1900-abcdef0-Beta",
        appVersionCode = 162,
        debuggable = debuggable,
    )

    @Test
    fun `固定五行_每行都不含换行`() {
        val lines = formatDeviceProfile(fields())
        assertEquals("档案固定 5 行（缺项也要占位，不能整块消失）", 5, lines.size)
        lines.forEach { line ->
            assertFalse("行式日志的硬约定：单行内不能有换行 → $line", line.contains('\n'))
            assertFalse("也不该有回车 → $line", line.contains('\r'))
            assertTrue("每行都要有内容", line.isNotBlank())
        }
    }

    @Test
    fun `关键事实都在`() {
        val text = formatDeviceProfile(fields()).joinToString("\n")
        listOf("PJD110", "Android 16", "SDK 36", "1440x3168", "360x792dp", "刷新率 60Hz",
            "内存 22.6GB", "8 核 arm64-v8a", "动画缩放", "不保留活动", "省电模式",
            "versionCode 162").forEach { fact ->
            assertTrue("缺了「$fact」：\n$text", text.contains(fact))
        }
    }

    @Test
    fun `高刷机要提示阈值按 60Hz 写死`() {
        val line = formatDeviceProfile(fields(refreshHz = 120f)).joinToString("\n")
        assertTrue("120Hz 机器上慢帧阈值需要换算，档案里必须提示：$line", line.contains("需换算"))
    }

    @Test
    fun `动画被调小要标注_否则动效验收会失真`() {
        val line = formatDeviceProfile(fields(animatorScale = 0f)).joinToString("\n")
        assertTrue("开发者选项把动画关掉时，动效类反馈不能直接采信：$line", line.contains("动效验收会失真"))
    }

    @Test
    fun `不保留活动与省电模式要醒目`() {
        val text = formatDeviceProfile(fields(alwaysFinish = true, powerSave = true)).joinToString("\n")
        assertTrue("「不保留活动」会让每次离开页面都重建，是「重进就重建」类反馈的头号原因", text.contains("不保留活动 **开**"))
        assertTrue("省电模式会限频限刷新率", text.contains("省电模式 **开**"))
    }

    @Test
    fun `debug 包要标注性能不代表正式版`() {
        val text = formatDeviceProfile(fields(debuggable = true)).joinToString("\n")
        assertTrue(text.contains("debug 包"))
    }

    @Test
    fun `缺项用问号占位_不写 null`() {
        val text = formatDeviceProfile(fields(refreshHz = 0f, cpuMaxMhz = null)).joinToString("\n")
        assertFalse("不能把 null 写进日志：\n$text", text.contains("null"))
        assertTrue("读不到的项用 ? 占位：\n$text", text.contains("刷新率 ?"))
    }

    @Test
    fun `低内存状态要标注`() {
        val text = formatDeviceProfile(fields(lowMemory = true)).joinToString("\n")
        assertTrue(text.contains("低内存**"))
    }
}
