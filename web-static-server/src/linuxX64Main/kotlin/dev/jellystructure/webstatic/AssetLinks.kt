package dev.jellystructure.webstatic

// DEB-2233 (FR-7) — Digital Asset Links for the Ravilo phone app. Android verifies an `autoVerify` App Link by fetching
// https://<host>/.well-known/assetlinks.json and finding the installed package, signed by one of the listed certificates.
// Two certificates are expected: the Play signing key (what a Play install is signed with) and the upload key (what a
// sideloaded CI build is signed with). Both are public fingerprints, supplied by the operator at run time and never committed.

/** The package the Ravilo Android app is published as (`ravilo.applicationId` in gradle.properties). */
internal const val DEFAULT_ANDROID_PACKAGE = "dev.jellystructure.ravilo"

private val FINGERPRINT = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")

/**
 * The file's body, or null when there is nothing to say. [certs] is a list separated by commas or whitespace; each entry is a
 * SHA-256 fingerprint as `keytool` prints it (32 hex bytes, colon-separated, any case). An entry that is not one is dropped,
 * so a typo cannot publish a statement that names nothing; all dropped, or a blank package, is null.
 */
internal fun assetLinksJson(packageName: String, certs: String): String? {
    val pkg = packageName.trim()
    if (pkg.isEmpty() || !pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return null
    val prints = certs.split(',', ' ', '\n', '\t').map { it.trim().uppercase() }.filter { FINGERPRINT.matches(it) }.distinct()
    if (prints.isEmpty()) return null
    return "[{\"relation\":[\"delegate_permission/common.handle_all_urls\"],\"target\":{\"namespace\":\"android_app\"," +
        "\"package_name\":\"$pkg\",\"sha256_cert_fingerprints\":[" + prints.joinToString(",") { "\"$it\"" } + "]}}]"
}
