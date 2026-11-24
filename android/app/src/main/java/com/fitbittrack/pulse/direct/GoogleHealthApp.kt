package com.fitbittrack.pulse.direct

import android.content.ActivityNotFoundException
import android.content.Context

/** The Google Health app (the former Fitbit app). It is the only app that can sync the band, and it does so each time it opens. */
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
