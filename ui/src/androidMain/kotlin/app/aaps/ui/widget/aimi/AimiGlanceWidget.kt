package app.aaps.ui.widget.aimi

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.fillMaxSize
import app.aaps.core.interfaces.configuration.awaitInitialized
import app.aaps.ui.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The AIMI widget. One widget that adapts to the size it is dragged to (4x2 compact, 4x3 large), drawn as
 * one bitmap by [AimiWidgetRenderer]. It runs in the process of the loop: reading is bounded in time
 * and every failure ends in a card without data, never in an exception that leaves the widget.
 */
class AimiGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = try {
            AimiWidgetDependencies.from(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            provideContent { EmptyContent() }
            return
        }
        if (!deps.config.awaitInitialized(AWAIT_INIT_TIMEOUT_MS)) {
            provideContent { EmptyContent() }
            return
        }
        val data = try {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { deps.loader.load(GlanceAppWidgetManager(context).getAppWidgetId(id)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        provideContent { WidgetContent(data) }
    }

    private companion object {

        const val AWAIT_INIT_TIMEOUT_MS = 5_000L
        const val LOAD_TIMEOUT_MS = 10_000L
    }
}

@Composable
private fun WidgetContent(data: AimiWidgetData?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density
    val bitmap = remember(data, size) {
        AimiWidgetRenderer().render(size.width.value, size.height.value, density, data, texts(context))
    }
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val modifier = GlanceModifier.fillMaxSize().let { if (launchIntent != null) it.clickable(actionStartActivity(launchIntent)) else it }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = data?.let { "${it.bgText} ${it.deltaText}" },
        modifier = modifier
    )
}

/** A card without data: an empty card. */
@Composable
private fun EmptyContent() {
    androidx.glance.layout.Box(modifier = GlanceModifier.fillMaxSize()) {}
}

internal fun texts(context: Context) = AimiWidgetTexts(
    iob = context.getString(R.string.aimi_widget_iob),
    basal = context.getString(R.string.aimi_widget_basal),
    isf = context.getString(R.string.aimi_widget_isf),
    smb = context.getString(R.string.aimi_widget_smb),
    pod = context.getString(R.string.aimi_widget_pod),
    site = context.getString(R.string.aimi_widget_site),
    sensor = context.getString(R.string.aimi_widget_sensor),
    tir = context.getString(R.string.aimi_widget_tir),
    rangeHours = context.getString(R.string.aimi_widget_range_hours, "6"),
    noValue = context.getString(R.string.aimi_widget_no_value),
    prediction = { context.getString(R.string.aimi_widget_prediction, it) },
    lowPossible = context.getString(R.string.aimi_widget_low_possible),
    hours = { context.getString(R.string.aimi_widget_hours_short, it) },
    daysLeft = { context.getString(R.string.aimi_widget_days_left, it) },
    grace = { context.getString(R.string.aimi_widget_grace, it) },
    overUnits = { context.getString(R.string.aimi_widget_over_units, it) },
    units = { context.getString(R.string.aimi_widget_units, it) }
)
