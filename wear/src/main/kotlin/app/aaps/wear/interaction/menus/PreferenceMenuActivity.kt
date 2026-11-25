package app.aaps.wear.interaction.menus

import android.content.Intent
import android.os.Bundle
import app.aaps.wear.R
import app.aaps.wear.interaction.WatchfaceConfigurationActivity
import app.aaps.wear.interaction.utils.MenuListActivity
import app.aaps.wear.watchfaces.utils.WatchfaceViewAdapter.Companion.SelectedWatchFace

class PreferenceMenuActivity : MenuListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTitle(R.string.menu_settings)
        super.onCreate(savedInstanceState)
    }

    override fun provideElements(): List<MenuItem> =
        ArrayList<MenuItem>().apply {
            add(MenuItem(R.drawable.ic_display, getString(R.string.pref_display_settings)))
            add(MenuItem(R.drawable.ic_graph, getString(R.string.pref_graph_settings)))
            add(MenuItem(R.drawable.ic_interface, getString(R.string.pref_interface_settings)))
            add(MenuItem(R.drawable.ic_tile_settings, getString(R.string.pref_tile_settings)))
            add(MenuItem(R.drawable.ic_complication, getString(R.string.pref_complication_settings)))
            add(MenuItem(R.drawable.ic_others, getString(R.string.pref_others_settings)))
            // The Custom watch face's own settings - what its picture shows, not where it is shown.
            // Offered on every watch, whatever its make: these settings are what the Watch Face
            // Format face draws from, and on a watch whose firmware no longer runs code-based faces
            // this menu is the only way to reach them. Digital and Circle are not offered here:
            // neither exists in the Watch Face Format face, and a watch that can still run them can
            // reach their settings by long-pressing the face.
            add(MenuItem(R.drawable.ic_watchface_aimi, getString(R.string.label_watchface_custom)))
        }

    override fun doAction(position: String) {
        when (position) {
            getString(R.string.pref_display_settings)      -> startActivity(Intent(this, WatchfaceConfigurationActivity::class.java).apply {
                putExtra(getString(R.string.key_preference_id), R.xml.display_preferences)
            })

            getString(R.string.pref_graph_settings)        -> startActivity(Intent(this, WatchfaceConfigurationActivity::class.java).apply {
                putExtra(getString(R.string.key_preference_id), R.xml.graph_preferences)
            })

            getString(R.string.pref_interface_settings)    -> startActivity(Intent(this, WatchfaceConfigurationActivity::class.java).apply {
                putExtra(getString(R.string.key_preference_id), R.xml.interface_preferences)
            })

            getString(R.string.pref_tile_settings)         -> startActivity(Intent(this, TileMenuActivity::class.java))

            getString(R.string.pref_complication_settings) -> startActivity(Intent(this, WatchfaceConfigurationActivity::class.java).apply {
                putExtra(getString(R.string.key_preference_id), R.xml.complication_preferences)
            })

            getString(R.string.pref_others_settings)       -> startActivity(Intent(this, WatchfaceConfigurationActivity::class.java).apply {
                putExtra(getString(R.string.key_preference_id), R.xml.others_preferences)
            })

            getString(R.string.label_watchface_custom)     -> startActivity(Intent(this, WatchfaceConfigurationActivity::class.java).apply {
                putExtra(getString(R.string.key_selected_watchface), SelectedWatchFace.CUSTOM.ordinal)
            })
        }
    }
}
