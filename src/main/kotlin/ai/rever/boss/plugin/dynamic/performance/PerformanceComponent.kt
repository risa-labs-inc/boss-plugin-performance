package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.FileOpenCallback
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle.Callbacks

/**
 * Performance panel component.
 * Shows real-time JVM performance metrics with tabs.
 */
class PerformanceComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val dataProvider: PerformanceDataProvider,
    private val fileOpenCallback: FileOpenCallback?,
    private val networkSampler: NetworkSampler? = null,
    private val probeService: PluginProbeService? = null
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = PerformanceViewModel(dataProvider, fileOpenCallback, networkSampler, probeService)

    init {
        lifecycle.subscribe(
            callbacks = object : Callbacks {
                override fun onDestroy() {
                    viewModel.dispose()
                }
            }
        )
    }

    @Composable
    override fun Content() {
        BossTheme {
            PerformanceView(viewModel)
        }
    }
}
