package com.fch.cgmband.core

/**
 * When a fingerstick was taken, as the user tags it. Finer than the meter's own meal flag
 * ([Meal]), which only knows before / after a meal; the user's tag wins when both exist.
 */
enum class FingerstickTag { AFTER_MEAL_30, AFTER_MEAL_60, AFTER_MEAL_120, FASTING, BEDTIME }

/** A stored fingerstick result: the meter's record, the meter it came from, and the user's tag. */
data class Fingerstick(val device: String, val record: MeterRecord, val tag: FingerstickTag?) {
    val at: Long get() = record.at

    /** The user's tag; else the meter's meal flag where it means the same thing (fasting, bedtime). */
    val effectiveTag: FingerstickTag?
        get() = tag ?: when (record.meal) {
            Meal.FASTING -> FingerstickTag.FASTING
            Meal.BEDTIME -> FingerstickTag.BEDTIME
            else -> null
        }

    /** The meter's meal flag when no tag applies (before / after a meal, casual), shown as it is. */
    val untaggedMeal: Meal?
        get() = if (effectiveTag == null) record.meal else null
}
