package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Network tab: system throughput (with sparkline), per-interface counters, and
 * the BOSS process's open TCP connections.
 */
@Composable
internal fun NetworkTab(
    snapshot: NetworkSampler.NetworkSnapshot?,
    rateHistory: List<NetworkSampler.RatePoint>
) {
    if (snapshot == null) {
        NetworkEmptyState("Waiting for network metrics...")
        return
    }
    if (!snapshot.available) {
        NetworkEmptyState("Network counters are not available on this platform")
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ThroughputCard(snapshot, rateHistory) }
        item { InterfacesCard(snapshot.interfaces) }
        item { ConnectionsCard(snapshot) }
    }
}

@Composable
private fun ThroughputCard(
    snapshot: NetworkSampler.NetworkSnapshot,
    rateHistory: List<NetworkSampler.RatePoint>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 0.dp,
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Throughput", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossThemeColors.TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RateReadout("↓ Down", snapshot.totalRxRateBps, BossThemeColors.AccentColor)
                RateReadout("↑ Up", snapshot.totalTxRateBps, BossThemeColors.SuccessColor)
            }

            if (rateHistory.size >= 2) {
                Spacer(modifier = Modifier.height(10.dp))
                RateSparkline(
                    history = rateHistory,
                    rxColor = BossThemeColors.AccentColor,
                    txColor = BossThemeColors.SuccessColor,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                val spanSec = (rateHistory.last().atMs - rateHistory.first().atMs) / 1000
                Text(
                    "last ${if (spanSec >= 60) "${spanSec / 60}m ${spanSec % 60}s" else "${spanSec}s"}",
                    fontSize = 9.sp,
                    color = BossThemeColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Totals since boot: ↓ ${NetworkSampler.formatBytes(snapshot.totalRxBytes)}   ↑ ${NetworkSampler.formatBytes(snapshot.totalTxBytes)}",
                fontSize = 10.sp,
                color = BossThemeColors.TextSecondary
            )
        }
    }
}

@Composable
private fun RateReadout(label: String, bps: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            NetworkSampler.formatRate(bps),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = color,
            fontFamily = FontFamily.Monospace
        )
        Text(label, fontSize = 10.sp, color = BossThemeColors.TextSecondary)
    }
}

@Composable
private fun RateSparkline(
    history: List<NetworkSampler.RatePoint>,
    rxColor: Color,
    txColor: Color,
    modifier: Modifier = Modifier
) {
    val gridColor = BossThemeColors.BorderColor
    Canvas(modifier = modifier) {
        val maxRate = history.maxOf { maxOf(it.rxBps, it.txBps) }.coerceAtLeast(1.0)
        val stepX = size.width / (history.size - 1).coerceAtLeast(1)

        // baseline + midline
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        drawLine(gridColor.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)

        fun pathFor(value: (NetworkSampler.RatePoint) -> Double): Path {
            val path = Path()
            history.forEachIndexed { i, point ->
                val x = i * stepX
                val y = size.height - (value(point) / maxRate * size.height).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }
        drawPath(pathFor { it.rxBps }, rxColor, style = Stroke(width = 1.5f))
        drawPath(pathFor { it.txBps }, txColor, style = Stroke(width = 1.5f))
    }
}

@Composable
private fun InterfacesCard(interfaces: List<NetworkSampler.InterfaceStats>) {
    // Hide never-used virtual interfaces to keep the panel readable.
    val shown = interfaces.filter { it.rxBytes + it.txBytes > 0 }
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 0.dp,
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Interfaces", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossThemeColors.TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("Name", fontSize = 10.sp, color = BossThemeColors.TextSecondary, modifier = Modifier.weight(1f))
                Text("↓ Total", fontSize = 10.sp, color = BossThemeColors.TextSecondary, modifier = Modifier.width(64.dp))
                Text("↑ Total", fontSize = 10.sp, color = BossThemeColors.TextSecondary, modifier = Modifier.width(64.dp))
                Text("↓/s", fontSize = 10.sp, color = BossThemeColors.TextSecondary, modifier = Modifier.width(58.dp))
                Text("↑/s", fontSize = 10.sp, color = BossThemeColors.TextSecondary, modifier = Modifier.width(58.dp))
            }
            Divider(color = BossThemeColors.BorderColor, thickness = 1.dp)

            if (shown.isEmpty()) {
                Text(
                    "No interface counters available",
                    fontSize = 10.sp,
                    color = BossThemeColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                shown.forEach { iface ->
                    val active = iface.rxRateBps + iface.txRateBps > 0
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        if (active) BossThemeColors.SuccessColor else BossThemeColors.BorderColor,
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                iface.name,
                                fontSize = 10.sp,
                                color = BossThemeColors.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(NetworkSampler.formatBytes(iface.rxBytes), fontSize = 9.sp, color = BossThemeColors.TextSecondary, modifier = Modifier.width(64.dp))
                        Text(NetworkSampler.formatBytes(iface.txBytes), fontSize = 9.sp, color = BossThemeColors.TextSecondary, modifier = Modifier.width(64.dp))
                        Text(
                            if (iface.rxRateBps > 0) NetworkSampler.formatRate(iface.rxRateBps) else "—",
                            fontSize = 9.sp,
                            color = if (iface.rxRateBps > 0) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                            modifier = Modifier.width(58.dp)
                        )
                        Text(
                            if (iface.txRateBps > 0) NetworkSampler.formatRate(iface.txRateBps) else "—",
                            fontSize = 9.sp,
                            color = if (iface.txRateBps > 0) BossThemeColors.SuccessColor else BossThemeColors.TextSecondary,
                            modifier = Modifier.width(58.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionsCard(snapshot: NetworkSampler.NetworkSnapshot) {
    val connections = snapshot.connections
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 0.dp,
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TCP Connections (BOSS process)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossThemeColors.TextPrimary)
                Text("${connections.size}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BossThemeColors.AccentColor)
            }

            if (!snapshot.connectionsSampled) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Connection listing unavailable (lsof not found)",
                    fontSize = 10.sp,
                    color = BossThemeColors.TextSecondary
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(6.dp))
            val byState = connections.groupingBy { it.state.ifEmpty { "OTHER" } }.eachCount()
                .entries.sortedByDescending { it.value }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                byState.take(4).forEach { (state, count) ->
                    val color = connectionStateColor(state)
                    Surface(shape = RoundedCornerShape(3.dp), color = color.copy(alpha = 0.2f)) {
                        Text(
                            "$state $count",
                            color = color,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = BossThemeColors.BorderColor, thickness = 1.dp)

            val shown = connections.take(MAX_CONNECTIONS_SHOWN)
            shown.forEach { conn ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(connectionStateColor(conn.state), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (conn.remote.isNotEmpty()) "${conn.local} → ${conn.remote}" else conn.local,
                        fontSize = 9.sp,
                        color = BossThemeColors.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        conn.state,
                        fontSize = 8.sp,
                        color = connectionStateColor(conn.state),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            if (connections.size > shown.size) {
                Text(
                    "+${connections.size - shown.size} more",
                    fontSize = 9.sp,
                    color = BossThemeColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun connectionStateColor(state: String): Color = when (state) {
    "ESTABLISHED" -> BossThemeColors.SuccessColor
    "LISTEN" -> BossThemeColors.AccentColor
    "CLOSE_WAIT", "TIME_WAIT", "FIN_WAIT_1", "FIN_WAIT_2", "CLOSING", "LAST_ACK" -> BossThemeColors.WarningColor
    else -> BossThemeColors.TextSecondary
}

@Composable
private fun NetworkEmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Lan,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = BossThemeColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, color = BossThemeColors.TextSecondary)
        }
    }
}

private const val MAX_CONNECTIONS_SHOWN = 25
