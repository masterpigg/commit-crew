package com.fll.pushtogithub.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreValueTest {

    @Test
    fun fromLabelMatchesLabelKey() {
        for (cv in CoreValue.entries) {
            assertEquals(cv, CoreValue.fromLabel(cv.labelKey))
        }
    }

    @Test
    fun fromLabelStripsPrefixAndCase() {
        assertEquals(CoreValue.TEAMWORK, CoreValue.fromLabel("core-value:Teamwork"))
        assertEquals(CoreValue.FUN, CoreValue.fromLabel("  FUN "))
    }

    @Test
    fun fromLabelUnknownIsNull() {
        assertNull(CoreValue.fromLabel("robot-game"))
    }
}
