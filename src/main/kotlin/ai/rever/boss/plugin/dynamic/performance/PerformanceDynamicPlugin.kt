package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory

/**
 * Performance dynamic plugin - Loaded from external JAR.
 *
 * Displays JVM metrics, CPU, memory, network activity, resource counts, and
 * per-plugin memory probes. Uses context.performanceDataProvider for host
 * metrics; network and plugin probing are sampled by the plugin itself.
 */
class PerformanceDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.performance"
    override val displayName: String = "Performance (Dynamic)"
    override val version: String = "1.2.0"
    override val description: String = "Displays JVM metrics, CPU, memory, network, and resource counts"
    override val author: String = "Rever AI"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-performance"

    private val logger = BossLogger.forComponent("PerformanceDynamicPlugin")
    private var networkSampler: NetworkSampler? = null

    override fun register(context: PluginContext) {
        val dataProvider = context.performanceDataProvider
            ?: throw IllegalStateException("performanceDataProvider not available in context")

        val probeService = PluginProbeService(context)
        val sampler = NetworkSampler().also { networkSampler = it }

        context.panelRegistry.registerPanel(PerformanceInfo) { ctx, panelInfo ->
            PerformanceComponent(ctx, panelInfo, dataProvider, null, sampler, probeService)
        }
        // Contribute performance MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(
            PerformanceMcpToolProvider(pluginId, dataProvider, sampler, probeService)
        )
        // Publish the probe as the cross-plugin PluginMemoryProbeAPI (api ≥ 1.0.64,
        // consumed by the Tool Evolver); false = the runtime api layer predates it.
        if (!ProbeApiBridge.register(context, probeService)) {
            logger.info(LogCategory.SYSTEM, "PluginMemoryProbeAPI not registered — runtime api layer predates it")
        }
    }

    override fun dispose() {
        networkSampler?.shutdown()
        networkSampler = null
    }
}
