package com.fitbittrack.pulse.direct

import android.content.ActivityNotFoundException
import android.content.Context

/** The Google Health app (the former Fitbit app). Only it can sync the band: pull down on its Today page. Opening it alone is not always enough. */
object GoogleHealthApp {
    private const val PACKAGE = "com.fitbit.FitbitMobile"

    private fun intent(context: Context) = context.packageManager.getLaunchIntentForPackage(PACKAGE)

    fun installed(context: Context): Boolean = intent(context) != null

    fun open(context: Context): Boolean = try {
        intent(context)?.let { context.startActivity(it) } != null
    } catch (_: ActivityNotFoundException) {
        false
    }
}
