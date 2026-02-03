package ai.rever.boss.plugin.dynamic.performance

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed

/**
 * Performance panel info.
 * Displays JVM metrics, CPU, memory, and resource counts.
 */
object PerformanceInfo : PanelInfo {
    override val id = PanelId("performance", 15)
    override val displayName = "Performance"
    override val icon = Icons.Outlined.Speed
    override val defaultSlotPosition = left.bottom
}
