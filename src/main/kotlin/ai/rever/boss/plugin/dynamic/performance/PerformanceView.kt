package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.BrowserTabData
import ai.rever.boss.plugin.api.EditorTabData
import ai.rever.boss.plugin.api.GcCollectorData
import ai.rever.boss.plugin.api.HealthStatusLevel
import ai.rever.boss.plugin.api.MemoryPoolData
import ai.rever.boss.plugin.api.PerformanceSettingsData
import ai.rever.boss.plugin.api.PerformanceSnapshotData
import ai.rever.boss.plugin.api.TerminalData
import ai.rever.boss.plugin.api.ThreadData
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossDarkAccent
import ai.rever.boss.plugin.ui.BossDarkBackground
import ai.rever.boss.plugin.ui.BossDarkBorder
import ai.rever.boss.plugin.ui.BossDarkError
import ai.rever.boss.plugin.ui.BossDarkSuccess
import ai.rever.boss.plugin.ui.BossDarkSurface
import ai.rever.boss.plugin.ui.BossDarkTextPrimary
import ai.rever.boss.plugin.ui.BossDarkTextSecondary
import ai.rever.boss.plugin.ui.BossDarkWarning
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.outlined.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Performance panel view with tabs.
 */
@Composable
fun PerformanceView(viewModel: PerformanceViewModel) {
    val snapshot by viewModel.currentSnapshot.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BossDarkBackground)
    ) {
        // Tab bar
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            backgroundColor = BossDarkBackground,
            contentColor = BossDarkAccent,
            modifier = Modifier.height(32.dp)
        ) {
            PerformanceViewModel.Tab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    modifier = Modifier.height(32.dp),
                    text = {
                        Text(
                            text = tab.displayName,
                            color = if (selectedTab == tab) BossDarkTextPrimary else BossDarkTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                )
            }
        }

        Divider(color = BossDarkBorder, thickness = 1.dp)

        // Export result notification
        exportResult?.let { path ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                color = BossDarkSuccess.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Exported to: $path",
                        color = BossDarkSuccess,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { viewModel.clearExportResult() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossDarkSurface)
                    ) {
                        Text("Dismiss", fontSize = 12.sp)
                    }
                }
            }
        }

        // Tab content
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            when (selectedTab) {
                PerformanceViewModel.Tab.OVERVIEW -> OverviewTab(snapshot, settings, viewModel)
                PerformanceViewModel.Tab.MEMORY -> MemoryTab(snapshot)
                PerformanceViewModel.Tab.CPU -> CpuTab(snapshot)
                PerformanceViewModel.Tab.TIMINGS -> TimingsTab(snapshot)
                PerformanceViewModel.Tab.RESOURCES -> ResourcesTab(snapshot)
            }
        }
    }
}

@Composable
private fun OverviewTab(
    snapshot: PerformanceSnapshotData?,
    settings: PerformanceSettingsData,
    viewModel: PerformanceViewModel
) {
    if (snapshot == null) {
        EmptyState("Waiting for metrics...")
        return
    }

    val memoryStatus = getHealthStatus(snapshot.heapUsagePercent, settings.memoryWarningThresholdPercent, settings.memoryCriticalThresholdPercent)
    val cpuStatus = getHealthStatus(snapshot.processLoadPercent, settings.cpuWarningThresholdPercent.toFloat(), settings.cpuCriticalThresholdPercent.toFloat())
    val overallStatus = if (memoryStatus == HealthStatusLevel.CRITICAL || cpuStatus == HealthStatusLevel.CRITICAL) {
        HealthStatusLevel.CRITICAL
    } else if (memoryStatus == HealthStatusLevel.WARNING || cpuStatus == HealthStatusLevel.WARNING) {
        HealthStatusLevel.WARNING
    } else {
        HealthStatusLevel.GOOD
    }

    val overviewListState = rememberLazyListState()
    LazyColumn(
        state = overviewListState,
        modifier = Modifier
            .fillMaxSize()
            .lazyListScrollbar(
                listState = overviewListState,
                direction = Orientation.Vertical,
                config = getPanelScrollbarConfig()
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            // Health summary card
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("System Health", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                        HealthBadge(overallStatus)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Circular gauges row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CircularGauge(
                            value = snapshot.heapUsagePercent / 100f,
                            label = "Memory",
                            valueText = "${snapshot.heapUsagePercent.toInt()}%",
                            status = memoryStatus,
                            onClick = { viewModel.selectTab(PerformanceViewModel.Tab.MEMORY) }
                        )
                        CircularGauge(
                            value = snapshot.processLoadPercent / 100f,
                            label = "CPU",
                            valueText = "${snapshot.processLoadPercent.toInt()}%",
                            status = cpuStatus,
                            onClick = { viewModel.selectTab(PerformanceViewModel.Tab.CPU) }
                        )
                        CircularGauge(
                            value = (snapshot.activeThreadCount.toFloat() / 100f).coerceAtMost(1f),
                            label = "Threads",
                            valueText = "${snapshot.activeThreadCount}",
                            status = HealthStatusLevel.GOOD,
                            showAsCount = true,
                            onClick = { viewModel.selectTab(PerformanceViewModel.Tab.CPU) }
                        )
                        CircularGauge(
                            value = (snapshot.gcCollectionCount.toFloat() / 50f).coerceAtMost(1f),
                            label = "GC",
                            valueText = "${snapshot.gcCollectionCount}",
                            status = HealthStatusLevel.GOOD,
                            showAsCount = true,
                            onClick = { viewModel.selectTab(PerformanceViewModel.Tab.TIMINGS) }
                        )
                    }
                }
            }
        }

        item {
            // Quick actions
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { viewModel.requestGC() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossDarkAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.Delete, "GC", modifier = Modifier.size(14.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Request GC", color = Color.White, fontSize = 11.sp)
                }

                Button(
                    onClick = { viewModel.exportMetrics() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossDarkSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.SaveAlt, "Export", modifier = Modifier.size(14.dp), tint = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export Metrics", color = BossDarkTextPrimary, fontSize = 11.sp)
                }
            }
        }

        item {
            // Resources summary
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectTab(PerformanceViewModel.Tab.RESOURCES) },
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Resources", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ResourceCard(Modifier.weight(1f), "Browser", snapshot.browserTabCount, Icons.Outlined.Web, BossDarkAccent)
                        ResourceCard(Modifier.weight(1f), "Terminal", snapshot.terminalCount, Icons.Outlined.Terminal, BossDarkSuccess)
                        ResourceCard(Modifier.weight(1f), "Editor", snapshot.editorTabCount, Icons.Default.Edit, BossDarkWarning)
                        ResourceCard(Modifier.weight(1f), "Panels", snapshot.panelCount, Icons.AutoMirrored.Outlined.ViewSidebar, Color(0xFF9C27B0))
                        ResourceCard(Modifier.weight(1f), "Windows", snapshot.windowCount, Icons.Outlined.Window, Color(0xFF00BCD4))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTab(snapshot: PerformanceSnapshotData?) {
    if (snapshot == null) {
        EmptyState("Waiting for memory metrics...")
        return
    }

    val memoryListState = rememberLazyListState()
    LazyColumn(
        state = memoryListState,
        modifier = Modifier
            .fillMaxSize()
            .lazyListScrollbar(
                listState = memoryListState,
                direction = Orientation.Vertical,
                config = getPanelScrollbarConfig()
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Heap Memory", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    ProgressBar(
                        progress = snapshot.heapUsagePercent / 100f,
                        label = "${snapshot.heapUsedMB.toInt()}MB / ${snapshot.heapMaxMB.toInt()}MB"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Committed: ${snapshot.heapCommittedMB.toInt()}MB",
                        color = BossDarkTextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Memory Pools", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Pool", fontSize = 10.sp, color = BossDarkTextSecondary, modifier = Modifier.weight(2f))
                        Text("Type", fontSize = 10.sp, color = BossDarkTextSecondary, modifier = Modifier.weight(1f))
                        Text("Usage", fontSize = 10.sp, color = BossDarkTextSecondary, modifier = Modifier.width(100.dp))
                    }

                    Divider(color = BossDarkBorder, thickness = 1.dp)

                    if (snapshot.memoryPools.isEmpty()) {
                        Text(
                            "No memory pool data available",
                            fontSize = 10.sp,
                            color = BossDarkTextSecondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        snapshot.memoryPools.forEach { pool ->
                            MemoryPoolRow(pool)
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Non-Heap Summary", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MetricItem("Used", "${snapshot.nonHeapUsedMB.toInt()}MB", HealthStatusLevel.GOOD)
                        MetricItem("Committed", "${snapshot.nonHeapCommittedMB.toInt()}MB", HealthStatusLevel.GOOD)
                    }
                }
            }
        }
    }
}

@Composable
private fun CpuTab(snapshot: PerformanceSnapshotData?) {
    if (snapshot == null) {
        EmptyState("Waiting for CPU metrics...")
        return
    }

    val cpuListState = rememberLazyListState()
    LazyColumn(
        state = cpuListState,
        modifier = Modifier
            .fillMaxSize()
            .lazyListScrollbar(
                listState = cpuListState,
                direction = Orientation.Vertical,
                config = getPanelScrollbarConfig()
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Process CPU", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    ProgressBar(
                        progress = snapshot.processLoadPercent / 100f,
                        label = "${snapshot.processLoadPercent.toInt()}%"
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("System CPU", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    ProgressBar(
                        progress = snapshot.systemLoadPercent / 100f,
                        label = "${snapshot.systemLoadPercent.toInt()}%"
                    )
                }
            }
        }

        // Threads section header
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = BossDarkSurface,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
            ) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
                    Text(
                        "Threads (${snapshot.activeThreadCount} active, ${snapshot.availableProcessors} processors)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = BossDarkTextPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Thread list header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Name", fontSize = 10.sp, color = BossDarkTextSecondary, modifier = Modifier.weight(2f))
                        Text("State", fontSize = 10.sp, color = BossDarkTextSecondary, modifier = Modifier.weight(1f))
                        Text("CPU", fontSize = 10.sp, color = BossDarkTextSecondary, modifier = Modifier.width(60.dp))
                    }

                    Divider(color = BossDarkBorder, thickness = 1.dp)
                }
            }
        }

        // Thread rows
        if (snapshot.threads.isNotEmpty()) {
            itemsIndexed(
                items = snapshot.threads,
                key = { _, thread -> thread.id }
            ) { index, thread ->
                val isLast = index == snapshot.threads.lastIndex
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = BossDarkSurface,
                    shape = if (isLast) RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp) else RoundedCornerShape(0.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = if (isLast) 8.dp else 0.dp)) {
                        ThreadRow(thread)
                    }
                }
            }
        } else {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = BossDarkSurface,
                    shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
                ) {
                    Text(
                        "No thread data available",
                        fontSize = 10.sp,
                        color = BossDarkTextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimingsTab(snapshot: PerformanceSnapshotData?) {
    if (snapshot == null) {
        EmptyState("Waiting for GC metrics...")
        return
    }

    val currentTime = System.currentTimeMillis()

    val timingsListState = rememberLazyListState()
    LazyColumn(
        state = timingsListState,
        modifier = Modifier
            .fillMaxSize()
            .lazyListScrollbar(
                listState = timingsListState,
                direction = Orientation.Vertical,
                config = getPanelScrollbarConfig()
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Garbage Collection Summary", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MetricItem("Total Collections", "${snapshot.gcCollectionCount}", HealthStatusLevel.GOOD)
                        MetricItem("Total Time", "${snapshot.gcCollectionTimeMs}ms", HealthStatusLevel.GOOD)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Collectors", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))

                    if (snapshot.gcCollectors.isEmpty()) {
                        Text(
                            "No GC collector data available",
                            fontSize = 10.sp,
                            color = BossDarkTextSecondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        snapshot.gcCollectors.forEach { collector ->
                            GcCollectorRow(collector, currentTime)
                            if (collector != snapshot.gcCollectors.last()) {
                                Divider(color = BossDarkBorder.copy(alpha = 0.5f), thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourcesTab(snapshot: PerformanceSnapshotData?) {
    if (snapshot == null) {
        EmptyState("Waiting for resource metrics...")
        return
    }

    val totalResources = snapshot.browserTabCount + snapshot.terminalCount + snapshot.editorTabCount + snapshot.panelCount + snapshot.windowCount

    val resourcesListState = rememberLazyListState()
    LazyColumn(
        state = resourcesListState,
        modifier = Modifier
            .fillMaxSize()
            .lazyListScrollbar(
                listState = resourcesListState,
                direction = Orientation.Vertical,
                config = getPanelScrollbarConfig()
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Total Resources", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                        Text("Active components", color = BossDarkTextSecondary, fontSize = 10.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(BossDarkAccent.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$totalResources", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = BossDarkAccent)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkSurface,
                elevation = 0.dp,
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Resource Breakdown", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    Spacer(modifier = Modifier.height(10.dp))

                    ResourceBarRow("Browser Tabs", snapshot.browserTabCount, maxOf(10, totalResources), Icons.Outlined.Web, BossDarkAccent)
                    Spacer(modifier = Modifier.height(8.dp))
                    ResourceBarRow("Terminal Sessions", snapshot.terminalCount, maxOf(10, totalResources), Icons.Outlined.Terminal, BossDarkSuccess)
                    Spacer(modifier = Modifier.height(8.dp))
                    ResourceBarRow("Editor Tabs", snapshot.editorTabCount, maxOf(10, totalResources), Icons.Default.Edit, BossDarkWarning)
                    Spacer(modifier = Modifier.height(8.dp))
                    ResourceBarRow("Open Panels", snapshot.panelCount, maxOf(10, totalResources), Icons.AutoMirrored.Outlined.ViewSidebar, Color(0xFF9C27B0))
                    Spacer(modifier = Modifier.height(8.dp))
                    ResourceBarRow("Windows", snapshot.windowCount, maxOf(10, totalResources), Icons.Outlined.Window, Color(0xFF00BCD4))
                }
            }
        }

        // Browser Tabs Details
        if (snapshot.browserTabs.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = BossDarkSurface,
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Web,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BossDarkAccent
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browser Tabs", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    }
                }
            }

            itemsIndexed(
                items = snapshot.browserTabs,
                key = { _, tab -> "browser-${tab.id}" }
            ) { index, tab ->
                val isLast = index == snapshot.browserTabs.lastIndex
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = BossDarkSurface,
                    shape = if (isLast) RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp) else RoundedCornerShape(0.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = if (isLast) 12.dp else 6.dp)) {
                        BrowserTabRow(tab)
                    }
                }
            }
        }

        // Terminal Sessions Details
        if (snapshot.terminals.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = BossDarkSurface,
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BossDarkSuccess
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Terminal Sessions", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    }
                }
            }

            itemsIndexed(
                items = snapshot.terminals,
                key = { _, terminal -> "terminal-${terminal.id}" }
            ) { index, terminal ->
                val isLast = index == snapshot.terminals.lastIndex
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = BossDarkSurface,
                    shape = if (isLast) RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp) else RoundedCornerShape(0.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = if (isLast) 12.dp else 6.dp)) {
                        TerminalRow(terminal)
                    }
                }
            }
        }

        // Editor Tabs Details
        if (snapshot.editorTabs.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = BossDarkSurface,
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = BossDarkWarning
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Editor Tabs", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
                    }
                }
            }

            itemsIndexed(
                items = snapshot.editorTabs,
                key = { _, editor -> "editor-${editor.id}" }
            ) { index, editor ->
                val isLast = index == snapshot.editorTabs.lastIndex
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = BossDarkSurface,
                    shape = if (isLast) RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp) else RoundedCornerShape(0.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = if (isLast) 12.dp else 6.dp)) {
                        EditorTabRow(editor)
                    }
                }
            }
        }
    }
}

// Helper functions and composables

private fun getHealthStatus(value: Float, warningThreshold: Int, criticalThreshold: Int): HealthStatusLevel {
    return when {
        value >= criticalThreshold -> HealthStatusLevel.CRITICAL
        value >= warningThreshold -> HealthStatusLevel.WARNING
        else -> HealthStatusLevel.GOOD
    }
}

private fun getHealthStatus(value: Float, warningThreshold: Float, criticalThreshold: Float): HealthStatusLevel {
    return when {
        value >= criticalThreshold -> HealthStatusLevel.CRITICAL
        value >= warningThreshold -> HealthStatusLevel.WARNING
        else -> HealthStatusLevel.GOOD
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(48.dp), tint = BossDarkTextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, color = BossDarkTextSecondary)
        }
    }
}

@Composable
private fun HealthBadge(status: HealthStatusLevel) {
    val color = when (status) {
        HealthStatusLevel.GOOD -> BossDarkSuccess
        HealthStatusLevel.WARNING -> BossDarkWarning
        HealthStatusLevel.CRITICAL -> BossDarkError
    }

    Surface(shape = RoundedCornerShape(3.dp), color = color.copy(alpha = 0.2f)) {
        Text(text = status.name, color = color, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
private fun ProgressBar(progress: Float, label: String) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = BossDarkTextPrimary, fontSize = 11.sp)
            Text("${(progress * 100).toInt()}%", color = BossDarkTextSecondary, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            backgroundColor = BossDarkBorder,
            color = when {
                progress >= 0.9f -> BossDarkError
                progress >= 0.75f -> BossDarkWarning
                else -> BossDarkSuccess
            }
        )
    }
}

@Composable
private fun CircularGauge(
    value: Float,
    label: String,
    valueText: String,
    status: HealthStatusLevel,
    showAsCount: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val animatedValue by animateFloatAsState(targetValue = value.coerceIn(0f, 1f), animationSpec = tween(durationMillis = 500))

    val color = if (showAsCount) BossDarkAccent else when (status) {
        HealthStatusLevel.GOOD -> BossDarkSuccess
        HealthStatusLevel.WARNING -> BossDarkWarning
        HealthStatusLevel.CRITICAL -> BossDarkError
    }

    val gaugeSize = 150.dp
    val strokeWidth = 12.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    ) {
        Box(modifier = Modifier.size(gaugeSize), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(gaugeSize)) {
                val stroke = strokeWidth.toPx()
                val arcSize = size.minDimension - stroke
                drawArc(color = BossDarkBorder, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2), size = Size(arcSize, arcSize),
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            Canvas(modifier = Modifier.size(gaugeSize)) {
                val stroke = strokeWidth.toPx()
                val arcSize = size.minDimension - stroke
                drawArc(color = color, startAngle = 135f, sweepAngle = 270f * animatedValue, useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2), size = Size(arcSize, arcSize),
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            Text(text = valueText, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = BossDarkTextPrimary)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, fontSize = 16.sp, color = BossDarkTextSecondary)
    }
}

@Composable
private fun ResourceCard(modifier: Modifier = Modifier, label: String, count: Int, icon: ImageVector, color: Color) {
    Surface(modifier = modifier, shape = RoundedCornerShape(4.dp), color = BossDarkBackground) {
        Column(
            modifier = Modifier.border(1.dp, BossDarkBorder, RoundedCornerShape(4.dp)).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(24.dp).background(color.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp), tint = color)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "$count", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = BossDarkTextPrimary)
            Text(text = label, fontSize = 10.sp, color = BossDarkTextSecondary)
        }
    }
}

@Composable
private fun ResourceBarRow(label: String, count: Int, maxCount: Int, icon: ImageVector, color: Color) {
    val progress = if (maxCount > 0) count.toFloat() / maxCount else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), animationSpec = tween(durationMillis = 500))

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(20.dp).background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = label, modifier = Modifier.size(12.dp), tint = color)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, color = BossDarkTextPrimary, fontSize = 12.sp)
            }
            Text("$count", fontWeight = FontWeight.Bold, color = color, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.dp)).background(BossDarkBorder)) {
            Box(modifier = Modifier.fillMaxWidth(animatedProgress).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, status: HealthStatusLevel) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossDarkTextPrimary)
        Text(label, color = BossDarkTextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun MemoryPoolRow(pool: MemoryPoolData) {
    val typeColor = if (pool.type == "HEAP") BossDarkAccent else Color(0xFF9C27B0)
    val progress = pool.usagePercent / 100f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = pool.name.take(20) + if (pool.name.length > 20) "..." else "",
            fontSize = 10.sp,
            color = BossDarkTextPrimary,
            modifier = Modifier.weight(2f)
        )

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(2.dp),
            color = typeColor.copy(alpha = 0.2f)
        ) {
            Text(
                text = pool.type,
                fontSize = 9.sp,
                color = typeColor,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        Row(
            modifier = Modifier.width(100.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BossDarkBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            when {
                                progress >= 0.9f -> BossDarkError
                                progress >= 0.75f -> BossDarkWarning
                                else -> BossDarkSuccess
                            }
                        )
                )
            }
            Text(
                text = "${pool.usagePercent.toInt()}%",
                fontSize = 9.sp,
                color = BossDarkTextSecondary
            )
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadData) {
    val stateColor = when (thread.state) {
        "RUNNABLE" -> BossDarkSuccess
        "BLOCKED" -> BossDarkError
        "WAITING", "TIMED_WAITING" -> BossDarkWarning
        else -> BossDarkTextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = thread.name.take(30) + if (thread.name.length > 30) "..." else "",
            fontSize = 10.sp,
            color = BossDarkTextPrimary,
            modifier = Modifier.weight(2f)
        )

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(2.dp),
            color = stateColor.copy(alpha = 0.2f)
        ) {
            Text(
                text = thread.state,
                fontSize = 9.sp,
                color = stateColor,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        Text(
            text = formatCpuTime(thread.cpuTimeMs),
            fontSize = 10.sp,
            color = BossDarkTextSecondary,
            modifier = Modifier.width(60.dp)
        )
    }
}

private fun formatCpuTime(ms: Long): String {
    return when {
        ms >= 60000 -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
        ms >= 1000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
        else -> "${ms}ms"
    }
}

@Composable
private fun GcCollectorRow(collector: GcCollectorData, currentTime: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = collector.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = BossDarkTextPrimary
            )
            Text(
                text = "${collector.collectionCount} collections, ${collector.collectionTimeMs}ms",
                fontSize = 10.sp,
                color = BossDarkTextSecondary
            )
        }

        collector.lastGcInfo?.let { lastGc ->
            val timeAgo = currentTime - lastGc.startTime
            val timeAgoText = formatTimeAgo(timeAgo)

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Last GC: $timeAgoText ago",
                    fontSize = 9.sp,
                    color = BossDarkTextSecondary
                )
                Text(
                    text = "${lastGc.durationMs}ms",
                    fontSize = 9.sp,
                    color = BossDarkWarning
                )
                if (lastGc.memoryReclaimedBytes > 0) {
                    Text(
                        text = "reclaimed ${lastGc.memoryReclaimedMB.toInt()}MB",
                        fontSize = 9.sp,
                        color = BossDarkSuccess
                    )
                }
            }
        }
    }
}

private fun formatTimeAgo(ms: Long): String {
    return when {
        ms >= 3600000 -> "${ms / 3600000}h ${(ms % 3600000) / 60000}m"
        ms >= 60000 -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
        ms >= 1000 -> "${ms / 1000}s"
        else -> "${ms}ms"
    }
}

@Composable
private fun BrowserTabRow(tab: BrowserTabData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (tab.isActive) BossDarkAccent.copy(alpha = 0.1f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tab.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(BossDarkAccent, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab.title.ifEmpty { "Untitled" },
                fontSize = 11.sp,
                fontWeight = if (tab.isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = BossDarkTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = tab.url.ifEmpty { "about:blank" },
                fontSize = 10.sp,
                color = BossDarkTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TerminalRow(terminal: TerminalData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (terminal.isActive) BossDarkSuccess.copy(alpha = 0.1f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (terminal.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(BossDarkSuccess, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = terminal.title.ifEmpty { "Terminal" },
                fontSize = 11.sp,
                fontWeight = if (terminal.isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = BossDarkTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (terminal.workingDirectory.isNotEmpty()) {
                Text(
                    text = terminal.workingDirectory,
                    fontSize = 10.sp,
                    color = BossDarkTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EditorTabRow(editor: EditorTabData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (editor.isActive) BossDarkWarning.copy(alpha = 0.1f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editor.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(BossDarkWarning, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (editor.isModified) {
            Text(
                text = "●",
                fontSize = 10.sp,
                color = BossDarkWarning,
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = editor.fileName.ifEmpty { "Untitled" },
                fontSize = 11.sp,
                fontWeight = if (editor.isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = BossDarkTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (editor.filePath.isNotEmpty()) {
                Text(
                    text = editor.filePath,
                    fontSize = 10.sp,
                    color = BossDarkTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
