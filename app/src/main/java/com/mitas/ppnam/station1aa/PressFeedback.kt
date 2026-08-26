package com.mitas.ppnam.station1aa

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Instant, interruptible press feedback: scales the view down the moment a finger
 * touches it (not on release) and springs back on lift, per Apple's fluid-interface
 * principles - response lives on press-down, and a critically-damped spring (no
 * overshoot) reads as a settle rather than a bounce, which is the right choice for
 * a tap that carries no gesture momentum. Because it's a spring, a fast repeated
 * tap re-targets smoothly instead of restarting a fixed animation.
 *
 * No-ops when the system's "remove animations" accessibility setting is on.
 */
fun View.applyPressScaleFeedback(pressedScale: Float = 0.96f) {
    if (!ValueAnimator.areAnimatorsEnabled()) return

    fun springTo(property: FloatPropertyCompat<View>, target: Float) {
        SpringAnimation(this, property, target).apply {
            spring = SpringForce(target).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
        }.start()
    }

    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                springTo(DynamicAnimation.SCALE_X, pressedScale)
                springTo(DynamicAnimation.SCALE_Y, pressedScale)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                springTo(DynamicAnimation.SCALE_X, 1f)
                springTo(DynamicAnimation.SCALE_Y, 1f)
            }
        }
        // Let the view's normal click/ripple handling still run.
        v.onTouchEvent(event)
    }
}

/**
 * A one-shot attention pulse (scale up, spring back to rest) - used to nudge the
 * operator toward a button the instant it becomes actionable (e.g. a form just
 * became valid), rather than relying on a state color change alone to be noticed.
 * No-ops under reduced motion, same as [applyPressScaleFeedback].
 */
fun View.flashAttention(peakScale: Float = 1.08f) {
    if (!ValueAnimator.areAnimatorsEnabled()) return

    fun pulse(property: FloatPropertyCompat<View>) {
        SpringAnimation(this, property, peakScale).apply {
            spring = SpringForce(peakScale).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
            addEndListener { _, _, _, _ ->
                SpringAnimation(this@flashAttention, property, 1f).apply {
                    spring = SpringForce(1f).apply {
                        dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                        stiffness = SpringForce.STIFFNESS_HIGH
                    }
                }.start()
            }
        }.start()
    }

    pulse(DynamicAnimation.SCALE_X)
    pulse(DynamicAnimation.SCALE_Y)
}
