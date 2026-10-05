package com.branchbase

import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.branchbase.downloader.DownloadNotificationState
import com.branchbase.downloader.DownloadStatus
import com.branchbase.downloader.GoogleLiveUpdateExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DownloadLiveUpdateTest {
    @Test
    fun android16ProgressNotificationsAreEligibleForPromotion() {
        assumeTrue(Build.VERSION.SDK_INT >= 36)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extension = GoogleLiveUpdateExtension()
        assertTrue(extension.isAvailable(context))
        for ((done, total) in listOf(0L to 100L, 42L to 100L, 100L to 100L, 0L to 0L)) {
            val state = DownloadNotificationState(
                taskId = "live-update-test",
                title = "下载测试",
                fileName = "test.bin",
                status = DownloadStatus.RUNNING,
                downloadedBytes = done,
                totalBytes = total,
            )
            val builder = NotificationCompat.Builder(context, "branchbase_downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(state.title)
                .setContentText(state.progressText)
                .setProgress(100, state.percent ?: 0, state.indeterminate)
                .setSilent(true)
            extension.decorate(context, builder, state)
            val notification = builder.build()
            assertTrue(notification.extras.getBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING))
            assertTrue(NotificationCompat.hasPromotableCharacteristics(notification))
            assertEquals(
                state.percent?.let { "$it%" } ?: "下载中",
                notification.extras.getString(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT),
            )
        }
    }
}
