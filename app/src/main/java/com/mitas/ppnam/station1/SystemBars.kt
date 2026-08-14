package com.mitas.ppnam.station1

import android.app.Activity
import androidx.core.view.WindowCompat

/**
 * Forces light (white) status bar icons, matching this app's always-dark background.
 * enableEdgeToEdge()'s own light/dark heuristic doesn't resolve consistently across every
 * screen, leaving status bar icons unreadable on some activities - this makes it explicit.
 */
fun Activity.forceLightStatusBarIcons() {
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
}
