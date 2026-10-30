package dev.fastnotes.ink

import android.content.res.Configuration
import android.view.Surface

/** ENote's bitmap orientation follows the physical display, not a narrow split-screen pane. */
internal fun panelOrientation(naturalWidth: Int, naturalHeight: Int, rotation: Int): Int {
    val quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    val portrait = (naturalHeight >= naturalWidth) != quarterTurn
    return if (portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
}
