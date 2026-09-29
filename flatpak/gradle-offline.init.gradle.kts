// R333 (FR-R333-2) — the Flatpak's Gradle build runs with no network: every repository the build would consult —
// the settings' plugin repositories, the version-catalogue plugins, every project's dependencies — becomes the ONE
// directory the manifest filled from `flatpak-sources.json` (Maven layout). Passed with
// `--init-script flatpak/gradle-offline.init.gradle.kts -Pravilo.flatpakOfflineRepo=<dir>` together with `--offline`.
//
// Verified outside the sandbox by flatpak/verify-offline.sh, which replays the captured sources against an empty
// Gradle home — the same thing Flathub's builder does, minus the sandbox.

val repoDir = gradle.startParameter.projectProperties["ravilo.flatpakOfflineRepo"]
    ?: throw GradleException("ravilo.flatpakOfflineRepo is not set: -Pravilo.flatpakOfflineRepo=<the offline-repository directory>")
val offline = java.io.File(repoDir).let {
    if (!it.isDirectory) throw GradleException("ravilo.flatpakOfflineRepo: $it is not a directory")
    it.toURI()
}

fun RepositoryHandler.onlyOffline() {
    clear()
    maven { url = offline }
}

// Before settings.gradle.kts runs: its own `plugins {}` block resolves from pluginManagement's repositories.
beforeSettings {
    pluginManagement.repositories.onlyOffline()
    buildscript.repositories.onlyOffline()
}

// After it ran: settings.gradle.kts added google() / gradlePluginPortal() / mavenCentral() — take them out again.
settingsEvaluated {
    pluginManagement.repositories.onlyOffline()
    dependencyResolutionManagement.repositories.onlyOffline()
}

allprojects {
    buildscript.repositories.onlyOffline()
    repositories.onlyOffline()
}
