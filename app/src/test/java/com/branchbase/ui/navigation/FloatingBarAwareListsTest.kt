package com.branchbase.ui.navigation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBarAwareListsTest {

    @Test
    fun `visible bar adds clearance over zero existing padding`() {
        assertEquals(96.dp, barAwareBottomPadding(existingBottom = 0.dp, barReserved = 80.dp))
    }

    @Test
    fun `larger existing bottom padding is preserved`() {
        assertEquals(110.dp, barAwareBottomPadding(existingBottom = 110.dp, barReserved = 80.dp))
    }

    @Test
    fun `hidden bar leaves existing padding untouched`() {
        assertEquals(24.dp, barAwareBottomPadding(existingBottom = 24.dp, barReserved = 0.dp))
        assertEquals(0.dp, barAwareBottomPadding(existingBottom = 0.dp, barReserved = 0.dp))
    }

    @Test
    fun `compact landscape bar uses its own measured height`() {
        assertEquals(80.dp, barAwareBottomPadding(existingBottom = 0.dp, barReserved = 64.dp))
    }
}
