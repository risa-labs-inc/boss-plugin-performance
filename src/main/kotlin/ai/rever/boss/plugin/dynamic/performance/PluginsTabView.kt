package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Plugins tab: probe any installed plugin — heap footprint by class (Sample
 * Memory, like the Tool Evolver's probe), leak signals, and recent logs.
 */
@Composable
internal fun PluginsTab(viewModel: PerformanceViewModel) {
    val selected by viewModel.selectedPlugin.collectAsState()

    if (!viewModel.probeAvailable) {
        ProbeEmptyState("Plugin probing is unavailable — the host does not expose the plugin list here")
        return
    }

    if (selected == null) {
        PluginListView(viewModel)
    } else {
        PluginProbeDetail(viewModel, selected!!)
    }
}

@Composable
private fun PluginListView(viewModel: PerformanceViewModel) {
    val plugins by viewModel.plugins.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${plugins.size} installed plugins",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = BossThemeColors.TextPrimary
            )
            IconButton(onClick = { viewModel.refreshPlugins() }, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(14.dp),
                    tint = BossThemeColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (plugins.isEmpty()) {
            ProbeEmptyState("No plugins reported by the host")
            return
        }

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .lazyListScrollbar(
                    listState = listState,
                    direction = Orientation.Vertical,
                    config = getPanelScrollbarConfig()
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(plugins, key = { it.pluginId }) { plugin ->
                PluginRow(plugin) { viewModel.selectPlugin(plugin) }
            }
        }
    }
}

@Composable
private fun PluginRow(plugin: LoadedPluginInfo, onClick: () -> Unit) {
    val healthColor = when {
        !plugin.isEnabled -> BossThemeColors.TextSecondary
        !plugin.healthy -> BossThemeColors.ErrorColor
        else -> BossThemeColors.SuccessColor
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 0.dp,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).background(healthColor, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plugin.displayName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BossThemeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("v${plugin.version}", fontSize = 9.sp, color = BossThemeColors.TextSecondary)
                }
                Text(
                    plugin.pluginId,
                    fontSize = 9.sp,
                    color = BossThemeColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!plugin.isEnabled) {
                StatusChip("disabled", BossThemeColors.TextSecondary)
            } else if (!plugin.healthy) {
                StatusChip("unhealthy", BossThemeColors.ErrorColor)
            }
        }
    }
}

@Composable
private fun PluginProbeDetail(viewModel: PerformanceViewModel, plugin: LoadedPluginInfo) {
    val samples by viewModel.pluginSamples.collectAsState()
    val leakSignals by viewModel.pluginLeakSignals.collectAsState()
    val logs by viewModel.pluginLogs.collectAsState()
    val instances by viewModel.pluginInstanceCount.collectAsState()
    val isSampling by viewModel.isSamplingMemory.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with back navigation
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.selectPlugin(null) }, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(14.dp),
                    tint = BossThemeColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${plugin.displayName} v${plugin.version}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = BossThemeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    plugin.pluginId,
                    fontSize = 9.sp,
                    color = BossThemeColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .lazyListScrollbar(
                    listState = listState,
                    direction = Orientation.Vertical,
                    config = getPanelScrollbarConfig()
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = BossThemeColors.SurfaceColor,
                    elevation = 0.dp,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusChip(if (plugin.isEnabled) "enabled" else "disabled",
                            if (plugin.isEnabled) BossThemeColors.SuccessColor else BossThemeColors.TextSecondary)
                        StatusChip(if (plugin.healthy) "healthy" else "unhealthy",
                            if (plugin.healthy) BossThemeColors.SuccessColor else BossThemeColors.ErrorColor)
                        StatusChip("$instances open", BossThemeColors.AccentColor)
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { viewModel.samplePluginMemory() },
                        enabled = !isSampling,
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.AccentColor),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        if (isSampling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = BossThemeColors.TextPrimary
                            )
                        } else {
                            Icon(Icons.Outlined.Memory, "Sample", modifier = Modifier.size(14.dp), tint = BossThemeColors.TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sample Memory", color = BossThemeColors.TextPrimary, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("forces a full GC", fontSize = 9.sp, color = BossThemeColors.TextSecondary)
                }
            }

            if (leakSignals.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BossThemeColors.WarningColor.copy(alpha = 0.12f),
                        elevation = 0.dp,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Leak Signals", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = BossThemeColors.WarningColor)
                            Spacer(modifier = Modifier.height(4.dp))
                            leakSignals.forEach { signal ->
                                Text("⚠ $signal", fontSize = 10.sp, color = BossThemeColors.TextPrimary, modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                    }
                }
            }

            item {
                MemorySamplesCard(samples)
            }

            item {
                LogsCard(logs)
            }
        }
    }
}

@Composable
private fun MemorySamplesCard(samples: List<PluginProbeService.MemorySample>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 0.dp,
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Memory Footprint", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossThemeColors.TextPrimary)

            val latest = samples.lastOrNull()
            if (latest == null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "No samples yet — Sample Memory reports live objects under the plugin's code package",
                    fontSize = 10.sp,
                    color = BossThemeColors.TextSecondary
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(
                        PluginProbeService.humanBytes(latest.totalBytes),
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BossThemeColors.AccentColor
                    )
                    Text("Total", fontSize = 9.sp, color = BossThemeColors.TextSecondary)
                }
                Column {
                    Text(
                        "${latest.instanceCount}",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BossThemeColors.TextPrimary
                    )
                    Text("Instances", fontSize = 9.sp, color = BossThemeColors.TextSecondary)
                }
                Column {
                    Text(
                        "${latest.classCount}",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BossThemeColors.TextPrimary
                    )
                    Text("Classes", fontSize = 9.sp, color = BossThemeColors.TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "under ${latest.packagePrefix}",
                fontSize = 9.sp,
                color = BossThemeColors.TextSecondary,
                fontFamily = FontFamily.Monospace
            )

            if (samples.size > 1) {
                Spacer(modifier = Modifier.height(6.dp))
                val trend = samples.takeLast(6)
                Text(
                    "History: " + trend.joinToString(" → ") { PluginProbeService.humanBytes(it.totalBytes) },
                    fontSize = 9.sp,
                    color = BossThemeColors.TextSecondary
                )
            }

            if (latest.topClasses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = BossThemeColors.BorderColor, thickness = 1.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Top classes", fontSize = 10.sp, color = BossThemeColors.TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                latest.topClasses.forEach { stat ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(
                            PluginProbeService.humanBytes(stat.bytes),
                            fontSize = 9.sp,
                            color = BossThemeColors.AccentColor,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(62.dp)
                        )
                        Text(
                            "${stat.instances}x",
                            fontSize = 9.sp,
                            color = BossThemeColors.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(50.dp)
                        )
                        Text(
                            stat.className.substringAfterLast('.'),
                            fontSize = 9.sp,
                            color = BossThemeColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsCard(logs: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 0.dp,
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Recent Logs", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossThemeColors.TextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            if (logs.isEmpty()) {
                Text(
                    "No recent log lines mention this plugin",
                    fontSize = 10.sp,
                    color = BossThemeColors.TextSecondary
                )
            } else {
                logs.forEach { line ->
                    Text(
                        line,
                        fontSize = 9.sp,
                        color = BossThemeColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(3.dp), color = color.copy(alpha = 0.2f)) {
        Text(
            text,
            color = color,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun ProbeEmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = BossThemeColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                message,
                color = BossThemeColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}
