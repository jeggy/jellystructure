// R386 (FR-R386-4) — Sparkle 2 behind the same plain-C bridge as everything else. One SPUUpdater with Sparkle's
// standard user driver, created on first use and driven from Kotlin (`MacUpdater`): Ravilo decides *when* to check
// (UpdateCheck, FR-R386-3) and *what feed* to read; Sparkle downloads, verifies, installs and relaunches.
//
// Like Bonjour.swift this keeps its state here and Kotlin polls `ravilo_update_snapshot` — nothing calls back into the
// JVM. Sparkle itself is only ever touched on the main thread.
//
// Why a background check used to stop after the appcast (Sparkle 2.10.0 source, read 2026-10-16, DEB-1513):
// `-[SPUUpdater _checkForUpdatesInBackground]` picks `SPUAutomaticUpdateDriver` (the silent download) only when
// `[self automaticallyDownloadsUpdates]` is true, else `SPUScheduledUpdateDriver` (which shows the update and waits).
// `SPUUpdaterSettings.currentAutomaticallyDownloadsUpdates` is `allowsAutomaticUpdates && SUAutomaticallyUpdate`, and
// `currentAllowsAutomaticUpdates` is the Info.plist's `SUAllowsAutomaticUpdates` when the bundle carries it, else
// `automaticallyChecksForUpdates`. `-setAutomaticallyDownloadsUpdates:` returns without storing anything while
// `allowsAutomaticUpdates` is false. Build 1 had automatic checks off and no `SUAllowsAutomaticUpdates`, so the
// assignment below was a silent no-op, the scheduled driver ran, and the update was found and never downloaded.
// The bundle now carries `SUAllowsAutomaticUpdates = YES` beside `SUEnableAutomaticChecks = NO`: silent downloads are
// allowed, Sparkle's timer stays off, and `updater(_:mayPerform:)` refuses any check Ravilo did not start.
//
// Sparkle is linked weakly (build.gradle.kts): a bundle without Sparkle.framework still loads this library, and the
// updater then reports `unavailable` instead of taking playback, the Keychain and Bonjour down with it.
//
// Does Sparkle report the bytes of a *background* download through a supported API? Recorded from the 2.10.0 headers,
// 2026-10-15: no. `SPUUpdaterDelegate` has `willDownloadUpdate`, `didDownloadUpdate` and `failedToDownloadUpdate` and
// nothing between them; `SPUStandardUserDriverDelegate` has none either. The only callbacks that carry bytes are
// `-[SPUUserDriver showDownloadDidReceiveExpectedContentLength:]` and `…DataOfLength:`, which belong to a custom user
// driver — and an automatic (silent) download does not go through the user driver at all. So the snapshot's
// `bytes`/`total` stay -1 and FR-R386-4's state 3 shows its sentence without figures. The spike's QA step (f) checks
// that on a real Mac.

import AppKit
import Foundation
import Sparkle

private final class RaviloUpdater: NSObject, SPUUpdaterDelegate, SPUStandardUserDriverDelegate {
    // ── what Kotlin reads (any thread) ──
    private let lock = NSLock()
    /// `unavailable` · `idle` · `checking` · `downloading` · `available` · `ready` · `installing` · `window` ·
    /// `failed` · `notfound`
    private var phase = "idle"
    private var version = ""
    /// With `failed`: `download` · `signature` · `feed` · `other`.
    private var failure = ""
    /// With `unavailable`: why, for the log.
    private var reason = ""
    private var autoDownload = true
    /// FR-R386-9 — how many times *Check for Updates…* has been chosen in the Ravilo menu; Kotlin reads it with the rest.
    private var menuClicks = 0
    /// FR-R386-12 — one entry per change of state, `old|new|version|failure|reason`, handed out by the next snapshot.
    private var changes: [String] = []

    // ── main thread only ──
    private var updater: SPUUpdater?
    private var userDriver: SPUStandardUserDriver?
    private var started = false
    private var feed = ""
    private var userInitiated = false
    /// Set by Ravilo's own calls right before they ask Sparkle for a check; `updater(_:mayPerform:)` consumes it.
    private var askedByRavilo = false
    private var installHandler: (() -> Void)?

    private func set(_ p: String, version v: String? = nil, failure f: String = "", reason r: String = "") {
        lock.lock()
        let old = (phase, version, failure, reason)
        let cleanReason = r.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "|", with: "/")
        phase = p
        if let v = v { version = v }
        failure = f
        reason = cleanReason
        if old != (phase, version, failure, reason) {
            changes.append("\(old.0)|\(p)|\(version)|\(f)|\(cleanReason)")
            if changes.count > 200 { changes.removeFirst(changes.count - 200) }
        }
        lock.unlock()
    }

    private func current() -> String { lock.lock(); defer { lock.unlock() }; return phase }

    /// Hands out — and forgets — the changes of state since the last call, as `change=` lines (FR-R386-12). Kotlin's
    /// poll is the one reader, once a second, so a state that lasts less than a second is still logged.
    func snapshot() -> String {
        lock.lock(); defer { lock.unlock() }
        let lines = ["phase=\(phase)", "version=\(version)", "failure=\(failure)", "reason=\(reason)",
                     "auto=\(autoDownload ? 1 : 0)", "bytes=-1", "total=-1", "menu=\(menuClicks)"] + changes.map { "change=\($0)" }
        changes.removeAll()
        return lines.joined(separator: "\n")
    }

    // ── the Ravilo menu's *Check for Updates…* (FR-R386-9, D8) ──

    /// The choice is recorded and Kotlin's poll sees it: the same no-callback shape as everything else here.
    fileprivate func menuChosen() { lock.lock(); menuClicks += 1; lock.unlock() }

    /// Puts *Check for Updates…* directly under *About Ravilo*, the first item of the application menu (the standard
    /// macOS place), or retitles it when the language changed. Idempotent, so Kotlin may call it every few seconds: AWT
    /// owns this menu and could rebuild it. Nothing happens until the menu bar exists.
    func installMenuItem(_ title: String) {
        DispatchQueue.main.async {
            guard let appMenu = NSApp.mainMenu?.items.first?.submenu else { return }
            if let existing = appMenu.items.first(where: { $0.tag == RaviloUpdater.menuTag }) {
                if existing.title != title { existing.title = title }
                return
            }
            let item = NSMenuItem(title: title, action: #selector(RaviloMenuTarget.check(_:)), keyEquivalent: "")
            item.target = menuTarget
            item.tag = RaviloUpdater.menuTag
            appMenu.insertItem(item, at: min(1, appMenu.items.count))
        }
    }

    private static let menuTag = 386

    // ── main thread ──

    /// Creates and starts the updater once. False — and `unavailable` with the reason — when Sparkle cannot run here.
    private func ensureStarted() -> Bool {
        if started { return updater != nil }
        started = true
        guard NSClassFromString("SPUUpdater") != nil else {
            set("unavailable", reason: "Sparkle.framework is not in this bundle")
            return false
        }
        guard let key = Bundle.main.object(forInfoDictionaryKey: "SUPublicEDKey") as? String, !key.isEmpty else {
            set("unavailable", reason: "the bundle has no SUPublicEDKey")
            return false
        }
        let driver = SPUStandardUserDriver(hostBundle: Bundle.main, delegate: self)
        let u = SPUUpdater(hostBundle: Bundle.main, applicationBundle: Bundle.main, userDriver: driver, delegate: self)
        // Ravilo schedules the checks (FR-R386-3); Sparkle's own timer stays off, as SUEnableAutomaticChecks says too.
        // `automaticallyDownloadsUpdates` below only sticks because the bundle says SUAllowsAutomaticUpdates (see above).
        u.automaticallyChecksForUpdates = false
        lock.lock(); let auto = autoDownload; lock.unlock()
        u.automaticallyDownloadsUpdates = auto
        do { try u.start() } catch {
            set("unavailable", reason: "Sparkle did not start: \(error.localizedDescription)")
            return false
        }
        userDriver = driver
        updater = u
        return true
    }

    func start() { DispatchQueue.main.async { _ = self.ensureStarted() } }

    func setAutoDownload(_ on: Bool) {
        lock.lock(); autoDownload = on; lock.unlock()
        DispatchQueue.main.async { if let u = self.updater { u.automaticallyDownloadsUpdates = on } }
    }

    /// A background check against [url]: no window, the gentle reminders below say what was found.
    func checkInBackground(_ url: String) {
        DispatchQueue.main.async {
            guard self.ensureStarted(), let u = self.updater else { return }
            // One session at a time; a ready or installing update is never replaced by a later check.
            let p = self.current()
            if u.sessionInProgress || p == "ready" || p == "installing" || p == "downloading" || p == "window" { return }
            self.feed = url
            self.userInitiated = false
            self.set("checking")
            self.askedByRavilo = true
            u.checkForUpdatesInBackground()
        }
    }

    /// D9 — Sparkle's own window: release notes, download progress, *Install and Relaunch*.
    func checkWithWindow(_ url: String) {
        DispatchQueue.main.async {
            guard self.ensureStarted(), let u = self.updater else { return }
            if !url.isEmpty { self.feed = url }
            self.userInitiated = true
            self.set("window", version: nil)
            self.askedByRavilo = true
            u.checkForUpdates()
        }
    }

    /// *Restart to update*: Sparkle installs the downloaded update and relaunches, with no window. Without a held
    /// install handler (a download resumed from an earlier launch) Sparkle's own window takes it from here.
    func installAndRelaunch() {
        DispatchQueue.main.async {
            if let h = self.installHandler {
                self.set("installing")
                h()
            } else if let u = self.updater {
                self.userInitiated = true
                self.set("window")
                self.askedByRavilo = true
                u.checkForUpdates()
            }
        }
    }

    // ── SPUUpdaterDelegate ──

    func feedURLString(for updater: SPUUpdater) -> String? { feed.isEmpty ? nil : feed }

    /// FR-R386-1 — Ravilo decides when Sparkle checks. A check Ravilo did not start (Sparkle's own scheduler, which is
    /// off, or a stored preference turning it on) is refused here; the throw is not reported as a failure below.
    func updater(_ updater: SPUUpdater, mayPerform updateCheck: SPUUpdateCheck) throws {
        if askedByRavilo { askedByRavilo = false; return }
        throw NSError(domain: Self.refusedDomain, code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "Ravilo did not ask for this check"])
    }

    private static let refusedDomain = "dev.jellystructure.ravilo.update"

    func updater(_ updater: SPUUpdater, didFindValidUpdate item: SUAppcastItem) {
        lock.lock(); let auto = autoDownload; lock.unlock()
        if userInitiated { set("window", version: item.displayVersionString) }
        else if auto { set("downloading", version: item.displayVersionString) }
        // Toggle off: the gentle reminder below says "available".
        else { set("checking", version: item.displayVersionString) }
    }

    func updaterDidNotFindUpdate(_ updater: SPUUpdater) {
        let p = current()
        if p == "ready" || p == "installing" { return }
        set("notfound")
    }

    func updater(_ updater: SPUUpdater, didDownloadUpdate item: SUAppcastItem) {
        // Extraction follows; `willInstallUpdateOnQuit` (silent) or the window (user-initiated) says the rest.
    }

    func updater(_ updater: SPUUpdater, failedToDownloadUpdate item: SUAppcastItem, error: Error) {
        set("failed", version: item.displayVersionString, failure: "download")
    }

    /// The silent path: a background download that finished and will install on quit. Returning true keeps the
    /// handler, so *Restart to update* installs it now; Sparkle still installs it when the app terminates.
    func updater(_ updater: SPUUpdater, willInstallUpdateOnQuit item: SUAppcastItem, immediateInstallationBlock immediateInstallHandler: @escaping () -> Void) -> Bool {
        installHandler = immediateInstallHandler
        set("ready", version: item.displayVersionString)
        return true
    }

    func updater(_ updater: SPUUpdater, willInstallUpdate item: SUAppcastItem) {
        set("installing", version: item.displayVersionString)
    }

    func updater(_ updater: SPUUpdater, userDidMake choice: SPUUserUpdateChoice, forUpdate updateItem: SUAppcastItem, state: SPUUserUpdateState) {
        // The viewer closed Sparkle's window (D9) without installing: the offer is still there.
        if choice != .install, current() == "window" { set("available", version: updateItem.displayVersionString) }
    }

    func updater(_ updater: SPUUpdater, didAbortWithError error: Error) {
        let p = current()
        if p == "ready" || p == "installing" { return }
        let ns = error as NSError
        if ns.domain == Self.refusedDomain { return }
        guard ns.domain == SUSparkleErrorDomain else { set("failed", failure: "other"); return }
        // SUErrors.h, by number (the enum's Swift spelling is not worth a build failure): 1001 no update; 3000s
        // extraction and signature; 2000/2001 download; 1000…1007 the appcast; 0001…0002 signing configuration.
        switch ns.code {
        case 1001: set("notfound")
        case 3001, 3002, 2: set("failed", failure: "signature")
        case 2000, 2001: set("failed", failure: "download")
        case 1000...1007: set("failed", failure: "feed")
        default: set("failed", failure: "other")
        }
    }

    func updater(_ updater: SPUUpdater, didFinishUpdateCycleFor updateCheck: SPUUpdateCheck, error: Error?) {
        askedByRavilo = false
        // A cycle that ended with neither an update nor an error was a check that found nothing.
        if error == nil, current() == "checking" { set("notfound") }
    }

    // ── SPUStandardUserDriverDelegate — gentle scheduled reminders (FR-R386-4) ──

    var supportsGentleScheduledUpdateReminders: Bool { true }

    /// Ravilo shows the scheduled reminder in its own block and notice: no Sparkle window for a background check.
    func standardUserDriverShouldHandleShowingScheduledUpdate(_ update: SUAppcastItem, andInImmediateFocus immediateFocus: Bool) -> Bool { false }

    func standardUserDriverWillHandleShowingUpdate(_ handleShowingUpdate: Bool, forUpdate update: SUAppcastItem, state: SPUUserUpdateState) {
        if state.userInitiated { return }
        let v = update.displayVersionString
        // A download an earlier launch finished: nothing left but the install.
        if state.stage == .downloaded { set("ready", version: v) } else { set("available", version: v) }
    }
}

private let updateEngine = RaviloUpdater()

/// The target of the menu item's action: AppKit needs an Objective-C object to send it to.
private final class RaviloMenuTarget: NSObject {
    @objc func check(_ sender: Any?) { updateEngine.menuChosen() }
}

private let menuTarget = RaviloMenuTarget()

/// Creates the updater (the snapshot says `unavailable`, and why, when Sparkle cannot run here).
@_cdecl("ravilo_update_start")
public func ravilo_update_start() { updateEngine.start() }

@_cdecl("ravilo_update_set_auto_download")
public func ravilo_update_set_auto_download(_ on: Int32) { updateEngine.setAutoDownload(on != 0) }

@_cdecl("ravilo_update_check_background")
public func ravilo_update_check_background(_ feedURL: UnsafePointer<CChar>) {
    updateEngine.checkInBackground(String(cString: feedURL))
}

@_cdecl("ravilo_update_check_window")
public func ravilo_update_check_window(_ feedURL: UnsafePointer<CChar>) {
    updateEngine.checkWithWindow(String(cString: feedURL))
}

@_cdecl("ravilo_update_install")
public func ravilo_update_install() { updateEngine.installAndRelaunch() }

/// FR-R386-9 — adds or retitles *Check for Updates…* under *About Ravilo*; a choice shows in the snapshot's `menu=`.
@_cdecl("ravilo_update_menu_item")
public func ravilo_update_menu_item(_ title: UnsafePointer<CChar>) { updateEngine.installMenuItem(String(cString: title)) }

/// `key=value` lines: phase · version · failure · reason · auto · bytes · total · menu. Free it.
@_cdecl("ravilo_update_snapshot")
public func ravilo_update_snapshot() -> UnsafeMutablePointer<CChar>? { cString(updateEngine.snapshot()) }
