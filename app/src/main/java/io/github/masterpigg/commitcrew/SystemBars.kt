package io.github.masterpigg.commitcrew

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Android 15+ draws every app edge to edge, so the status bar, navigation bar and
 * keyboard would otherwise cover the toolbar and the bottom of each screen.
 *
 * Call right after setContentView. The toolbar grows by the status bar height so its
 * brand color sits behind the (white) status bar icons, and the root view is padded
 * for the navigation bar, display cutouts and the on-screen keyboard.
 */
fun ComponentActivity.fitSystemBars(root: View, toolbar: View) {
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

    val toolbarHeight = toolbar.layoutParams.height
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())

        toolbar.updatePadding(top = bars.top)
        if (toolbarHeight > 0) {
            toolbar.updateLayoutParams { height = toolbarHeight + bars.top }
        }
        view.updatePadding(
            left = bars.left,
            right = bars.right,
            bottom = maxOf(bars.bottom, keyboard.bottom)
        )
        WindowInsetsCompat.CONSUMED
    }
}
