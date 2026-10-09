// R329 — the Mac's player: AVPlayer behind a handle, driven from Kotlin (`MacPlayer` in ravilo-ui) through JNA.
//
// Every call comes from one Kotlin thread (Compose's), and the state Kotlin reads is polled, never pushed: there
// are no callbacks into the JVM from here. AVFoundation's own threads only set flags under the box's lock; the work
// those flags call for (the start seek, AVPlayer's subtitles off, a queued audio pick, starting) happens in
// `ravilo_player_tick`, which Kotlin calls on its thread while a player is loaded.
//
// Frames are copied (FR-R329-1 dev review 1, path (b)): Kotlin owns the destination memory,
// `ravilo_player_copy_frame` writes the newest decoded frame into it as tightly packed BGRA, and Skia wraps that
// memory without a second copy. Audio-only players (music, audiobooks) attach no video output.
//
// AVPlayer's own subtitles are never shown (FR-R329-5): the legible group is switched off once the item is ready,
// and Compose draws every text subtitle from the ticket's WebVTT.

import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import QuartzCore

final class RaviloPlayerBox: NSObject {
    let player = AVPlayer()
    let lock = NSLock()
    var item: AVPlayerItem?
    var output: AVPlayerItemVideoOutput?
    var statusObservation: NSKeyValueObservation?
    var observers: [NSObjectProtocol] = []

    // Guarded by `lock` — written by AVFoundation's notification and completion threads, read by Kotlin.
    var ready = false
    var failedReason: String?
    var ended = false
    var firstFrame = false
    var seeking = false
    var stalls: Int64 = 0

    // Only touched from the Kotlin thread.
    var wantPlay = false
    var rate: Float = 1.0
    var pendingStartMs: Int64 = 0
    var pendingAudio: Int = -1
    var readyHandled = false

    override init() {
        super.init()
        player.automaticallyWaitsToMinimizeStalling = true
        player.appliesMediaSelectionCriteriaAutomatically = false
        player.allowsExternalPlayback = false
    }

    @discardableResult
    func locked<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }

    func teardownItem() {
        player.pause()
        statusObservation?.invalidate()
        statusObservation = nil
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers.removeAll()
        locked {
            if let out = output, let it = item { it.remove(out) }
            output = nil
            ready = false; failedReason = nil; ended = false; firstFrame = false; seeking = false; stalls = 0
        }
        player.replaceCurrentItem(with: nil)
        item = nil
        pendingAudio = -1
        pendingStartMs = 0
        readyHandled = false
    }

    /// The item became playable: take the start position, switch AVPlayer's subtitles off, apply a queued audio pick.
    func onReady() {
        guard let it = item else { return }
        if let legible = it.asset.mediaSelectionGroup(forMediaCharacteristic: .legible) {
            it.select(nil, in: legible)
        }
        applyPendingAudio()
        let start = pendingStartMs
        pendingStartMs = 0
        if start > 0 {
            locked { seeking = true }
            it.seek(to: CMTime(value: start, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                guard let self = self else { return }
                self.locked { self.seeking = false }
            }
        }
    }

    func startIfWanted() {
        let canStart = locked { ready && !seeking }
        if wantPlay && canStart && player.timeControlStatus == .paused {
            player.playImmediately(atRate: rate)
        }
    }

    /// FR-R329-4 — a composed master names each rendition `a{position} …` (the backend's composeMaster); anything
    /// else is matched by its place in the group.
    func applyPendingAudio() {
        guard pendingAudio >= 0, let it = item, locked({ ready }),
              let group = it.asset.mediaSelectionGroup(forMediaCharacteristic: .audible) else { return }
        let options = group.options
        let tag = "a\(pendingAudio)"
        let byName = options.first { $0.displayName == tag || $0.displayName.hasPrefix(tag + " ") }
        let chosen = byName ?? (pendingAudio < options.count ? options[pendingAudio] : nil)
        if let o = chosen { it.select(o, in: group) }
        pendingAudio = -1
    }
}

private var players: [Int64: RaviloPlayerBox] = [:]
private var nextHandle: Int64 = 1
private let registry = NSLock()

private func box(_ h: Int64) -> RaviloPlayerBox? {
    registry.lock(); defer { registry.unlock() }
    return players[h]
}

@_cdecl("ravilo_player_create")
public func ravilo_player_create() -> Int64 {
    let b = RaviloPlayerBox()
    registry.lock()
    let h = nextHandle
    nextHandle += 1
    players[h] = b
    registry.unlock()
    return h
}

/// Loads [url] at [startMs]. [mime] (may be empty) names the type of a file whose URL has no extension (a
/// direct-played song); [audioOnly] attaches no video output.
@_cdecl("ravilo_player_load")
public func ravilo_player_load(_ h: Int64, _ url: UnsafePointer<CChar>, _ mime: UnsafePointer<CChar>, _ startMs: Int64, _ audioOnly: Int32) {
    guard let b = box(h) else { return }
    b.teardownItem()
    guard let u = URL(string: String(cString: url)) else {
        b.locked { b.failedReason = "bad url" }
        return
    }
    var options: [String: Any] = [:]
    let mimeType = String(cString: mime)
    if !mimeType.isEmpty { options[AVURLAssetOverrideMIMETypeKey] = mimeType }
    let asset = AVURLAsset(url: u, options: options)
    let it = AVPlayerItem(asset: asset)
    it.audioTimePitchAlgorithm = .timeDomain   // a book at 1.5× keeps its voice
    if audioOnly == 0 {
        // 309 (FR-309-9) — keep 40 s ahead, as the other players do: a step to a rung whose encode has just started
        // is covered by what is already buffered. The peak (`ravilo_player_set_peak_bitrate`) bounds the climb.
        it.preferredForwardBufferDuration = 40
    }
    if audioOnly == 0 {
        let out = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        ])
        it.add(out)
        b.locked { b.output = out }
    }
    b.pendingStartMs = max(0, startMs)
    b.statusObservation = it.observe(\.status, options: [.new]) { [weak b] item, _ in
        guard let b = b else { return }
        switch item.status {
        case .readyToPlay:
            b.locked { b.ready = true }
        case .failed:
            b.locked { b.failedReason = item.error?.localizedDescription ?? "failed" }
        default: break
        }
    }
    let center = NotificationCenter.default
    b.observers.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: it, queue: nil) { [weak b] _ in
        b?.locked { b?.ended = true }
    })
    b.observers.append(center.addObserver(forName: .AVPlayerItemPlaybackStalled, object: it, queue: nil) { [weak b] _ in
        b?.locked { b?.stalls += 1 }
    })
    b.observers.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: it, queue: nil) { [weak b] note in
        let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
        b?.locked { b?.failedReason = err?.localizedDescription ?? "failed to play to the end" }
    })
    b.item = it
    b.player.replaceCurrentItem(with: it)
}

@_cdecl("ravilo_player_play")
public func ravilo_player_play(_ h: Int64) {
    guard let b = box(h) else { return }
    b.wantPlay = true
    if b.locked({ b.ended }) {
        b.locked { b.ended = false }
        b.player.seek(to: .zero)
    }
    b.startIfWanted()
}

@_cdecl("ravilo_player_pause")
public func ravilo_player_pause(_ h: Int64) {
    guard let b = box(h) else { return }
    b.wantPlay = false
    b.player.pause()
}

@_cdecl("ravilo_player_seek")
public func ravilo_player_seek(_ h: Int64, _ ms: Int64) {
    guard let b = box(h) else { return }
    if !b.locked({ b.ready }) { b.pendingStartMs = max(0, ms); return }
    b.locked { b.seeking = true; b.ended = false }
    b.player.seek(to: CMTime(value: max(0, ms), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak b] _ in
        b?.locked { b?.seeking = false }
    }
}

/// The work AVFoundation's flags call for, on the caller's thread: once ready, the start seek, subtitles off and a
/// queued audio pick; then, whenever the viewer wants it playing and no seek is pending, play.
@_cdecl("ravilo_player_tick")
public func ravilo_player_tick(_ h: Int64) {
    guard let b = box(h), b.item != nil else { return }
    if !b.readyHandled && b.locked({ b.ready }) {
        b.readyHandled = true
        b.onReady()
    }
    // R290's start latch waits for a first frame before it plays — ExoPlayer draws one while paused. AVPlayer's video
    // output only delivers frames once it runs, so waiting for a copied frame would wait forever. Ready and parked at
    // the start position is what the latch needs to know; the picture follows the moment it plays.
    if b.readyHandled && b.pendingStartMs == 0 {
        b.locked { if !b.seeking && b.failedReason == nil { b.firstFrame = true } }
    }
    if b.readyHandled { b.startIfWanted() }
}

/// FR-R323-5 — a book's speed; films and songs stay at 1×.
@_cdecl("ravilo_player_set_rate")
public func ravilo_player_set_rate(_ h: Int64, _ rate: Float) {
    guard let b = box(h) else { return }
    b.rate = max(0.25, min(rate, 4.0))
    b.player.defaultRate = b.rate
    if b.player.timeControlStatus != .paused { b.player.rate = b.rate }
}

/// 309 (FR-309-9) — the highest variant AVPlayer may pick on its own (`preferredPeakBitRate`, bits/s; 0 = no bound).
/// Kotlin raises it one rung at a time once enough is buffered and lowers it when the buffer runs low.
@_cdecl("ravilo_player_set_peak_bitrate")
public func ravilo_player_set_peak_bitrate(_ h: Int64, _ bps: Double) {
    guard let b = box(h), let it = b.item else { return }
    it.preferredPeakBitRate = max(0, bps)
}

@_cdecl("ravilo_player_set_volume")
public func ravilo_player_set_volume(_ h: Int64, _ volume: Float) {
    box(h)?.player.volume = max(0, min(volume, 1))
}

@_cdecl("ravilo_player_select_audio")
public func ravilo_player_select_audio(_ h: Int64, _ index: Int32) {
    guard let b = box(h) else { return }
    b.pendingAudio = Int(index)
    b.applyPendingAudio()
}

/// Fills [out] (at least [count] Int64s) with the player's state, in the order `MacPlayerState` reads it:
/// 0 position ms · 1 duration ms (-1 unknown/live) · 2 buffered-to ms · 3 timeControl (0 paused, 1 waiting, 2 playing)
/// · 4 item (0 loading, 1 ready, 2 failed) · 5 ended · 6 first frame shown · 7 width · 8 height · 9 dropped frames
/// · 10 stalls · 11 seeking · 12 observed bitrate (bit/s) · 13 wants to play.
@_cdecl("ravilo_player_state")
public func ravilo_player_state(_ h: Int64, _ out: UnsafeMutablePointer<Int64>, _ count: Int32) {
    let n = Int(count)
    for i in 0..<n { out[i] = 0 }
    guard let b = box(h), n >= 14 else { return }
    func ms(_ t: CMTime) -> Int64 { t.isValid && t.isNumeric ? Int64(t.seconds * 1000) : 0 }
    let flags = b.locked { (b.ready, b.failedReason != nil, b.ended, b.firstFrame, b.stalls, b.seeking) }
    out[0] = ms(b.player.currentTime())
    if let it = b.item {
        let d = it.duration
        out[1] = (d.isValid && d.isNumeric && !d.isIndefinite) ? ms(d) : -1
        let now = b.player.currentTime()
        for value in it.loadedTimeRanges {
            let r = value.timeRangeValue
            if CMTimeRangeContainsTime(r, time: now) { out[2] = ms(CMTimeRangeGetEnd(r)) }
        }
        if let events = it.accessLog()?.events {
            out[9] = Int64(events.reduce(0) { $0 + max(0, $1.numberOfDroppedVideoFrames) })
            if let last = events.last, last.observedBitrate > 0 { out[12] = Int64(last.observedBitrate) }
        }
        out[7] = Int64(it.presentationSize.width)
        out[8] = Int64(it.presentationSize.height)
    } else {
        out[1] = -1
    }
    switch b.player.timeControlStatus {
    case .paused: out[3] = 0
    case .waitingToPlayAtSpecifiedRate: out[3] = 1
    case .playing: out[3] = 2
    @unknown default: out[3] = 0
    }
    out[4] = flags.1 ? 2 : (flags.0 ? 1 : 0)
    out[5] = flags.2 ? 1 : 0
    out[6] = flags.3 ? 1 : 0
    out[10] = flags.4
    out[11] = flags.5 ? 1 : 0
    out[13] = b.wantPlay ? 1 : 0
}

/// Why the item failed, or null. Free with `ravilo_free`.
@_cdecl("ravilo_player_error")
public func ravilo_player_error(_ h: Int64) -> UnsafeMutablePointer<CChar>? {
    guard let b = box(h) else { return nil }
    return cString(b.locked { b.failedReason })
}

/// The audible group's options as `displayName\textendedLanguageTag` lines (for the build notes' rendition check).
@_cdecl("ravilo_player_audio_options")
public func ravilo_player_audio_options(_ h: Int64) -> UnsafeMutablePointer<CChar>? {
    guard let b = box(h), let it = b.item, b.locked({ b.ready }),
          let group = it.asset.mediaSelectionGroup(forMediaCharacteristic: .audible) else { return nil }
    let selected = it.currentMediaSelection.selectedMediaOption(in: group)
    let lines = group.options.map { o in "\(o == selected ? "*" : "")\(o.displayName)\t\(o.extendedLanguageTag ?? "")" }
    return cString(lines.joined(separator: "\n"))
}

/// One line of what AVFoundation itself says about the item, for the app's log: its status, why the player waits,
/// the last HLS error and access log entries. URLs lose their query string, so no token reaches a log.
@_cdecl("ravilo_player_debug")
public func ravilo_player_debug(_ h: Int64) -> UnsafeMutablePointer<CChar>? {
    guard let b = box(h) else { return cString("no player") }
    guard let it = b.item else { return cString("no item") }
    func path(_ uri: String?) -> String { (uri ?? "").components(separatedBy: "?").first ?? "" }
    var parts: [String] = []
    let status: String
    switch it.status {
    case .readyToPlay: status = "ready"
    case .failed: status = "failed"
    default: status = "unknown"
    }
    parts.append("item=\(status)")
    switch b.player.timeControlStatus {
    case .paused: parts.append("player=paused")
    case .waitingToPlayAtSpecifiedRate: parts.append("player=waiting(\(b.player.reasonForWaitingToPlay?.rawValue ?? "?"))")
    case .playing: parts.append("player=playing")
    @unknown default: parts.append("player=?")
    }
    let flags = b.locked { (b.ready, b.seeking, b.firstFrame, b.failedReason) }
    parts.append("readySeen=\(flags.0) readyHandled=\(b.readyHandled) seeking=\(flags.1) firstFrame=\(flags.2) want=\(b.wantPlay) pendingStartMs=\(b.pendingStartMs)")
    parts.append("t=\(String(format: "%.1f", b.player.currentTime().seconds)) size=\(Int(it.presentationSize.width))x\(Int(it.presentationSize.height))")
    let ranges = it.loadedTimeRanges.map { v -> String in
        let r = v.timeRangeValue
        return String(format: "%.1f+%.1f", r.start.seconds, r.duration.seconds)
    }
    parts.append("loaded=[\(ranges.joined(separator: ","))]")
    if let e = it.error as NSError? { parts.append("error=\(e.domain)/\(e.code) \(e.localizedDescription)") }
    if let r = flags.3 { parts.append("failedReason=\(r)") }
    if let ev = it.errorLog()?.events.last {
        parts.append("errorLog=\(ev.errorStatusCode) \(ev.errorDomain) \(ev.errorComment ?? "") \(path(ev.uri))")
    }
    if let ev = it.accessLog()?.events.last {
        parts.append("accessLog=requests:\(ev.numberOfMediaRequests) bitrate:\(Int(ev.indicatedBitrate)) stalls:\(ev.numberOfStalls) \(path(ev.uri))")
    }
    parts.append("hasOutput=\(b.output != nil)")
    return cString(parts.joined(separator: " "))
}

/// FR-R329-1 path (b) — copies the newest frame into [dst] as tightly packed BGRA and returns 1; 0 when there is no
/// new frame; -1 when [capacity] is too small, with the frame's size in `dims` so the caller can grow its buffer.
/// `dims` receives width and height.
@_cdecl("ravilo_player_copy_frame")
public func ravilo_player_copy_frame(_ h: Int64, _ dst: UnsafeMutableRawPointer?, _ capacity: Int64, _ dims: UnsafeMutablePointer<Int32>) -> Int32 {
    guard let b = box(h) else { return 0 }
    b.lock.lock()
    defer { b.lock.unlock() }
    guard let out = b.output else { return 0 }
    let t = out.itemTime(forHostTime: CACurrentMediaTime())
    guard out.hasNewPixelBuffer(forItemTime: t),
          let pb = out.copyPixelBuffer(forItemTime: t, itemTimeForDisplay: nil) else { return 0 }
    CVPixelBufferLockBaseAddress(pb, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
    let width = CVPixelBufferGetWidth(pb)
    let height = CVPixelBufferGetHeight(pb)
    dims[0] = Int32(width)
    dims[1] = Int32(height)
    let dstRow = width * 4
    guard let dst = dst, let src = CVPixelBufferGetBaseAddress(pb), Int64(dstRow * height) <= capacity else { return -1 }
    let srcRow = CVPixelBufferGetBytesPerRow(pb)
    if srcRow == dstRow {
        memcpy(dst, src, dstRow * height)
    } else {
        for y in 0..<height { memcpy(dst + y * dstRow, src + y * srcRow, dstRow) }
    }
    if !b.seeking { b.firstFrame = true }
    return 1
}

@_cdecl("ravilo_player_release")
public func ravilo_player_release(_ h: Int64) {
    registry.lock()
    let b = players.removeValue(forKey: h)
    registry.unlock()
    b?.teardownItem()
}

// ── FR-R329-3 — what this Mac really decodes ──────────────────────────────────────────────────────────

/// 1 when AVFoundation says it can play [mime] (an extended MIME type with codecs, e.g. `video/mp4; codecs="hvc1.1.6.L150.90"`).
@_cdecl("ravilo_caps_playable")
public func ravilo_caps_playable(_ mime: UnsafePointer<CChar>) -> Int32 {
    AVURLAsset.isPlayableExtendedMIMEType(String(cString: mime)) ? 1 : 0
}
