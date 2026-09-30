// R337 (FR-R337-5) — the Mac window's own shape, the parts only AppKit can give: the traffic lights sitting inside
// the sidebar's glass where macOS puts them in a toolbar window, a window that moves when its title area is dragged,
// and the system's double-click.
//
// The window is AWT's (a transparent title bar over a full-size content view, set from Kotlin). AWT's view takes
// every click in the title area and never lets the window move, so the app says where the window may be dragged
// from (`ravilo_window_drag`, called on a press on empty chrome) and this file does it the system's way.

import AppKit

private var lightsCentre: CGPoint? = nil      // the close button's centre from the window's top-left, in points
private var titledCentres: [String: CGPoint] = [:]   // a window with a place of its own for them (Settings), by its title
private var lastMouseDown: NSEvent? = nil
private var installed = false

/** Ravilo's windows whose content runs under their title bar: the main one and Settings. About is left alone. */
private func raviloWindows() -> [NSWindow] {
    NSApp.windows.filter { $0.styleMask.contains(.titled) && $0.styleMask.contains(.fullSizeContentView) && !($0 is NSPanel) }
}

private func layoutLights(_ window: NSWindow) {
    guard let centre = titledCentres[window.title] ?? lightsCentre,
          let close = window.standardWindowButton(.closeButton),
          let mini = window.standardWindowButton(.miniaturizeButton),
          let zoom = window.standardWindowButton(.zoomButton),
          let container = close.superview?.superview else { return }
    if window.styleMask.contains(.fullScreen) { return }   // full screen hides them; macOS lays them out itself
    let gap = mini.frame.minX - close.frame.maxX
    let height = centre.y * 2
    // The title bar's container grows to a band whose middle is the lights' centre line; the buttons are centred in it.
    container.frame = NSRect(x: 0, y: window.frame.height - height, width: window.frame.width, height: height)
    var x = centre.x - close.frame.width / 2
    for b in [close, mini, zoom] {
        b.setFrameOrigin(NSPoint(x: x, y: (height - b.frame.height) / 2))
        x += b.frame.width + max(gap, 6)
    }
}

/**
 * AWT's view tells AppKit that a press on it may move the window, so AppKit takes every press in the title area for
 * itself and the controls the app draws there (the toolbar's Back, *Play on…*) never hear a click. Here the view says
 * no; the app moves the window from its own empty chrome instead (`ravilo_window_drag`). The traffic lights are
 * AppKit's own buttons above the view and are not affected.
 */
private func letTitleAreaClicksThrough() {
    guard let cls = NSClassFromString("AWTView") else { return }
    let sel = #selector(getter: NSView.mouseDownCanMoveWindow)
    guard let method = class_getInstanceMethod(cls, sel) else { return }
    let never: @convention(block) (AnyObject) -> Bool = { _ in false }
    class_replaceMethod(cls, sel, imp_implementationWithBlock(never), method_getTypeEncoding(method))
}

private func install() {
    if installed { return }
    installed = true
    letTitleAreaClicksThrough()
    let nc = NotificationCenter.default
    for name in [NSWindow.didResizeNotification, NSWindow.didEndLiveResizeNotification, NSWindow.didExitFullScreenNotification,
                 NSWindow.didBecomeKeyNotification, NSWindow.didResignKeyNotification, NSWindow.didChangeScreenNotification] {
        nc.addObserver(forName: name, object: nil, queue: .main) { note in
            if let w = note.object as? NSWindow, raviloWindows().contains(w) { layoutLights(w) }
        }
    }
    NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown]) { event in
        lastMouseDown = event
        return event
    }
}

/** Puts the centre of the close button at (x, y) from the window's top-left, the other two beside it, and keeps them there. */
@_cdecl("ravilo_window_lights")
public func ravilo_window_lights(_ x: Double, _ y: Double) {
    DispatchQueue.main.async {
        lightsCentre = CGPoint(x: x, y: y)
        install()
        raviloWindows().forEach(layoutLights)
    }
}

/** The same for one window, named by its title: Settings keeps its lights over its tabs wherever the main window's are. */
@_cdecl("ravilo_window_lights_titled")
public func ravilo_window_lights_titled(_ title: UnsafePointer<CChar>, _ x: Double, _ y: Double) {
    let name = String(cString: title)
    DispatchQueue.main.async {
        titledCentres[name] = CGPoint(x: x, y: y)
        install()
        raviloWindows().forEach(layoutLights)
    }
}

/** A press landed on empty chrome: the window follows the pointer, as a title bar's does (snapping and tiling included). */
@_cdecl("ravilo_window_drag")
public func ravilo_window_drag() {
    DispatchQueue.main.async {
        install()
        guard let event = lastMouseDown, let window = event.window, raviloWindows().contains(window) else { return }
        // A double-click does what System Settings ▸ Desktop & Dock says a title bar's does.
        if event.clickCount == 2 {
            switch UserDefaults.standard.string(forKey: "AppleActionOnDoubleClick") ?? "Maximize" {
            case "Minimize": window.performMiniaturize(nil)
            case "None": break
            default: window.performZoom(nil)
            }
            return
        }
        if NSEvent.pressedMouseButtons & 1 != 0 { window.performDrag(with: event) }
    }
}

/** For the test driver: where the lights are and who takes a click in the title area. */
@_cdecl("ravilo_window_debug")
public func ravilo_window_debug() -> UnsafeMutablePointer<CChar>? {
    var out = ""
    let work = {
        for w in NSApp.windows where w.isVisible {
            let h = w.frame.height
            out += "window '\(w.title)' \(Int(w.frame.width))x\(Int(h)) mask=\(w.styleMask.rawValue) full=\(w.styleMask.contains(.fullSizeContentView))"
            for (name, kind) in [("close", NSWindow.ButtonType.closeButton), ("mini", .miniaturizeButton), ("zoom", .zoomButton)] {
                if let b = w.standardWindowButton(kind) {
                    let f = b.convert(b.bounds, to: nil)
                    out += " \(name)=(\(Int(f.midX)),\(Int(h - f.midY)) \(Int(f.width))x\(Int(f.height)))"
                }
            }
            if let frameView = w.contentView?.superview {
                for (x, y) in [(120.0, 4.0), (120.0, 20.0), (120.0, 45.0), (600.0, 10.0), (600.0, 30.0), (600.0, 80.0)] {
                    let hit = frameView.hitTest(NSPoint(x: x, y: h - y))
                    out += " hit(\(Int(x)),\(Int(y)))=\(hit.map { String(describing: type(of: $0)) } ?? "nil")/\(hit?.mouseDownCanMoveWindow ?? false)"
                }
            }
            out += "\n"
        }
    }
    if Thread.isMainThread { work() } else { DispatchQueue.main.sync(execute: work) }
    return cString(out)
}
