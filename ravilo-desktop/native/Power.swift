// R329 (FR-R329-8) — the display stays awake while a film plays: one power assertion, held while playing and
// released on pause and on leaving. System sleep is not held off — only the display's idle dimming.

import Foundation
import IOKit.pwr_mgt

private var displayAssertion: IOPMAssertionID = 0
private let displayLock = NSLock()

@_cdecl("ravilo_display_keep_awake")
public func ravilo_display_keep_awake(_ on: Int32) {
    displayLock.lock()
    defer { displayLock.unlock() }
    if on != 0 {
        guard displayAssertion == 0 else { return }
        var id: IOPMAssertionID = 0
        // kIOPMAssertionTypePreventUserIdleDisplaySleep is a CFSTR macro, which Swift does not import.
        let status = IOPMAssertionCreateWithName("PreventUserIdleDisplaySleep" as CFString,
                                                 IOPMAssertionLevel(kIOPMAssertionLevelOn),
                                                 "Ravilo is playing a film" as CFString, &id)
        if status == 0 { displayAssertion = id }   // kIOReturnSuccess
    } else if displayAssertion != 0 {
        IOPMAssertionRelease(displayAssertion)
        displayAssertion = 0
    }
}

// DEB-3670 (FR-R330-3d) — while a cast is joined or rejoining, one process activity keeps macOS from napping the app: a
// casting app plays nothing itself, so a hidden window is napped, and App Nap coalesces the 5 s heartbeat timer past the
// 10 s CAF waits before closing the connection. The system and the display may still sleep.
private var castActivity: NSObjectProtocol?
private let castActivityLock = NSLock()

@_cdecl("ravilo_cast_activity")
public func ravilo_cast_activity(_ on: Int32) {
    castActivityLock.lock()
    defer { castActivityLock.unlock() }
    if on != 0 {
        guard castActivity == nil else { return }
        castActivity = ProcessInfo.processInfo.beginActivity(options: .userInitiatedAllowingIdleSystemSleep, reason: "Ravilo is casting")
    } else if let activity = castActivity {
        ProcessInfo.processInfo.endActivity(activity)
        castActivity = nil
    }
}
