package app.aaps.wear.interaction.utils

object Constants {

    const val SECOND_IN_MS: Long = 1000
    const val MINUTE_IN_MS: Long = 60000
    const val HOUR_IN_MS: Long = 3600000
    const val DAY_IN_MS: Long = 86400000
    /** The age after which a reading counts as old: the limit the phone strikes the value through at. */
    const val STALE_MS = MINUTE_IN_MS * app.aaps.core.data.configuration.Constants.OLD_BG_MINUTES
}