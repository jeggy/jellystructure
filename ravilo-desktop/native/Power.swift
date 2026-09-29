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
