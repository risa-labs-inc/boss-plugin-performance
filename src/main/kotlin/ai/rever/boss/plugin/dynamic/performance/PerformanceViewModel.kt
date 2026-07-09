package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.FileOpenCallback
import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.PerformanceSettingsData
import ai.rever.boss.plugin.api.PerformanceSnapshotData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for Performance panel.
 */
class PerformanceViewModel(
    private val dataProvider: PerformanceDataProvider,
    private val fileOpenCallback: FileOpenCallback?,
    private val networkSampler: NetworkSampler? = null,
    private val probeService: PluginProbeService? = null
) {
    private val logger = BossLogger.forComponent("PerformanceViewModel")
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val currentSnapshot: StateFlow<PerformanceSnapshotData?> = dataProvider.currentSnapshot
    val history: StateFlow<List<PerformanceSnapshotData>> = dataProvider.history
    val settings: StateFlow<PerformanceSettingsData> = dataProvider.settings

    /**
     * Selected tab in panel.
     */
    enum class Tab(val displayName: String) {
        OVERVIEW("Overview"),
        MEMORY("Heap & Pools"),
        CPU("CPU & Threads"),
        TIMINGS("GC Timings"),
        RESOURCES("Resources"),
        PROCESSES("Processes"),
        NETWORK("Network"),
        PLUGINS("Plugins")
    }

    private val _selectedTab = MutableStateFlow(Tab.OVERVIEW)
    val selectedTab: StateFlow<Tab> = _selectedTab.asStateFlow()

    // Export state
    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    // ---------------------------------------------------------------- network

    private val emptyNetworkSnapshot = MutableStateFlow<NetworkSampler.NetworkSnapshot?>(null)
    private val emptyRateHistory = MutableStateFlow<List<NetworkSampler.RatePoint>>(emptyList())

    val networkSnapshot: StateFlow<NetworkSampler.NetworkSnapshot?> =
        networkSampler?.snapshot ?: emptyNetworkSnapshot.asStateFlow()
    val networkRateHistory: StateFlow<List<NetworkSampler.RatePoint>> =
        networkSampler?.rateHistory ?: emptyRateHistory.asStateFlow()

    // ----------------------------------------------------------- plugin probe

    private val _plugins = MutableStateFlow<List<LoadedPluginInfo>>(emptyList())
    val plugins: StateFlow<List<LoadedPluginInfo>> = _plugins.asStateFlow()

    private val _selectedPlugin = MutableStateFlow<LoadedPluginInfo?>(null)
    val selectedPlugin: StateFlow<LoadedPluginInfo?> = _selectedPlugin.asStateFlow()

    private val _pluginSamples = MutableStateFlow<List<PluginProbeService.MemorySample>>(emptyList())
    val pluginSamples: StateFlow<List<PluginProbeService.MemorySample>> = _pluginSamples.asStateFlow()

    private val _pluginLeakSignals = MutableStateFlow<List<String>>(emptyList())
    val pluginLeakSignals: StateFlow<List<String>> = _pluginLeakSignals.asStateFlow()

    private val _pluginLogs = MutableStateFlow<List<String>>(emptyList())
    val pluginLogs: StateFlow<List<String>> = _pluginLogs.asStateFlow()

    private val _pluginInstanceCount = MutableStateFlow(0)
    val pluginInstanceCount: StateFlow<Int> = _pluginInstanceCount.asStateFlow()

    private val _isSamplingMemory = MutableStateFlow(false)
    val isSamplingMemory: StateFlow<Boolean> = _isSamplingMemory.asStateFlow()

    /** Whether the host exposes the plugin list at all (in-process, new host). */
    val probeAvailable: Boolean get() = probeService?.loader != null

    init {
        networkSampler?.acquire()
    }

    fun selectTab(tab: Tab) {
        _selectedTab.value = tab
        if (tab == Tab.PLUGINS) refreshPlugins()
    }

    fun refreshPlugins() {
        val probe = probeService ?: return
        scope.launch {
            _plugins.value = withContext(Dispatchers.IO) { probe.listPlugins() }
            _selectedPlugin.value?.let { selected ->
                _plugins.value.firstOrNull { it.pluginId == selected.pluginId }?.let { refreshed ->
                    _selectedPlugin.value = refreshed
                }
            }
        }
    }

    fun selectPlugin(plugin: LoadedPluginInfo?) {
        _selectedPlugin.value = plugin
        if (plugin == null) {
            _pluginSamples.value = emptyList()
            _pluginLeakSignals.value = emptyList()
            _pluginLogs.value = emptyList()
            _pluginInstanceCount.value = 0
            return
        }
        val probe = probeService ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val samples = probe.history(plugin.pluginId)
                val signals = probe.leakSignals(plugin.pluginId)
                val logs = probe.recentLogs(plugin.pluginId, plugin.displayName)
                val instances = probe.instanceCount(plugin.pluginId)
                withContext(Dispatchers.Main) {
                    _pluginSamples.value = samples
                    _pluginLeakSignals.value = signals
                    _pluginLogs.value = logs
                    _pluginInstanceCount.value = instances
                }
            }
        }
    }

    /** Sample the selected plugin's heap footprint. Forces a full GC. */
    fun samplePluginMemory() {
        val probe = probeService ?: return
        val plugin = _selectedPlugin.value ?: return
        if (_isSamplingMemory.value) return
        scope.launch {
            _isSamplingMemory.value = true
            try {
                withContext(Dispatchers.IO) {
                    probe.sample(plugin.pluginId)
                    val samples = probe.history(plugin.pluginId)
                    val signals = probe.leakSignals(plugin.pluginId)
                    val logs = probe.recentLogs(plugin.pluginId, plugin.displayName)
                    val instances = probe.instanceCount(plugin.pluginId)
                    withContext(Dispatchers.Main) {
                        _pluginSamples.value = samples
                        _pluginLeakSignals.value = signals
                        _pluginLogs.value = logs
                        _pluginInstanceCount.value = instances
                    }
                }
            } finally {
                _isSamplingMemory.value = false
            }
        }
    }

    fun requestGC() {
        dataProvider.requestGC()
    }

    fun exportMetrics() {
        scope.launch {
            _isExporting.value = true
            _exportError.value = null

            dataProvider.exportMetrics()
                .onSuccess { filePath ->
                    // Show relative path
                    val homeDir = System.getProperty("user.home")
                    val displayPath = if (filePath.startsWith(homeDir)) {
                        "~" + filePath.removePrefix(homeDir)
                    } else {
                        filePath.substringAfterLast("/")
                    }
                    _exportResult.value = displayPath

                    // Open the exported file
                    fileOpenCallback?.openFile(filePath)
                }
                .onFailure { error ->
                    _exportError.value = error.message ?: "Failed to export metrics"
                }

            _isExporting.value = false
        }
    }

    fun clearExportResult() {
        _exportResult.value = null
    }

    fun clearExportError() {
        _exportError.value = null
    }

    fun updateSettings(newSettings: PerformanceSettingsData) {
        scope.launch {
            dataProvider.updateSettings(newSettings)
        }
    }

    fun dispose() {
        networkSampler?.release()
        scope.cancel()
    }
}
