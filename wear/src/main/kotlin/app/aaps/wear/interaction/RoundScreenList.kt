package app.aaps.wear.interaction

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.InputDeviceCompat
import androidx.core.view.ViewConfigurationCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Makes a settings list usable on a round screen.
 *
 * Rows at the top and bottom edge are cut by the circle, so the list gets side and end padding it can scroll
 * through ([RecyclerView.setClipToPadding] off: rows are visible in the padding while moving, but can come to rest
 * clear of the edge). The rotary input (crown or bezel) scrolls it, which the list does not do on its own.
 */
internal fun RecyclerView.fitRoundScreen() {
    val side = (resources.displayMetrics.widthPixels * SIDE_FRACTION).toInt()
    val end = (resources.displayMetrics.heightPixels * END_FRACTION).toInt()
    setPadding(side, end, side, end)
    clipToPadding = false
    isFocusable = true
    isFocusableInTouchMode = true
    setOnGenericMotionListener { view, event ->
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) *
                ViewConfigurationCompat.getScaledVerticalScrollFactor(ViewConfiguration.get(view.context), view.context)
            (view as RecyclerView).scrollBy(0, delta.toInt())
            true
        } else false
    }
    requestFocus()
}

private fun MotionEvent.isFromSource(source: Int) = (this.source and source) == source

private const val SIDE_FRACTION = 0.07f
private const val END_FRACTION = 0.16f
