package com.coda.mobileui.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 皮肤 manifest 的可信范围：超出的部件与超长的行一律跳过，不让渲染层去分配。 */
class PetSkinGuardTest {
    @Test
    fun originStaysWithinTheHandDrawnRange() {
        assertTrue(isPlausiblePartOrigin(0, 0))
        assertTrue(isPlausiblePartOrigin(-64, 64))
        assertFalse(isPlausiblePartOrigin(-65, 0))
        assertFalse(isPlausiblePartOrigin(0, 65))
        assertFalse(isPlausiblePartOrigin(Int.MIN_VALUE, 0))
    }

    @Test
    fun absurdlyWideRowsAreRejected() {
        assertTrue(isPlausiblePartLine("."))
        assertTrue(isPlausiblePartLine("x".repeat(PET_PART_MAX_LINE)))
        assertFalse(isPlausiblePartLine("x".repeat(PET_PART_MAX_LINE + 1)))
    }
}
