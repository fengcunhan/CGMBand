package com.fch.cgmband

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.fch.cgmband.core.Fingerstick
import com.fch.cgmband.core.FingerstickTag
import com.fch.cgmband.core.Meal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How a fingerstick result is labelled, and the dialog that tags when it was taken. */
object FingerstickTags {

    /** See [Fingerstick.effectiveTag]: the user's tag, else the meter's meal flag, else null. */
    fun label(context: Context, stick: Fingerstick): String? =
        stick.effectiveTag?.let { context.getString(tagLabel(it)) }
            ?: stick.untaggedMeal?.let { context.getString(mealLabel(it)) }

    /** Lets the user pick when [stick] was taken; [onChanged] gets the saved tag (null = cleared). */
    fun pick(activity: Activity, stick: Fingerstick, onChanged: (FingerstickTag?) -> Unit) {
        val tags = FingerstickTag.entries
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(stick.at))
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.fingerstick_tag_title, time, stick.record.label))
            .setSingleChoiceItems(tags.map { activity.getString(tagLabel(it)) }.toTypedArray(), tags.indexOf(stick.tag)) { dialog, which ->
                dialog.dismiss()
                save(activity, stick, tags[which], onChanged)
            }
            .setNegativeButton(R.string.cancel, null)
        if (stick.tag != null) {
            builder.setNeutralButton(R.string.fingerstick_tag_clear) { _, _ -> save(activity, stick, null, onChanged) }
        }
        builder.show()
    }

    private fun save(activity: Activity, stick: Fingerstick, tag: FingerstickTag?, onChanged: (FingerstickTag?) -> Unit) {
        MeterDatabase.get(activity).setTag(stick.device, stick.record.sequence, tag) { saved ->
            if (activity.isDestroyed) return@setTag
            if (saved) onChanged(tag) else Toast.makeText(activity, R.string.meter_error_storage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun tagLabel(tag: FingerstickTag): Int = when (tag) {
        FingerstickTag.AFTER_MEAL_30 -> R.string.tag_after_meal_30
        FingerstickTag.AFTER_MEAL_60 -> R.string.tag_after_meal_60
        FingerstickTag.AFTER_MEAL_120 -> R.string.tag_after_meal_120
        FingerstickTag.FASTING -> R.string.tag_fasting
        FingerstickTag.BEDTIME -> R.string.tag_bedtime
    }

    private fun mealLabel(meal: Meal): Int = when (meal) {
        Meal.BEFORE_MEAL -> R.string.meal_before
        Meal.AFTER_MEAL -> R.string.meal_after
        Meal.CASUAL -> R.string.meal_casual
        Meal.FASTING -> R.string.tag_fasting // normally shown as the matching tag already
        Meal.BEDTIME -> R.string.tag_bedtime
    }
}
