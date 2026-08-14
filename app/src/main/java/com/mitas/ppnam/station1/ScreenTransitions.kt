package com.mitas.ppnam.station1

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent

/**
 * Directional, symmetric screen transitions: forward navigation slides the new
 * screen in from the right while the old one eases out to the left; back
 * navigation mirrors the exact same path in reverse. Per "enter and exit along
 * the same path" - a screen that arrives from the right but leaves by fading
 * would read as spatially disconnected. Falls back to the platform default
 * (no override) when the system's "remove animations" accessibility setting
 * is on.
 */
fun Activity.startActivityForward(intent: Intent) {
    startActivity(intent)
    if (ValueAnimator.areAnimatorsEnabled()) {
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }
}

fun Activity.finishBackward() {
    finish()
    if (ValueAnimator.areAnimatorsEnabled()) {
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
