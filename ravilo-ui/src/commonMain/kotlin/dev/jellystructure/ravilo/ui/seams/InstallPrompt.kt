package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

// R263 (FR-R263-8) — the "install the web app" card only ever makes sense on the web build: the
// Android/TV app is a native APK, installed from the Play Store or sideloaded, never a PWA install
// target. `:ravilo-android`'s actual is a constant `false`, matching `isTvPlatform`'s own precedent
// for a platform fact no runtime check on that target could contradict.
expect val isWebPlatform: Boolean

// Distinguishes which install instructions apply — iOS never fires beforeinstallprompt (Safari has no
// such API) and only ever offers the manual Share → Add to Home Screen steps; Android Chrome offers a
// real Install button via [rememberInstallPromptAvailable]. UA-based, same as boot.js's own check
// (feature detection can't answer "which onboarding copy" the way it answers "does this API exist").
expect val isIOSWebPlatform: Boolean

/** True once this page is already running as an installed standalone web app (iOS Home Screen,
 *  Android WebAPK) — the card must never show there. Constant `false` off the web. */
@Composable
expect fun rememberIsStandaloneWebApp(): Boolean

/** True once the browser has fired `beforeinstallprompt` and it hasn't been used yet (Android Chrome
 *  in practice; iOS Safari never fires it). Polled rather than pushed — simplest correct option for an
 *  event that, once captured, only changes at most once more (used, or the app gets installed some
 *  other way) for the lifetime of the page. */
@Composable
expect fun rememberInstallPromptAvailable(): Boolean

/** Fires the captured `beforeinstallprompt` event (boot.js's `window.raviloTriggerInstall()`). A no-op
 *  wherever [rememberInstallPromptAvailable] would report `false`. */
expect fun triggerNativeInstall()

/** True once the service worker has installed a new build behind the one currently controlling this
 *  page (boot.js sets `window.__raviloUpdateAvailable` from the registration's own `updatefound` /
 *  `statechange` events) — the "Ravilo updated · Reload" toast's trigger. Constant `false` off the web. */
@Composable
expect fun rememberUpdateAvailable(): Boolean

/** Tells the waiting service worker to activate immediately, then reloads the page — the "Ravilo
 *  updated · Reload" toast's action. A no-op off the web. */
expect fun reloadForUpdate()

/** Whether the viewer has already dismissed the install card on this device (`localStorage`, same
 *  class of per-device UI state as the remembered skin — FR-R263-9). Always `true` off the web, so the
 *  card's gating (`!isWebPlatform || dismissed || …`) never needs a platform check of its own. */
expect fun installCardDismissed(): Boolean

/** Persists the dismissal from [installCardDismissed]. A no-op off the web. */
expect fun dismissInstallCard()
