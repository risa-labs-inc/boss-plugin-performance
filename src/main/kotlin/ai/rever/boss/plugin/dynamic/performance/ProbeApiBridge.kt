package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.PluginContext
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Publishes [PluginProbeService] as the cross-plugin `PluginMemoryProbeAPI`
 * (api ≥ 1.0.64) so other plugins — notably the Tool Evolver's probe — can use
 * the Performance plugin as their memory-sampling source and share one sample
 * history.
 *
 * Deliberately reflection-only: the host's binary-compatibility validator
 * pre-scans every class in the jar against the runtime api layer and disables
 * the plugin on any unresolved reference, so the new interface must not be
 * referenced statically. When the runtime api layer predates the interface,
 * [register] returns false and the rest of the plugin works unaffected.
 */
internal object ProbeApiBridge {

    private const val API_INTERFACE = "ai.rever.boss.plugin.api.PluginMemoryProbeAPI"
    private const val SAMPLE_CLASS = "ai.rever.boss.plugin.api.PluginMemorySampleData"
    private const val CLASS_STAT_CLASS = "ai.rever.boss.plugin.api.PluginClassStatData"

    /** True when the runtime api layer ships the interface and registration succeeded. */
    fun register(context: PluginContext, service: PluginProbeService): Boolean = try {
        val loader = ProbeApiBridge::class.java.classLoader
        val iface = Class.forName(API_INTERFACE, false, loader)
        val sampleCtor = Class.forName(SAMPLE_CLASS, true, loader)
            .constructors.first { it.parameterCount == 7 }
        val statCtor = Class.forName(CLASS_STAT_CLASS, true, loader)
            .constructors.first { it.parameterCount == 3 }

        fun toApiSample(s: PluginProbeService.MemorySample): Any = sampleCtor.newInstance(
            s.atMs, s.pluginId, s.packagePrefix, s.classCount, s.instanceCount, s.totalBytes,
            s.topClasses.map { statCtor.newInstance(it.className, it.instances, it.bytes) },
        )

        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "sampleMemory" -> service.sample(args[0] as String, args[1] as Int)?.let(::toApiSample)
                "sampleHistory" -> service.history(args[0] as String).map(::toApiSample)
                "leakSignals" -> service.leakSignals(args[0] as String)
                "toString" -> "PluginMemoryProbeAPI(Performance plugin)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException("PluginMemoryProbeAPI.${method.name}")
            }
        }
        context.registerPluginAPI(Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler))
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
