// R383 (FR-R383-6) — the app's own resource figures, read from the kernel the way Activity Monitor reads them:
// `proc_pid_rusage(RUSAGE_INFO_V4)`. The About window shows them, so a heavy client is visible without opening
// Activity Monitor.

import Darwin

/**
 * Fills `out` with three counters for this process and returns 0, else -1 (the kernel refused, or `count` is under 3):
 *
 *  - `out[0]` — `ri_phys_footprint`, bytes: the footprint Activity Monitor calls *Memory*;
 *  - `out[1]` — user + system CPU time, nanoseconds since launch (converted from mach absolute time, which on Apple
 *    silicon is not nanoseconds), so the caller turns two reads into CPU % over its own wall clock;
 *  - `out[2]` — `ri_pkg_idle_wkups`, the cumulative idle wakeups Activity Monitor's *Idle Wake Ups* column is the
 *    rate of.
 */
@_cdecl("ravilo_resource_usage")
public func ravilo_resource_usage(_ out: UnsafeMutablePointer<Int64>?, _ count: Int32) -> Int32 {
    guard let out = out, count >= 3 else { return -1 }
    var usage = rusage_info_v4()
    let rc: Int32 = withUnsafeMutablePointer(to: &usage) {
        $0.withMemoryRebound(to: rusage_info_t?.self, capacity: 1) {
            proc_pid_rusage(getpid(), RUSAGE_INFO_V4, $0)
        }
    }
    if rc != 0 { return -1 }
    var base = mach_timebase_info_data_t()
    mach_timebase_info(&base)
    let numer = UInt64(base.numer), denom = UInt64(max(base.denom, 1))
    let cpuMach = usage.ri_user_time &+ usage.ri_system_time
    // Split before multiplying so a long-running process cannot overflow UInt64.
    let cpuNanos = (cpuMach / denom) &* numer &+ (cpuMach % denom) &* numer / denom
    out[0] = Int64(clamping: usage.ri_phys_footprint)
    out[1] = Int64(clamping: cpuNanos)
    out[2] = Int64(clamping: usage.ri_pkg_idle_wkups)
    return 0
}
