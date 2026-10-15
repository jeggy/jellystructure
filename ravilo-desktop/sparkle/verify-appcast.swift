// R386 (FR-R386-2, acceptance 2) — proves, in CI, that an appcast's `sparkle:edSignature` verifies against the
// `SUPublicEDKey` the bundle carries, over the exact `.dmg` the enclosure names. Run by deploy-macos.yml:
//
//   swift ravilo-desktop/sparkle/verify-appcast.swift <appcast.xml> <ravilo-mac-N.dmg> <SUPublicEDKey>
//
// Sparkle's EdDSA is plain Ed25519 over the archive's bytes; the key and the signature are base64. Exit 0 and one
// line on success; exit 1 with the reason otherwise. Reads no secret — the public key is in the bundle.

import CryptoKit
import Foundation

func fail(_ why: String) -> Never {
    FileHandle.standardError.write(Data("appcast check FAILED: \(why)\n".utf8))
    exit(1)
}

let args = CommandLine.arguments
guard args.count == 4 else { fail("usage: verify-appcast.swift <appcast.xml> <archive> <SUPublicEDKey>") }
guard let xml = try? String(contentsOfFile: args[1], encoding: .utf8) else { fail("cannot read \(args[1])") }
guard let archive = FileManager.default.contents(atPath: args[2]) else { fail("cannot read \(args[2])") }
guard let keyData = Data(base64Encoded: args[3].trimmingCharacters(in: .whitespacesAndNewlines)), keyData.count == 32 else {
    fail("SUPublicEDKey is not 32 bytes of base64")
}

func attribute(_ name: String) -> String? {
    guard let r = xml.range(of: "\(name)=\"") else { return nil }
    let rest = xml[r.upperBound...]
    guard let end = rest.firstIndex(of: "\"") else { return nil }
    return String(rest[..<end])
}

guard let signature = attribute("sparkle:edSignature"), let sig = Data(base64Encoded: signature) else {
    fail("the appcast has no readable sparkle:edSignature")
}
guard let length = attribute("length"), Int(length) == archive.count else {
    fail("the enclosure's length (\(attribute("length") ?? "none")) is not the archive's size (\(archive.count))")
}
guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: keyData) else { fail("SUPublicEDKey is not an Ed25519 key") }
guard key.isValidSignature(sig, for: archive) else { fail("the signature does not verify against SUPublicEDKey") }
print("appcast signature verifies against SUPublicEDKey (\(archive.count) bytes)")
