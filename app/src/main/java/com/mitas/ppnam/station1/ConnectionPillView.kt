package com.mitas.ppnam.station1

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Rounded connection-status badge, matching Station 2's exact pill: 50%-radius background at
 * 12% colour alpha, a 6dp coloured dot, and a status label in the same colour.
 */
class ConnectionPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val dot: View
    private val label: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val horizontalPadding = dp(10)
        val verticalPadding = dp(5)
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

        dot = View(context).apply {
            layoutParams = LayoutParams(dp(6), dp(6))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        addView(dot)

        label = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(5)
            }
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        addView(label)

        setStatus(ConnectionStatus.OFFLINE)
    }

    fun setStatus(status: ConnectionStatus) {
        val color = when (status) {
            ConnectionStatus.CONNECTED -> ContextCompat.getColor(context, R.color.pill_connected)
            ConnectionStatus.RECONNECTING -> ContextCompat.getColor(context, R.color.pill_warning)
            ConnectionStatus.STATION_OFFLINE -> ContextCompat.getColor(context, R.color.pill_warning)
            ConnectionStatus.OFFLINE -> ContextCompat.getColor(context, R.color.pill_offline)
        }
        val text = when (status) {
            ConnectionStatus.CONNECTED -> "Connected"
            ConnectionStatus.RECONNECTING -> "Reconnecting"
            ConnectionStatus.STATION_OFFLINE -> "Station Offline"
            ConnectionStatus.OFFLINE -> "Offline"
        }

        val backgroundAlpha = (0.12f * 255).toInt()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(100).toFloat()
            setColor(Color.argb(backgroundAlpha, Color.red(color), Color.green(color), Color.blue(color)))
        }
        (dot.background as GradientDrawable).setColor(color)
        label.setTextColor(color)
        label.text = text
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
