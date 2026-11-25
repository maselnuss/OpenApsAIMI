package app.aaps.core.interfaces.rx.weardata

/**
 * Reads what the AIMI watch face draws beyond the standard fields (see [EventData.TreatmentData.Aimi]).
 *
 * An interface because the data comes from the AIMI widget code in the `ui` module, which the sync module cannot
 * depend on.
 */
interface AimiWatchExtrasProvider {

    /** The extras, or null when they could not be read. Never throws except on cancellation. */
    suspend fun loadWatchExtras(): EventData.TreatmentData.Aimi?
}
