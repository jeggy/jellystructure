package dev.jellystructure.ravilo.benchmark

import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * R213 (FR-RV-R213-1, FR-RV-R213-3, as amended 2026-10-23) — generates `ravilo-android/src/main/baseline-prof.txt`
 * from the **TV** activity, signed in, and fails unless it reached Home.
 *
 * Run it with `./gradlew :ravilo-android:generateBaselineProfile -Pravilo.releaseAsDebugId` plus the three
 * instrumentation arguments below (README.md has the exact command) on a claimed `TV`-type device, API 33 or later.
 * The target package is whatever the build installed (`dev.jellystructure.ravilo.debug`): the plugin hands it to this
 * test as [ARG_TARGET_PACKAGE], so there is no package literal here.
 *
 * Nothing about the household is committed: the server address, user name and password arrive as instrumentation
 * arguments at run time and are used only if server setup or sign-in is on screen.
 */
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        val args = InstrumentationRegistry.getArguments()
        val targetPackage = args.getString(ARG_TARGET_PACKAGE)
            ?: abort("No $ARG_TARGET_PACKAGE instrumentation argument: run through :ravilo-android:generateBaselineProfile.")
        val server = args.getString(ARG_SERVER)
        val user = args.getString(ARG_USER)
        val password = args.getString(ARG_PASSWORD)

        baselineProfileRule.collect(packageName = targetPackage) {
            pressHome()
            // The TV activity by explicit component: with no intent this launches the phone LAUNCHER activity.
            startActivityAndWait(Intent().setClassName(packageName, TV_ACTIVITY))
            reachHome(device, packageName, server, user, password)

            // Down through five rows, with the row scroll and focus detail run in the first two.
            repeat(2) {
                press(device) { pressDPadDown() }
                repeat(TILES_PER_ROW) { press(device) { pressDPadRight() } }
                repeat(TILES_PER_ROW) { press(device) { pressDPadLeft() } }
            }
            repeat(3) { press(device) { pressDPadDown() } }
            // Back up to the hero.
            repeat(5) { press(device) { pressDPadUp() } }

            // Open a detail screen from the first row, move down it, and back out.
            press(device) { pressDPadDown() }
            press(device) { pressDPadCenter() }
            device.wait(Until.hasObject(By.pkg(packageName)), 3_000)
            device.waitForIdle()
            repeat(3) { press(device) { pressDPadDown() } }
            press(device) { pressBack() }
        }
    }

    private enum class Screen(val label: String) {
        HOME("Home"), SETUP("server setup"), LOGIN("sign-in"), PICKER("the profile picker"), OTHER("an unrecognised screen")
    }

    private fun screenOf(device: UiDevice): Screen = when {
        device.hasObject(By.res(HOME_TAG)) -> Screen.HOME
        device.hasObject(By.res(SETUP_ADDRESS_FIELD_TAG)) || device.hasObject(By.res(SETUP_ADDRESS_TAG)) -> Screen.SETUP
        device.hasObject(By.res(LOGIN_USERNAME_TAG)) -> Screen.LOGIN
        device.hasObject(By.res(PICKER_TAG)) -> Screen.PICKER
        else -> Screen.OTHER
    }

    /**
     * Drives whatever is between launch and Home — server setup, sign-in, the profile picker — then asserts Home.
     * Each step runs at most twice; a run that is still not on Home after [HOME_TIMEOUT_MS] **fails**, naming the screen
     * it is on, so no profile is written from a short run.
     */
    private fun reachHome(device: UiDevice, pkg: String, server: String?, user: String?, password: String?) {
        val attempts = mutableMapOf<Screen, Int>()
        val deadline = SystemClock.uptimeMillis() + HOME_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val screen = screenOf(device)
            when (screen) {
                Screen.HOME -> {
                    device.waitForIdle()
                    return
                }
                Screen.OTHER -> SystemClock.sleep(POLL_MS)
                else -> {
                    // Each step is driven at most twice; after that the screen is left to finish (a slow sign-in
                    // must not be typed over) and the deadline decides.
                    val n = (attempts[screen] ?: 0) + 1
                    attempts[screen] = n
                    if (n <= 2) {
                        when (screen) {
                            Screen.SETUP -> setUpServer(device, need(ARG_SERVER, server))
                            Screen.LOGIN -> signIn(device, need(ARG_USER, user), need(ARG_PASSWORD, password))
                            Screen.PICKER -> press(device) { pressDPadCenter() }   // the first profile holds focus on arrival
                            else -> Unit
                        }
                    }
                    SystemClock.sleep(STEP_SETTLE_MS)
                }
            }
        }
        failOn(device, pkg, screenOf(device))
    }

    private fun need(name: String, value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: abort("The app is on a screen that needs -Pandroid.testInstrumentationRunnerArguments.$name=…, and none was given.")

    private fun failOn(device: UiDevice, pkg: String, screen: Screen): Nothing {
        val visible = device.findObjects(By.pkg(pkg)).mapNotNull { it.text ?: it.contentDescription }.distinct().take(8)
        abort(
            "Home was not reached within ${HOME_TIMEOUT_MS / 1000}s: the app is on ${screen.label}" +
                " (foreground package ${device.currentPackageName}, visible text $visible). No profile is written from this run.",
        )
    }

    /** JUnit's `fail()` returns Unit; this one ends the expression, so `?: abort(…)` types. */
    private fun abort(message: String): Nothing = throw AssertionError(message)

    /** OK on the focused address makes it editable; the field takes the text and Enter is the keyboard's Go. */
    private fun setUpServer(device: UiDevice, server: String) {
        press(device) { pressDPadCenter() }
        device.wait(Until.findObject(By.res(SETUP_ADDRESS_FIELD_TAG)), UI_WAIT_MS)?.text = server
        press(device) { pressEnter() }
    }

    /** The user name field holds focus on arrival: OK to edit, type, Enter (Next) hands typing to the password, Enter (Done) submits. */
    private fun signIn(device: UiDevice, user: String, password: String) {
        press(device) { pressDPadCenter() }
        device.wait(Until.findObject(By.res(LOGIN_USERNAME_TAG)), UI_WAIT_MS)?.text = user
        press(device) { pressEnter() }
        val field = device.wait(Until.findObject(By.res(LOGIN_PASSWORD_TAG).focused(true)), UI_WAIT_MS)
            ?: abort("Signing in: the password field never took focus after the user name.")
        field.text = password
        press(device) { pressEnter() }
    }

    private inline fun press(device: UiDevice, key: UiDevice.() -> Unit) {
        device.key()
        device.waitForIdle()
    }

    private companion object {
        /** Written by the baselineprofile plugin from the target variant's application id. */
        const val ARG_TARGET_PACKAGE = "androidx.benchmark.targetPackageName"
        const val ARG_SERVER = "ravilo.server"
        const val ARG_USER = "ravilo.user"
        const val ARG_PASSWORD = "ravilo.password"

        /** The TV activity's class (the namespace, not the application id, which carries the `.debug` suffix). */
        const val TV_ACTIVITY = "dev.jellystructure.ravilo.android.MainActivity"

        // The tags the app exposes as resource ids (ServerSetupTags, LoginTags, HomeTags, ProfilePickerTags).
        const val SETUP_ADDRESS_TAG = "setup-address"
        const val SETUP_ADDRESS_FIELD_TAG = "setup-address-field"
        const val LOGIN_USERNAME_TAG = "login-username"
        const val LOGIN_PASSWORD_TAG = "login-password"
        const val PICKER_TAG = "profile-picker"
        const val HOME_TAG = "home-loaded"

        const val TILES_PER_ROW = 8
        const val HOME_TIMEOUT_MS = 90_000L
        const val POLL_MS = 500L
        const val STEP_SETTLE_MS = 3_000L
        const val UI_WAIT_MS = 5_000L
    }
}
