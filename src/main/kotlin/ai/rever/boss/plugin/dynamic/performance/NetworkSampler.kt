package ai.rever.boss.plugin.dynamic.performance

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Samples system network-interface counters and the BOSS process's open TCP
 * connections. The JVM exposes no network counters, so this reads the OS:
 * `netstat -ib` on macOS, `/proc/net/dev` on Linux (other platforms report
 * unavailable). Connections come from `lsof` scoped to this process.
 *
 * The 2s polling loop runs only while a Performance panel holds a lease
 * ([acquire]/[release]); [snapshotNow] serves MCP calls when the loop is cold
 * by taking two quick samples to derive rates.
 */
class NetworkSampler {

    data class InterfaceStats(
        val name: String,
        val rxBytes: Long,
        val txBytes: Long,
        val rxRateBps: Double,
        val txRateBps: Double,
    ) {
        val isLoopback: Boolean get() = name.startsWith("lo")
    }

    data class ConnectionInfo(
        val local: String,
        val remote: String,
        val state: String,
    )

    data class NetworkSnapshot(
        val atMs: Long,
        val available: Boolean,
        val interfaces: List<InterfaceStats>,
        /** Totals across non-loopback interfaces. */
        val totalRxBytes: Long,
        val totalTxBytes: Long,
        val totalRxRateBps: Double,
        val totalTxRateBps: Double,
        val connections: List<ConnectionInfo>,
        val connectionsSampled: Boolean,
    )

    data class RatePoint(val atMs: Long, val rxBps: Double, val txBps: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sampleMutex = Mutex()

    private val _snapshot = MutableStateFlow<NetworkSnapshot?>(null)
    val snapshot: StateFlow<NetworkSnapshot?> = _snapshot.asStateFlow()

    private val _rateHistory = MutableStateFlow<List<RatePoint>>(emptyList())
    val rateHistory: StateFlow<List<RatePoint>> = _rateHistory.asStateFlow()

    private val leases = AtomicInteger(0)
    private var loopJob: Job? = null

    /** Interface name → (rxBytes, txBytes) from the previous sample. */
    private var prevCounters: Map<String, Pair<Long, Long>> = emptyMap()
    private var prevAtMs: Long = 0L
    private var tick = 0

    /** Start the polling loop (first lease) — one lease per open panel. */
    fun acquire() {
        if (leases.incrementAndGet() == 1) {
            loopJob = scope.launch {
                while (isActive) {
                    sampleOnce(includeConnections = tick % CONNECTION_EVERY_N_TICKS == 0)
                    tick++
                    delay(SAMPLE_INTERVAL_MS)
                }
            }
        }
    }

    fun release() {
        if (leases.decrementAndGet() <= 0) {
            leases.set(0)
            loopJob?.cancel()
            loopJob = null
        }
    }

    /**
     * Fresh snapshot for on-demand consumers (MCP). Reuses the loop's data when
     * it is recent; otherwise takes two samples ~600ms apart to derive rates.
     */
    suspend fun snapshotNow(): NetworkSnapshot? {
        val current = _snapshot.value
        if (loopJob?.isActive == true && current != null &&
            System.currentTimeMillis() - current.atMs < 2 * SAMPLE_INTERVAL_MS
        ) {
            return current
        }
        sampleOnce(includeConnections = false)
        delay(600)
        sampleOnce(includeConnections = true)
        return _snapshot.value
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun sampleOnce(includeConnections: Boolean) = sampleMutex.withLock {
        val now = System.currentTimeMillis()
        val counters = readCounters()
        if (counters == null) {
            _snapshot.value = NetworkSnapshot(
                atMs = now, available = false, interfaces = emptyList(),
                totalRxBytes = 0, totalTxBytes = 0, totalRxRateBps = 0.0, totalTxRateBps = 0.0,
                connections = _snapshot.value?.connections ?: emptyList(),
                connectionsSampled = false,
            )
            return@withLock
        }

        val elapsedSec = if (prevAtMs > 0) (now - prevAtMs) / 1000.0 else 0.0
        val interfaces = counters.map { (name, rxTx) ->
            val (rx, tx) = rxTx
            val prev = prevCounters[name]
            val rxRate = if (prev != null && elapsedSec > 0) ((rx - prev.first) / elapsedSec).coerceAtLeast(0.0) else 0.0
            val txRate = if (prev != null && elapsedSec > 0) ((tx - prev.second) / elapsedSec).coerceAtLeast(0.0) else 0.0
            InterfaceStats(name, rx, tx, rxRate, txRate)
        }.sortedByDescending { it.rxBytes + it.txBytes }
        prevCounters = counters
        prevAtMs = now

        val active = interfaces.filter { !it.isLoopback }
        val connections = if (includeConnections) readConnections() else null
        _snapshot.value = NetworkSnapshot(
            atMs = now,
            available = true,
            interfaces = interfaces,
            totalRxBytes = active.sumOf { it.rxBytes },
            totalTxBytes = active.sumOf { it.txBytes },
            totalRxRateBps = active.sumOf { it.rxRateBps },
            totalTxRateBps = active.sumOf { it.txRateBps },
            connections = connections ?: _snapshot.value?.connections ?: emptyList(),
            connectionsSampled = connections != null || _snapshot.value?.connectionsSampled == true,
        )
        _rateHistory.value = (_rateHistory.value + RatePoint(
            now,
            active.sumOf { it.rxRateBps },
            active.sumOf { it.txRateBps },
        )).takeLast(HISTORY_CAPACITY)
    }

    /** Interface name → (rxBytes, txBytes), or null when unsupported/failed. */
    private fun readCounters(): Map<String, Pair<Long, Long>>? {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> parseNetstatIb(runCommand(listOf(netstatBinary(), "-ibn")) ?: return null)
            os.contains("linux") -> parseProcNetDev()
            else -> null
        }
    }

    /**
     * macOS `netstat -ibn`: one `<Link#N>` row per interface carries the byte
     * counters. Column presence varies by macOS release, so the counter columns
     * are located by their offset from the END of the header row.
     */
    private fun parseNetstatIb(output: String): Map<String, Pair<Long, Long>>? {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val header = lines.first().trim().split(Regex("\\s+"))
        val ibytesFromEnd = header.size - header.indexOf("Ibytes")
        val obytesFromEnd = header.size - header.indexOf("Obytes")
        if (ibytesFromEnd > header.size || obytesFromEnd > header.size) return null
        val result = LinkedHashMap<String, Pair<Long, Long>>()
        for (line in lines.drop(1)) {
            if (!line.contains("<Link#")) continue
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < maxOf(ibytesFromEnd, obytesFromEnd)) continue
            val name = cols[0]
            val rx = cols[cols.size - ibytesFromEnd].toLongOrNull() ?: continue
            val tx = cols[cols.size - obytesFromEnd].toLongOrNull() ?: continue
            result[name] = rx to tx
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseProcNetDev(): Map<String, Pair<Long, Long>>? = try {
        val result = LinkedHashMap<String, Pair<Long, Long>>()
        File("/proc/net/dev").readLines().drop(2).forEach { line ->
            val name = line.substringBefore(':').trim()
            val fields = line.substringAfter(':').trim().split(Regex("\\s+"))
            if (name.isNotEmpty() && fields.size >= 9) {
                val rx = fields[0].toLongOrNull()
                val tx = fields[8].toLongOrNull()
                if (rx != null && tx != null) result[name] = rx to tx
            }
        }
        result.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Open TCP connections of this process via lsof; null when unavailable. */
    private fun readConnections(): List<ConnectionInfo>? {
        val pid = ProcessHandle.current().pid()
        val output = runCommand(listOf(lsofBinary(), "-nP", "-iTCP", "-a", "-p", pid.toString()))
            ?: return null
        return output.lines().drop(1).mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 9) return@mapNotNull null
            val name = cols.subList(8, cols.size).joinToString(" ")
            val state = Regex("\\(([A-Z_]+)\\)").find(name)?.groupValues?.get(1) ?: ""
            val endpoint = name.substringBefore(" (").trim()
            val local = endpoint.substringBefore("->")
            val remote = if (endpoint.contains("->")) endpoint.substringAfter("->") else ""
            ConnectionInfo(local, remote, state)
        }
    }

    private fun netstatBinary(): String =
        "/usr/sbin/netstat".takeIf { File(it).canExecute() } ?: "netstat"

    private fun lsofBinary(): String =
        listOf("/usr/sbin/lsof", "/usr/bin/lsof").firstOrNull { File(it).canExecute() } ?: "lsof"

    private fun runCommand(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS) && process.exitValue() == 0) output
        else {
            process.destroyForcibly()
            null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val CONNECTION_EVERY_N_TICKS = 5
        private const val HISTORY_CAPACITY = 150
        private const val COMMAND_TIMEOUT_SEC = 3L

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }

        fun formatRate(bps: Double): String = when {
            bps >= (1L shl 20) -> "%.1f MB/s".format(bps / (1L shl 20))
            bps >= (1L shl 10) -> "%.1f KB/s".format(bps / (1L shl 10))
            else -> "%.0f B/s".format(bps)
        }
    }
}
