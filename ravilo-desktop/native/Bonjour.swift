// R330 (FR-R330-2, dev review 1) — the household's Cast devices, found with macOS's own Bonjour (`NWBrowser`), which
// is what macOS 15's Local Network permission is built around. Kotlin polls `ravilo_bonjour_snapshot` about once a
// second while the app is on screen; nothing calls back into the JVM.
//
// A result carries its TXT record (id · fn · md · ca · rs · st); its address is resolved once with a short TCP
// connection to the service, then forgotten. A refusal of Local Network access is reported as state 1, so the sheet
// can say so (FR-R330-8).

import Foundation
import Network

private final class CastBrowser {
    struct Entry {
        var txt: [String: String]
        var host: String?
        var port: Int = 0
        var resolving: NWConnection?
    }

    let queue = DispatchQueue(label: "dev.jellystructure.ravilo.bonjour")
    let lock = NSLock()
    var browser: NWBrowser?
    var entries: [String: Entry] = [:]
    /// 0 browsing (or idle) · 1 Local Network access refused · 2 failed for another reason
    var state: Int32 = 0

    func start() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_googlecast._tcp", domain: nil), using: .tcp)
        b.stateUpdateHandler = { [weak self] st in
            guard let self = self else { return }
            switch st {
            case .ready: self.setState(0)
            case .waiting(let error): if Self.refused(error) { self.setState(1) }
            case .failed(let error): self.setState(Self.refused(error) ? 1 : 2)
            default: break
            }
        }
        b.browseResultsChangedHandler = { [weak self] results, _ in self?.update(results) }
        browser = b
        b.start(queue: queue)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        lock.lock()
        for e in entries.values { e.resolving?.cancel() }
        entries.removeAll()
        state = 0
        lock.unlock()
    }

    /// `kDNSServiceErr_PolicyDenied` (-65570): the user (or the system, for a bare process) refused Local Network access.
    static func refused(_ error: NWError) -> Bool {
        if case .dns(let code) = error { return code == -65570 }
        return false
    }

    func setState(_ s: Int32) { lock.lock(); state = s; lock.unlock() }

    func update(_ results: Set<NWBrowser.Result>) {
        var seen = Set<String>()
        for r in results {
            guard case .service(let name, _, _, _) = r.endpoint else { continue }
            seen.insert(name)
            var txt: [String: String] = [:]
            if case .bonjour(let record) = r.metadata { txt = record.dictionary }
            lock.lock()
            var e = entries[name] ?? Entry(txt: txt)
            e.txt = txt
            let needsAddress = e.host == nil && e.resolving == nil
            entries[name] = e
            lock.unlock()
            if needsAddress { resolve(name, r.endpoint) }
        }
        lock.lock()
        for (name, e) in entries where !seen.contains(name) {
            e.resolving?.cancel()
            entries.removeValue(forKey: name)
        }
        lock.unlock()
    }

    func resolve(_ name: String, _ endpoint: NWEndpoint) {
        let c = NWConnection(to: endpoint, using: .tcp)
        lock.lock(); entries[name]?.resolving = c; lock.unlock()
        c.stateUpdateHandler = { [weak self, weak c] st in
            guard let self = self, let c = c else { return }
            switch st {
            case .ready:
                if case .hostPort(let host, let port) = c.currentPath?.remoteEndpoint {
                    var h = "\(host)"
                    if let pct = h.firstIndex(of: "%") { h = String(h[..<pct]) }
                    self.lock.lock()
                    self.entries[name]?.host = h
                    self.entries[name]?.port = Int(port.rawValue)
                    self.entries[name]?.resolving = nil
                    self.lock.unlock()
                }
                c.cancel()
            case .failed, .cancelled:
                self.lock.lock(); self.entries[name]?.resolving = nil; self.lock.unlock()
            default: break
            }
        }
        c.start(queue: queue)
    }

    /// `state=N`, then one line per resolved device: id · fn · md · ca · rs · host · port, tab-separated.
    func snapshot() -> String {
        lock.lock(); defer { lock.unlock() }
        var lines = ["state=\(state)"]
        for e in entries.values {
            guard let host = e.host, e.port > 0 else { continue }
            let t = e.txt
            let fields = [t["id"] ?? "", t["fn"] ?? "", t["md"] ?? "", t["ca"] ?? "", t["rs"] ?? "", host, String(e.port)]
            lines.append(fields.map { $0.replacingOccurrences(of: "\t", with: " ").replacingOccurrences(of: "\n", with: " ") }.joined(separator: "\t"))
        }
        return lines.joined(separator: "\n")
    }
}

private let castBrowser = CastBrowser()

@_cdecl("ravilo_bonjour_start")
public func ravilo_bonjour_start() { castBrowser.queue.async { castBrowser.start() } }

@_cdecl("ravilo_bonjour_stop")
public func ravilo_bonjour_stop() { castBrowser.queue.async { castBrowser.stop() } }

@_cdecl("ravilo_bonjour_snapshot")
public func ravilo_bonjour_snapshot() -> UnsafeMutablePointer<CChar>? { cString(castBrowser.snapshot()) }
