package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Performance dynamic plugin - Loaded from external JAR.
 *
 * Displays JVM metrics, CPU, memory, and resource counts.
 * Uses context.performanceDataProvider for accessing performance data.
 */
class PerformanceDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.performance"
    override val displayName: String = "Performance (Dynamic)"
    override val version: String = "1.0.2"
    override val description: String = "Displays JVM metrics, CPU, memory, and resource counts"
    override val author: String = "Rever AI"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-performance"

    override fun register(context: PluginContext) {
        val dataProvider = context.performanceDataProvider
            ?: throw IllegalStateException("performanceDataProvider not available in context")
        
        context.panelRegistry.registerPanel(PerformanceInfo) { ctx, panelInfo ->
            PerformanceComponent(ctx, panelInfo, dataProvider, null)
        }
    }
}
