package com.fch.cgmband.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerstickTest {

    private fun stick(meal: Meal?, tag: FingerstickTag?) =
        Fingerstick("AA:BB", MeterRecord(1, 0L, 5.0, meal, MeterFlag.NONE), tag)

    @Test
    fun `the user's tag beats the meter's meal flag`() {
        assertEquals(FingerstickTag.AFTER_MEAL_60, stick(Meal.FASTING, FingerstickTag.AFTER_MEAL_60).effectiveTag)
        assertNull(stick(Meal.AFTER_MEAL, FingerstickTag.AFTER_MEAL_120).untaggedMeal)
    }

    @Test
    fun `meter flags that mean the same as a tag become that tag`() {
        assertEquals(FingerstickTag.FASTING, stick(Meal.FASTING, null).effectiveTag)
        assertEquals(FingerstickTag.BEDTIME, stick(Meal.BEDTIME, null).effectiveTag)
        assertNull(stick(Meal.FASTING, null).untaggedMeal)
    }

    @Test
    fun `other meter flags are shown as they are until the user tags the result`() {
        listOf(Meal.BEFORE_MEAL, Meal.AFTER_MEAL, Meal.CASUAL).forEach { meal ->
            assertNull(stick(meal, null).effectiveTag)
            assertEquals(meal, stick(meal, null).untaggedMeal)
        }
        assertNull(stick(null, null).effectiveTag)
        assertNull(stick(null, null).untaggedMeal)
    }
}
