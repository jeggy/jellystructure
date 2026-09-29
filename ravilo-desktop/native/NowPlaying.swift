// R329 (FR-R329-10) — Control Center's Now Playing card and the media keys (the keyboard's, AirPods', Control
// Center's). Both centres must be touched on the main thread (dev review 7): every call hops there. The commands
// come back through one C callback (`command`, `value`) that Kotlin posts to Compose's thread.
//
// Commands: 1 play · 2 pause · 3 toggle · 4 next · 5 previous · 6 seek to [value] seconds · 7 skip forward [value]
// seconds · 8 skip back [value] seconds · 9 stop.
// Modes: 0 a film (play/pause/seek) · 1 music (+ next/previous) · 2 an audiobook (+ ±30 s instead of next/previous).

import AppKit
import Foundation
import MediaPlayer

public typealias RaviloRemoteCallback = @convention(c) (Int32, Double) -> Void

private var remoteCallback: RaviloRemoteCallback?
private var commandsInstalled = false
private var artwork: MPMediaItemArtwork?

private func send(_ command: Int32, _ value: Double = 0) -> MPRemoteCommandHandlerStatus {
    guard let cb = remoteCallback else { return .noActionableNowPlayingItem }
    cb(command, value)
    return .success
}

private func installCommands() {
    guard !commandsInstalled else { return }
    commandsInstalled = true
    let c = MPRemoteCommandCenter.shared()
    c.playCommand.addTarget { _ in send(1) }
    c.pauseCommand.addTarget { _ in send(2) }
    c.togglePlayPauseCommand.addTarget { _ in send(3) }
    c.nextTrackCommand.addTarget { _ in send(4) }
    c.previousTrackCommand.addTarget { _ in send(5) }
    c.changePlaybackPositionCommand.addTarget { event in
        guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
        return send(6, e.positionTime)
    }
    c.skipForwardCommand.preferredIntervals = [30]
    c.skipBackwardCommand.preferredIntervals = [30]
    c.skipForwardCommand.addTarget { event in send(7, (event as? MPSkipIntervalCommandEvent)?.interval ?? 30) }
    c.skipBackwardCommand.addTarget { event in send(8, (event as? MPSkipIntervalCommandEvent)?.interval ?? 30) }
    c.stopCommand.addTarget { _ in send(9) }
}

@_cdecl("ravilo_nowplaying_set_handler")
public func ravilo_nowplaying_set_handler(_ cb: RaviloRemoteCallback?) {
    DispatchQueue.main.async {
        remoteCallback = cb
        installCommands()
    }
}

@_cdecl("ravilo_nowplaying_set_mode")
public func ravilo_nowplaying_set_mode(_ mode: Int32) {
    DispatchQueue.main.async {
        let c = MPRemoteCommandCenter.shared()
        c.nextTrackCommand.isEnabled = mode == 1
        c.previousTrackCommand.isEnabled = mode == 1
        c.skipForwardCommand.isEnabled = mode == 2
        c.skipBackwardCommand.isEnabled = mode == 2
        c.changePlaybackPositionCommand.isEnabled = true
    }
}

/// The card: title, artist or series, album or book, where it is and how fast. [durationMs] < 0 = unknown.
@_cdecl("ravilo_nowplaying_update")
public func ravilo_nowplaying_update(_ title: UnsafePointer<CChar>, _ artist: UnsafePointer<CChar>, _ album: UnsafePointer<CChar>,
                                     _ durationMs: Int64, _ positionMs: Int64, _ rate: Double, _ playing: Int32, _ video: Int32) {
    let t = String(cString: title), a = String(cString: artist), al = String(cString: album)
    DispatchQueue.main.async {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMs) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: playing != 0 ? rate : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: NSNumber(value: (video != 0 ? MPNowPlayingInfoMediaType.video : MPNowPlayingInfoMediaType.audio).rawValue),
        ]
        if !a.isEmpty { info[MPMediaItemPropertyArtist] = a }
        if !al.isEmpty { info[MPMediaItemPropertyAlbumTitle] = al }
        if durationMs >= 0 { info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000.0 }
        if let art = artwork { info[MPMediaItemPropertyArtwork] = art }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = playing != 0 ? .playing : .paused
    }
}

/// The cover, as encoded image bytes (JPEG/PNG); [length] 0 clears it. Applied with the next update.
@_cdecl("ravilo_nowplaying_artwork")
public func ravilo_nowplaying_artwork(_ bytes: UnsafePointer<UInt8>?, _ length: Int64) {
    let data = (bytes != nil && length > 0) ? Data(bytes: bytes!, count: Int(length)) : nil
    DispatchQueue.main.async {
        guard let data = data, let image = NSImage(data: data) else { artwork = nil; return }
        artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        if var info = MPNowPlayingInfoCenter.default().nowPlayingInfo {
            info[MPMediaItemPropertyArtwork] = artwork
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }
}

@_cdecl("ravilo_nowplaying_clear")
public func ravilo_nowplaying_clear() {
    DispatchQueue.main.async {
        artwork = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        MPNowPlayingInfoCenter.default().playbackState = .stopped
    }
}
