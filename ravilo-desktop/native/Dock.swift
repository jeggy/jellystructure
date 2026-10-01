// R342 — the running Dock icon follows the listening mode. In music mode the app hands the Dock the music icon (M7b)
// drawn for the viewer's macOS icon style. In films mode it hands back nil, which puts the installed icon back, and
// macOS styles that one itself. ⌘-Tab follows on its own, because it shows the running app's image. On quit there is
// nothing to undo.
//
// The pictures are rendered by scripts/render-brand-icons.sh (dock) into ravilo-desktop/icons/dock/ and packaged
// beside this library. Each one already carries macOS's icon-grid margin and shadow, because the Dock draws a running
// app's image exactly as given. Tinted is painted here from one-colour layers, since the tint is the viewer's own
// colour. Clear is a picture: an approximation, with no live blur of the desktop behind it.

import AppKit

/** The style the music icon should be drawn in, plus a tint for the Tinted styles, and why (for the log). */
private struct DockStyle {
    let name: String        // default | dark | clear-light | clear-dark | tinted-light | tinted-dark
    let tint: NSColor?
    let why: String
    var key: String {
        let t = tint.flatMap { $0.usingColorSpace(.sRGB) }.map {
            String(format: " #%02X%02X%02X", Int($0.redComponent * 255), Int($0.greenComponent * 255), Int($0.blueComponent * 255))
        } ?? ""
        return "\(name)\(t) (\(why))"
    }
}

private func systemDark() -> Bool { UserDefaults.standard.string(forKey: "AppleInterfaceStyle") == "Dark" }

/**
 * FR-R342-7 — macOS 26 keeps the viewer's *Icon & widget style* in two undocumented global defaults:
 * `AppleIconAppearanceTheme` (RegularDark, RegularAutomatic, ClearLight/Dark/Automatic, TintedLight/Dark/Automatic) and
 * `AppleIconAppearanceTintColor`. An `…Automatic` value follows the system's light or dark. A missing key, a value we
 * don't know or a tint we can't read gives Default, or Dark when the system is dark, so nothing breaks if Apple
 * changes the keys. Before macOS 26 icons have no styles, so it is always Default.
 */
private func resolveStyle() -> DockStyle {
    guard #available(macOS 26, *) else { return DockStyle(name: "default", tint: nil, why: "macOS before 26") }
    let dark = systemDark()
    let fallback = dark ? "dark" : "default"
    guard let raw = UserDefaults.standard.string(forKey: "AppleIconAppearanceTheme") else {
        return DockStyle(name: fallback, tint: nil, why: "no style key")
    }
    func auto(_ light: String, _ darkName: String) -> String { dark ? darkName : light }
    switch raw {
    case "RegularLight": return DockStyle(name: "default", tint: nil, why: raw)
    case "RegularDark": return DockStyle(name: "dark", tint: nil, why: raw)
    case "RegularAutomatic": return DockStyle(name: auto("default", "dark"), tint: nil, why: raw)
    case "ClearLight": return DockStyle(name: "clear-light", tint: nil, why: raw)
    case "ClearDark": return DockStyle(name: "clear-dark", tint: nil, why: raw)
    case "ClearAutomatic": return DockStyle(name: auto("clear-light", "clear-dark"), tint: nil, why: raw)
    case "TintedLight", "TintedDark", "TintedAutomatic":
        guard let tint = readTint() else { return DockStyle(name: fallback, tint: nil, why: "\(raw), tint unreadable") }
        let name = raw == "TintedLight" ? "tinted-light" : raw == "TintedDark" ? "tinted-dark" : auto("tinted-light", "tinted-dark")
        return DockStyle(name: name, tint: tint, why: raw)
    default: return DockStyle(name: fallback, tint: nil, why: "unknown style '\(raw)'")
    }
}

/**
 * The tint. How macOS stores it is undocumented: a named colour (*Graphite* is one), and for a custom colour we accept
 * the shapes a colour is usually kept in (components as text or numbers, or an archived NSColor). With no key at all
 * the tint is the system's accent colour, which is what Tinted shows before a colour is picked. A value we can't read
 * is nil: the caller falls back.
 */
private func readTint() -> NSColor? {
    guard let value = UserDefaults.standard.object(forKey: "AppleIconAppearanceTintColor") else {
        return NSColor.controlAccentColor
    }
    if let s = value as? String {
        let named: [String: NSColor] = [
            "blue": .systemBlue, "purple": .systemPurple, "pink": .systemPink, "red": .systemRed,
            "orange": .systemOrange, "yellow": .systemYellow, "green": .systemGreen, "graphite": .systemGray,
            "gray": .systemGray, "grey": .systemGray, "teal": .systemTeal, "indigo": .systemIndigo, "mint": .systemMint,
            "cyan": .systemCyan, "brown": .systemBrown, "accent": .controlAccentColor, "multicolor": .controlAccentColor,
        ]
        if let c = named[s.lowercased()] { return c }
        let parts = s.split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Double($0) }
        if parts.count >= 3 { return NSColor(srgbRed: parts[0], green: parts[1], blue: parts[2], alpha: 1) }
        return nil
    }
    if let a = value as? [NSNumber], a.count >= 3 {
        return NSColor(srgbRed: a[0].doubleValue, green: a[1].doubleValue, blue: a[2].doubleValue, alpha: 1)
    }
    if let d = value as? Data {
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: NSColor.self, from: d)
    }
    return nil
}

private func load(_ dir: String, _ file: String) -> CGImage? {
    guard let rep = NSBitmapImageRep(data: (try? Data(contentsOf: URL(fileURLWithPath: dir).appendingPathComponent(file))) ?? Data())
    else { return nil }
    return rep.cgImage
}

private func context(_ w: Int, _ h: Int) -> CGContext? {
    CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
              space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
}

/** A one-colour layer painted in [color]: the layer's alpha, the colour's ink. */
private func paint(_ layer: CGImage, _ color: NSColor) -> CGImage? {
    let rect = CGRect(x: 0, y: 0, width: layer.width, height: layer.height)
    guard let ctx = context(layer.width, layer.height) else { return nil }
    ctx.draw(layer, in: rect)
    ctx.setBlendMode(.sourceIn)
    ctx.setFillColor((color.usingColorSpace(.sRGB) ?? color).cgColor)
    ctx.fill(rect)
    return ctx.makeImage()
}

/** One size of the music icon in [style]: a whole picture, or Tinted's three layers painted and stacked. */
private func picture(_ dir: String, _ style: DockStyle, _ suffix: String) -> CGImage? {
    guard style.name.hasPrefix("tinted"), let tint = style.tint else {
        return load(dir, "music-\(style.name)\(suffix).png")
    }
    guard let shadow = load(dir, "music-tinted-shadow\(suffix).png"),
          let tile = load(dir, "music-tinted-tile\(suffix).png"),
          let marks = load(dir, "music-tinted-marks\(suffix).png"),
          let ctx = context(tile.width, tile.height) else { return nil }
    // FR-R342-2 — Tinted light: the tint fills the tile, the marks are white. Tinted dark: a near-black tile, the marks in the tint.
    let light = style.name == "tinted-light"
    let tileInk = light ? tint : NSColor(srgbRed: 0x0C / 255.0, green: 0x13 / 255.0, blue: 0x22 / 255.0, alpha: 1)
    let markInk = light ? NSColor.white : tint
    let rect = CGRect(x: 0, y: 0, width: tile.width, height: tile.height)
    ctx.draw(shadow, in: rect)
    if let t = paint(tile, tileInk) { ctx.draw(t, in: rect) }
    if let m = paint(marks, markInk) { ctx.draw(m, in: rect) }
    return ctx.makeImage()
}

private var lastSet = "films"

/** What `ravilo_dock_icon` would draw now, as one line: the change key Kotlin polls once a second (R338's tick). */
@_cdecl("ravilo_dock_style")
public func ravilo_dock_style() -> UnsafeMutablePointer<CChar>? { cString(resolveStyle().key) }

/**
 * Sets the running Dock icon. [dir] is the folder of music pictures, for music mode; null hands back the installed
 * icon (films mode). Returns 1 when the music icon was set (or films was asked for), 0 when the pictures were missing
 * and the installed icon was kept.
 */
@_cdecl("ravilo_dock_icon")
public func ravilo_dock_icon(_ dir: UnsafePointer<CChar>?) -> Int32 {
    guard let dir = dir.map({ String(cString: $0) }) else {
        DispatchQueue.main.async { NSApp.applicationIconImage = nil; lastSet = "films" }
        return 1
    }
    let style = resolveStyle()
    guard let big = picture(dir, style, "") else {
        DispatchQueue.main.async { NSApp.applicationIconImage = nil; lastSet = "films (no pictures in \(dir))" }
        return 0
    }
    // The Dock tile is at most 128 pt (256 px on Retina) and ⌘-Tab is smaller: one 512 px picture, plus FR-R342-1's
    // bigger-bubble drawing as a second representation for 32 px and below. Both have the image's size in points, so
    // AppKit picks by the pixels it needs.
    let size = NSSize(width: big.width, height: big.height)
    let image = NSImage(size: size)
    let bigRep = NSBitmapImageRep(cgImage: big)
    bigRep.size = size
    image.addRepresentation(bigRep)
    if let small = picture(dir, style, "-32") {
        let rep = NSBitmapImageRep(cgImage: small)
        rep.size = size
        image.addRepresentation(rep)
    }
    let key = "music \(style.key)"
    DispatchQueue.main.async { NSApp.applicationIconImage = image; lastSet = key }
    return 1
}

/** For the test driver: what was handed to the Dock last, and that image (or the installed icon) written as a PNG. */
@_cdecl("ravilo_dock_snapshot")
public func ravilo_dock_snapshot(_ path: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>? {
    let file = String(cString: path)
    var out = ""
    let work = {
        out = lastSet
        guard let img = NSApp.applicationIconImage,
              let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 512, pixelsHigh: 512, bitsPerSample: 8,
                                         samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                         bytesPerRow: 0, bitsPerPixel: 0) else { return }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        img.draw(in: NSRect(x: 0, y: 0, width: 512, height: 512))
        NSGraphicsContext.restoreGraphicsState()
        if let png = rep.representation(using: .png, properties: [:]), (try? png.write(to: URL(fileURLWithPath: file))) != nil {
            out += " · wrote \(file)"
        }
    }
    if Thread.isMainThread { work() } else { DispatchQueue.main.sync(execute: work) }
    return cString(out)
}
