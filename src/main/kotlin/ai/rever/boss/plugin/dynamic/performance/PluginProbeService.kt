package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.ConsoleLogsAPI
import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginLogMatcher
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import javax.management.ObjectName

/**
 * Per-plugin probing: memory footprint, leak signals, and log attribution.
 *
 * In-process plugins share the host JVM heap, so per-plugin memory is
 * attributed by code package: the JVM's live-object class histogram (the
 * DiagnosticCommand MBean — same data as `jcmd GC.class_histogram`) filtered
 * to classes under the plugin's package prefix. Sampling forces a full GC —
 * it is on-demand, never periodic.
 *
 * One instance per plugin activation, shared by the panel UI, the MCP tools,
 * and (via [ProbeApiBridge]) other plugins such as the Tool Evolver — so the
 * sample history used by leak heuristics covers every consumer's samples.
 */
class PluginProbeService(private val context: PluginContext) {

    data class ClassStat(
        val className: String,
        val instances: Long,
        val bytes: Long,
    )

    data class MemorySample(
        val atMs: Long,
        val pluginId: String,
        val packagePrefix: String,
        val classCount: Int,
        val instanceCount: Long,
        val totalBytes: Long,
        val topClasses: List<ClassStat>,
    )

    private val historyByPlugin = ConcurrentHashMap<String, List<MemorySample>>()

    /** Host plugin-management API; null on old hosts or out-of-process runs. */
    val loader: PluginLoaderDelegate?
        get() = context.getPluginAPI(PluginLoaderDelegate::class.java)

    fun listPlugins(): List<LoadedPluginInfo> =
        (loader?.getLoadedPlugins() ?: emptyList()).sortedBy { it.displayName.lowercase() }

    fun findPlugin(pluginId: String): LoadedPluginInfo? =
        listPlugins().firstOrNull { it.pluginId == pluginId }

    fun isLoaded(pluginId: String): Boolean = loader?.isPluginLoaded(pluginId) ?: false

    fun instanceCount(pluginId: String): Int = loader?.getRunningInstanceCount(pluginId) ?: 0

    /**
     * Sample live heap objects attributed to [pluginId] and record the sample.
     * Forces a full GC. Null when the JVM refuses the histogram.
     */
    fun sample(pluginId: String, top: Int = 12): MemorySample? {
        val histogram = classHistogram() ?: return null
        val prefix = findPlugin(pluginId)?.let { packagePrefixFor(it) } ?: pluginId
        var classes = 0
        var instances = 0L
        var bytes = 0L
        val stats = ArrayList<ClassStat>()
        histogram.lineSequence().forEach { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 4 || !tokens[0].endsWith(":")) return@forEach
            val inst = tokens[1].toLongOrNull() ?: return@forEach
            val b = tokens[2].toLongOrNull() ?: return@forEach
            val name = tokens[3]
            if (!name.startsWith(prefix)) return@forEach
            classes++
            instances += inst
            bytes += b
            stats += ClassStat(name, inst, b)
        }
        stats.sortByDescending { it.bytes }
        val sample = MemorySample(
            atMs = System.currentTimeMillis(),
            pluginId = pluginId,
            packagePrefix = prefix,
            classCount = classes,
            instanceCount = instances,
            totalBytes = bytes,
            topClasses = stats.take(top),
        )
        historyByPlugin.compute(pluginId) { _, old ->
            ((old ?: emptyList()) + sample).takeLast(HISTORY_CAPACITY)
        }
        return sample
    }

    /** Samples recorded for [pluginId] this session, oldest first. */
    fun history(pluginId: String): List<MemorySample> =
        historyByPlugin[pluginId] ?: emptyList()

    /**
     * Leak heuristics over the recorded history plus host state. Returns
     * human-readable warnings; empty means nothing suspicious observed.
     */
    fun leakSignals(pluginId: String): List<String> {
        val history = history(pluginId)
        val signals = mutableListOf<String>()
        val instances = instanceCount(pluginId)
        if (!isLoaded(pluginId) && (history.lastOrNull()?.instanceCount ?: 0L) > 0L) {
            signals += "Plugin is unloaded but ${history.last().instanceCount} of its objects remain on the heap — likely leaked classloader."
        }
        if (instances > 1) {
            signals += "$instances live instances open — stale instances can pin the old version after a reload."
        }
        if (history.size >= 3) {
            val monotonic = history.zipWithNext().all { (a, b) -> b.totalBytes >= a.totalBytes }
            val first = history.first().totalBytes
            val last = history.last().totalBytes
            if (monotonic && first > 0 && last > first * 3 / 2) {
                signals += "Heap use grew every sample (${humanBytes(first)} → ${humanBytes(last)} across ${history.size} samples) — possible leak."
            }
        }
        return signals
    }

    /**
     * Recent host log lines attributed to the plugin — via the Console plugin's
     * shared attribution flow when installed, else the same [PluginLogMatcher]
     * heuristic over the raw host log stream.
     */
    fun recentLogs(pluginId: String, displayName: String?, limit: Int = 15): List<String> {
        val entries = context.getPluginAPI(ConsoleLogsAPI::class.java)
            ?.logsForPlugin(pluginId, displayName)?.value
            ?: context.logDataProvider?.logs?.value
                ?.let { PluginLogMatcher.filter(it, pluginId, displayName) }
            ?: return emptyList()
        return entries
            .takeLast(limit.coerceIn(1, 200))
            .map { "${it.formatTimestamp()} ${it.message.take(400)}" }
    }

    /** Full live-object class histogram, or null when the MBean is unavailable. */
    private fun classHistogram(): String? = try {
        val server = ManagementFactory.getPlatformMBeanServer()
        val name = ObjectName("com.sun.management:type=DiagnosticCommand")
        server.invoke(
            name,
            "gcClassHistogram",
            arrayOf<Any?>(null),
            arrayOf("[Ljava.lang.String;"),
        ) as? String
    } catch (_: Exception) {
        null
    }

    /**
     * The package prefix used to attribute heap objects to [info]: the package
     * of the plugin's mainClass (read from its jar manifest), falling back to
     * the pluginId, which by house convention mirrors the code package.
     */
    private fun packagePrefixFor(info: LoadedPluginInfo): String =
        manifestMainClass(File(info.jarPath))
            ?.substringBeforeLast('.', "")
            ?.takeIf { it.isNotEmpty() }
            ?: info.pluginId

    /** Best-effort `mainClass` from a jar's plugin manifest; null on any failure. */
    private fun manifestMainClass(jar: File): String? = try {
        if (!jar.isFile) null
        else JarFile(jar).use { jf ->
            jf.getEntry("META-INF/boss-plugin/plugin.json")?.let { entry ->
                val text = jf.getInputStream(entry).bufferedReader().readText()
                Regex("\"mainClass\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val HISTORY_CAPACITY = 32

        fun humanBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.2f GiB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.2f MiB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.1f KiB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }
    }
}
