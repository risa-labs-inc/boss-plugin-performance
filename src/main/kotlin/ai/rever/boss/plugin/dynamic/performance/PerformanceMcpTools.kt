package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PerformanceDataProvider

/**
 * MCP tools contributed by the Performance plugin: a live JVM/resource snapshot
 * and a GC trigger. Registered in [PerformanceDynamicPlugin.register]; removed
 * automatically on disable/unload.
 */
internal class PerformanceMcpToolProvider(
    override val providerId: String,
    private val perf: PerformanceDataProvider,
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
    )
}
