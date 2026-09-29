// R328 (FR-R328-4) — libravilo-mac.dylib: the small part of Ravilo on the Mac that only macOS can do.
// Every export is a plain C function (`@_cdecl`) called from Kotlin through JNA (`MacNative`). Strings cross as
// UTF-8 `char*`; a string returned to Kotlin is `strdup`ed here and freed with `ravilo_free`.
//
// Bump `ravilo_abi` whenever an export changes shape: MacNative refuses a library whose ABI it does not know.

import Foundation
import Security
import SystemConfiguration

@_cdecl("ravilo_abi")
public func ravilo_abi() -> Int32 { 3 }

@_cdecl("ravilo_free")
public func ravilo_free(_ p: UnsafeMutablePointer<CChar>?) { free(p) }

func cString(_ s: String?) -> UnsafeMutablePointer<CChar>? {
    guard let s = s else { return nil }
    return strdup(s)
}

// ── The Keychain (D5) ──────────────────────────────────────────────────────────────────────────────
// One generic-password item per token in the login keychain. Its access list is bound to the signing identity of
// the app that created it, which is why every release is signed with the same certificate (R331 FR-R331-3).

private func keychainQuery(_ service: UnsafePointer<CChar>, _ account: UnsafePointer<CChar>) -> [String: Any] {
    [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: String(cString: service),
        kSecAttrAccount as String: String(cString: account),
    ]
}

@_cdecl("ravilo_keychain_get")
public func ravilo_keychain_get(_ service: UnsafePointer<CChar>, _ account: UnsafePointer<CChar>) -> UnsafeMutablePointer<CChar>? {
    var query = keychainQuery(service, account)
    query[kSecReturnData as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
          let data = result as? Data,
          let value = String(data: data, encoding: .utf8) else { return nil }
    return cString(value)
}

@_cdecl("ravilo_keychain_set")
public func ravilo_keychain_set(_ service: UnsafePointer<CChar>, _ account: UnsafePointer<CChar>, _ value: UnsafePointer<CChar>) -> Int32 {
    let data = Data(String(cString: value).utf8)
    let query = keychainQuery(service, account)
    let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
    if update == errSecSuccess { return 0 }
    guard update == errSecItemNotFound else { return update }
    var add = query
    add[kSecValueData as String] = data
    add[kSecAttrLabel as String] = "Ravilo"
    let status = SecItemAdd(add as CFDictionary, nil)
    return status == errSecSuccess ? 0 : status
}

@_cdecl("ravilo_keychain_delete")
public func ravilo_keychain_delete(_ service: UnsafePointer<CChar>, _ account: UnsafePointer<CChar>) -> Int32 {
    let status = SecItemDelete(keychainQuery(service, account) as CFDictionary)
    return (status == errSecSuccess || status == errSecItemNotFound) ? 0 : status
}

// ── The Computer Name (FR-R328-3) — System Settings → General → Sharing ──────────────────────────────

@_cdecl("ravilo_computer_name")
public func ravilo_computer_name() -> UnsafeMutablePointer<CChar>? {
    cString(SCDynamicStoreCopyComputerName(nil, nil) as String?)
}
