package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PerformanceDataProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP tools contributed by the Performance plugin: a live JVM/resource snapshot,
 * a GC trigger, network activity, and per-plugin memory sampling. Registered in
 * [PerformanceDynamicPlugin.register]; removed automatically on disable/unload.
 */
internal class PerformanceMcpToolProvider(
    override val providerId: String,
    private val perf: PerformanceDataProvider,
    private val network: NetworkSampler,
    private val probe: PluginProbeService,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "performance_snapshot",
            description = "Current JVM/resource metrics: heap, CPU load, threads, and BOSS resource counts.",
            handler = McpToolHandler {
                val s = perf.currentSnapshot.value
                    ?: return@McpToolHandler McpToolResult("No performance snapshot available yet.")
                McpToolResult(
                    buildString {
                        appendLine("Heap: %.0f / %.0f MB (%.0f%%)".format(s.heapUsedMB, s.heapMaxMB, s.heapUsagePercent))
                        appendLine("Non-heap: %.0f MB".format(s.nonHeapUsedMB))
                        appendLine("CPU: process %.0f%%, system %.0f%%".format(s.processLoadPercent, s.systemLoadPercent))
                        appendLine("Threads: ${s.activeThreadCount} (procs ${s.availableProcessors})")
                        appendLine("GC: ${s.gcCollectionCount} collections, ${s.gcCollectionTimeMs} ms")
                        append("Resources: windows=${s.windowCount} panels=${s.panelCount} browserTabs=${s.browserTabCount} terminals=${s.terminalCount} editors=${s.editorTabCount}")
                    }
                )
            },
        ),
        McpToolDefinition(
            name = "performance_gc",
            description = "Request a JVM garbage collection.",
            readOnly = false,
            handler = McpToolHandler {
                perf.requestGC()
                McpToolResult("Requested GC.")
            },
        ),
        McpToolDefinition(
            name = "performance_network",
            description = "Network activity: total and per-interface RX/TX rates and counters, plus the BOSS process's open TCP connections.",
            handler = McpToolHandler {
                val s = withContext(Dispatchers.IO) { network.snapshotNow() }
                if (s == null || !s.available) {
                    return@McpToolHandler McpToolResult("Network counters are not available on this platform.")
                }
                McpToolResult(
                    buildString {
                        appendLine("Throughput: down ${NetworkSampler.formatRate(s.totalRxRateBps)}, up ${NetworkSampler.formatRate(s.totalTxRateBps)}")
                        appendLine("Totals since boot: down ${NetworkSampler.formatBytes(s.totalRxBytes)}, up ${NetworkSampler.formatBytes(s.totalTxBytes)}")
                        appendLine()
                        appendLine("Interfaces (with traffic):")
                        s.interfaces.filter { it.rxBytes + it.txBytes > 0 }.forEach { i ->
                            appendLine(
                                "  ${i.name.padEnd(8)} rx=${NetworkSampler.formatBytes(i.rxBytes)} tx=${NetworkSampler.formatBytes(i.txBytes)}" +
                                    (if (i.rxRateBps + i.txRateBps > 0) " | ${NetworkSampler.formatRate(i.rxRateBps)} down, ${NetworkSampler.formatRate(i.txRateBps)} up" else "")
                            )
                        }
                        appendLine()
                        if (!s.connectionsSampled) {
                            append("TCP connections: unavailable (lsof not found)")
                        } else {
                            val byState = s.connections.groupingBy { it.state.ifEmpty { "OTHER" } }.eachCount()
                            appendLine("TCP connections (BOSS process): ${s.connections.size} — " +
                                byState.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" })
                            s.connections.take(30).forEach { c ->
                                appendLine("  ${if (c.remote.isNotEmpty()) "${c.local} -> ${c.remote}" else c.local} ${c.state}")
                            }
                            if (s.connections.size > 30) append("  … +${s.connections.size - 30} more")
                        }
                    }.trimEnd()
                )
            },
        ),
        McpToolDefinition(
            name = "performance_plugin_memory",
            description = "Sample one plugin's live-heap footprint (class histogram filtered to its code package — forces a GC): totals, top classes, sample history, and leak signals. The same probe the Performance panel's Plugins tab and the Tool Evolver use.",
            inputSchema = """{"type":"object","properties":{
                "plugin_id":{"type":"string","description":"Plugin id, e.g. ai.rever.boss.plugin.dynamic.bookmarks"},
                "top":{"type":"integer","description":"Max classes to list (default 12)"}
            },"required":["plugin_id"]}""".trimIndent(),
            handler = McpToolHandler { args ->
                val pluginId = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                withContext(Dispatchers.IO) {
                    val info = probe.findPlugin(pluginId)
                    if (info == null && !probe.isLoaded(pluginId)) {
                        return@withContext McpToolResult("No loaded plugin with id $pluginId", isError = true)
                    }
                    val sample = probe.sample(pluginId, args.int("top") ?: 12)
                        ?: return@withContext McpToolResult("Memory histogram unavailable on this JVM", isError = true)
                    val history = probe.history(pluginId)
                    val signals = probe.leakSignals(pluginId)
                    McpToolResult(
                        buildString {
                            info?.let { appendLine("${it.displayName} (${it.pluginId}) v${it.version}") }
                            appendLine("Memory (live objects under ${sample.packagePrefix}, GC forced):")
                            appendLine("  ${sample.instanceCount} instances of ${sample.classCount} classes, ${PluginProbeService.humanBytes(sample.totalBytes)} total")
                            sample.topClasses.forEach { c ->
                                appendLine("  ${PluginProbeService.humanBytes(c.bytes).padStart(10)}  ${c.instances.toString().padStart(8)}x  ${c.className}")
                            }
                            if (history.size > 1) {
                                appendLine("History (${history.size} samples): " +
                                    history.takeLast(6).joinToString(" -> ") { PluginProbeService.humanBytes(it.totalBytes) })
                            }
                            append(if (signals.isEmpty()) "Leak signals: none observed"
                            else "Leak signals:\n" + signals.joinToString("\n") { "  ! $it" })
                        }.trimEnd()
                    )
                }
            },
        ),
    )
}
