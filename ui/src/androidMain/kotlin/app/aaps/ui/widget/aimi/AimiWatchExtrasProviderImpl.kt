package app.aaps.ui.widget.aimi

import app.aaps.core.interfaces.rx.weardata.AimiWatchExtrasProvider
import app.aaps.core.interfaces.rx.weardata.EventData
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Gives the sync module the data of the AIMI watch face. It is read by the same code as the AIMI home screen
 * widget, so the watch shows exactly what the widget shows.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class AimiWatchExtrasProviderImpl(private val loader: AimiWidgetLoader) : AimiWatchExtrasProvider {

    override suspend fun loadWatchExtras(): EventData.TreatmentData.Aimi? = loader.loadWatchExtras()
}
