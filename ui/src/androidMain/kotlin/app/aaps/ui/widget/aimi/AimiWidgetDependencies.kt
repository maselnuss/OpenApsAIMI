package app.aaps.ui.widget.aimi

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.core.interfaces.di.injectMetroMembers
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

/**
 * The app-graph dependencies of the AIMI widget, fetched on demand. Same pattern as
 * `WidgetDependencies`, but in its own class and its own binding so no existing widget file changes.
 */
class AimiWidgetDependencies {

    @Inject lateinit var loader: AimiWidgetLoader
    @Inject lateinit var config: Config

    companion object {

        fun from(context: Context): AimiWidgetDependencies =
            AimiWidgetDependencies().also { context.injectMetroMembers(it) }
    }
}

/** The member injector entry that `injectMetroMembers` looks up for [AimiWidgetDependencies]. */
@ContributesTo(AppScope::class)
@BindingContainer
object AimiWidgetMemberInjectors {

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(AimiWidgetDependencies::class)
    fun bindAimiWidgetDependencies(injector: MembersInjector<AimiWidgetDependencies>): MembersInjector<*> = injector
}
