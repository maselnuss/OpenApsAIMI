package app.aaps.ui.widget.aimi

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Receiver of the AIMI widget. Injects nothing, see [AimiWidgetDependencies]. */
class AimiWidget : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = AimiGlanceWidget()
}
