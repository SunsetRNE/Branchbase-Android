package com.branchbase.downloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDiagnosticChangesTest {
    @Test
    fun repeatedProgressDoesNotRepeatDiagnostic() {
        val changes = NotificationDiagnosticChanges()
        assertTrue(changes.changed("publish", "已提交"))
        repeat(1000) { assertFalse(changes.changed("publish", "已提交")) }
    }

    @Test
    fun changesAndRecoveryAreReported() {
        val changes = NotificationDiagnosticChanges()
        assertTrue(changes.changed("publish", "已提交"))
        assertTrue(changes.changed("publish", "失败"))
        assertFalse(changes.changed("publish", "失败"))
        assertTrue(changes.changed("publish", "已提交"))
    }

    @Test
    fun independentStatesAndNewSessionAreReported() {
        val changes = NotificationDiagnosticChanges()
        assertTrue(changes.changed("permission", "否"))
        assertTrue(changes.changed("system", "否"))
        changes.clear("system")
        assertTrue(changes.changed("system", "否"))
        assertFalse(changes.changed("permission", "否"))
    }
}
