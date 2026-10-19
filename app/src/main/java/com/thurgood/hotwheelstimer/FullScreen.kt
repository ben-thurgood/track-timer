package com.thurgood.hotwheelstimer

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the status bar and the Android navigation buttons (swipe from an edge to show them briefly)
 * and pads [root] so nothing sits under a camera hole-punch or notch.
 * Call from onCreate, and call [hideSystemBars] again from onWindowFocusChanged.
 */
fun Activity.enterFullScreen(root: View) {
    hideSystemBars()
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        v.setPadding(c.left, c.top, c.right, c.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

fun Activity.hideSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
