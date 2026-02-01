package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.FileOpenCallback
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

/**
 * ViewModel for Performance panel.
 */
class PerformanceViewModel(
    private val dataProvider: PerformanceDataProvider,
    private val fileOpenCallback: FileOpenCallback?
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
        RESOURCES("Resources")
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

    fun selectTab(tab: Tab) {
        _selectedTab.value = tab
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
        scope.cancel()
    }
}
