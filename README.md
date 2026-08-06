# BOSS Performance

Live JVM and system telemetry for the running BOSS process, in a left sidebar panel.

Eight tabs of metrics sourced from the host's `PerformanceDataProvider`, plus two things this
plugin samples itself: real network throughput per interface, and the heap footprint of any
individual loaded plugin.

## What it does

- **Eight tabs**: Overview, Heap & Pools, CPU & Threads, GC Timings, Resources, Processes,
  Network, Plugins.
- **Memory and CPU**: heap and non-heap usage, memory pools, process versus system CPU, a live
  thread table, and per-collector GC statistics.
- **BOSS resource counts** broken down by browser tabs, terminals, editor tabs, panels and
  windows, each with a drill-down list.
- **Network**: RX/TX rates and counters per interface, plus the BOSS process's open TCP
  connections.
- **Per-plugin memory**: pick any loaded plugin and sample its live-heap footprint - total
  bytes, top classes, sample history, leak signals, running instance count, and recent log
  lines attributed to it.
- **Request a GC** on demand, or export a metrics file and have it opened for you.

Plugin memory sampling forces a full garbage collection, so it is on-demand only and never
runs on a timer.

## MCP tools

| Tool | Purpose |
|---|---|
| `performance_snapshot` | Heap, CPU load, threads, GC and BOSS resource counts |
| `performance_gc` | Request a JVM garbage collection |
| `performance_network` | Per-interface RX/TX rates and open TCP connections |
| `performance_plugin_memory` | Sample one plugin's live-heap footprint, with history and leak signals |

## Requirements

- BOSS >= 9.2.20, boss-plugin-api >= 1.0.20
- `context.performanceDataProvider` is required.

Everything below is optional and degrades to an explanatory message rather than an error:

- macOS uses `/usr/sbin/netstat -ibn` for interface counters, Linux uses `/proc/net/dev`. Other
  platforms report that network counters are unavailable.
- `lsof` (in `/usr/sbin` or `/usr/bin`) lists TCP connections. Absent, the panel says so.
- Plugin memory needs the HotSpot `com.sun.management:type=DiagnosticCommand` MBean. On a JVM
  without it, the histogram is unavailable.
- The plugin list needs `PluginLoaderDelegate`. Absent, probing is unavailable.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-performance-*.jar ~/.boss/plugins/
```

Then reload from Toolbox. For a dev-mode host use `~/.boss_debug/plugins/` instead.

See [AGENTS.md](AGENTS.md) for architecture and conventions.
