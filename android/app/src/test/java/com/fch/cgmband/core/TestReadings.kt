package com.fch.cgmband.core

internal const val MINUTE = 60_000L
internal const val T0 = 1_800_000_000_000L

/** Reading taken [minutesAfterT0] minutes after [T0]; a null [mmol] renders as "LO". */
internal fun reading(
    minutesAfterT0: Long?,
    mmol: Double?,
    connected: Boolean = true,
) = GlucoseReading(
    value = mmol?.toString() ?: "LO",
    unit = "mmol/L",
    mmol = mmol,
    time = minutesAfterT0?.let { "t+$it" }.orEmpty(),
    readingAt = minutesAfterT0?.let { T0 + it * MINUTE },
    postedAt = T0 + (minutesAfterT0 ?: 0) * MINUTE,
    connected = connected,
)
