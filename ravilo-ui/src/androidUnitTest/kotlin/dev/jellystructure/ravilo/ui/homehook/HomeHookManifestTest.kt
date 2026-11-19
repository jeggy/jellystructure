package dev.jellystructure.ravilo.ui.homehook

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.jellystructure.ravilo.ui.R
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DEB-4910 (FR-7, FR-9) — the facts about the hook that live in the manifest and the resources, which no behaviour test sees:
 * the service is disabled by default, reads no window content, and no Ravilo component claims the HOME category.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeHookManifestTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val service get() = ComponentName(ctx, RaviloHomeHookService::class.java)

    // installHomeHook registers the process-wide hook (and Apps); without this the Settings tests that run after it would draw the Home screen section.
    @org.junit.After fun forgetTheHook() { HomeHook.resetForTest() }

    // DEB-5061 — installHomeHook sets the process-wide HomeHook; leaving it set adds the Home screen row to every later Settings test in the JVM.
    @After fun uninstallTheHook() = HomeHook.uninstallForTest()

    // FR-7 — a phone never lists the service: the manifest declares it disabled, and Ravilo enables it itself at every start on a TV only.
    @Test fun theServiceIsDisabledByDefault() {
        // Asking for it without MATCH_DISABLED_COMPONENTS throws: that is the disabled default seen from outside.
        assertFailsWith<PackageManager.NameNotFoundException> { ctx.packageManager.getServiceInfo(service, PackageManager.GET_META_DATA) }
        val info = ctx.packageManager.getServiceInfo(service, PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(info.enabled, "the manifest must declare android:enabled=false")
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.permission)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, ctx.packageManager.getComponentEnabledSetting(service))
    }

    // DEB-5061 (FR-7) — a TV enables the component at start, before any screen shows; Android's accessibility manager lists it from its next package event.
    @Test @Config(sdk = [34], qualifiers = "television")
    fun aTvEnablesTheServiceAtStart() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, ctx.packageManager.getComponentEnabledSetting(service))
        installHomeHook(ctx)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, ctx.packageManager.getComponentEnabledSetting(service))
        installHomeHook(ctx)   // every start: the second leaves it enabled
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, ctx.packageManager.getComponentEnabledSetting(service))
    }

    // DEB-5061 (FR-7) — a phone's start leaves the component disabled, so Android never lists it there.
    @Test fun aPhoneLeavesTheServiceDisabled() {
        installHomeHook(ctx)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, ctx.packageManager.getComponentEnabledSetting(service))
        assertFailsWith<PackageManager.NameNotFoundException> { ctx.packageManager.getServiceInfo(service, PackageManager.GET_META_DATA) }
    }

    // FR-7 — window changes only: no window content, no gestures, no touch exploration.
    @Test fun theServiceReadsNoWindowContent() {
        val xml = ctx.resources.getXml(R.xml.ravilo_home_hook_service)
        var seen = false
        while (xml.eventType != android.content.res.XmlResourceParser.END_DOCUMENT) {
            if (xml.eventType == android.content.res.XmlResourceParser.START_TAG && xml.name == "accessibility-service") {
                seen = true
                val ns = "http://schemas.android.com/apk/res/android"
                assertFalse(xml.getAttributeBooleanValue(ns, "canRetrieveWindowContent", true))
                assertFalse(xml.getAttributeBooleanValue(ns, "canPerformGestures", true))
                assertFalse(xml.getAttributeBooleanValue(ns, "canRequestTouchExplorationMode", true))
                assertFalse(xml.getAttributeBooleanValue(ns, "isAccessibilityTool", true))
                assertNull(xml.getAttributeValue(ns, "packageNames"))   // the filter is the stock home's, which is found at run time (FR-6)
                assertNull(xml.getAttributeValue(ns, "accessibilityFlags"))
            }
            xml.next()
        }
        assertTrue(seen)
    }

    private fun assertNull(v: Any?) = kotlin.test.assertNull(v)

    // FR-7 — no component of Ravilo, in any manifest the app is built from, declares the HOME category, and there is no
    // WRITE_SECURE_SETTINGS. (The <queries> entry that lets Ravilo *find* the stock home is not a component.)
    @Test fun noComponentDeclaresHomeAndNoSecureSettingsPermission() {
        val manifests = listOf(
            "src/androidMain/AndroidManifest.xml",
            "../ravilo-android/src/main/AndroidManifest.xml",
            "../ravilo-android/src/appLink/AndroidManifest.xml",
        ).map { File(it) }.filter { it.exists() }
        assertTrue(manifests.size >= 2, "the manifests to read were not found from ${File(".").absolutePath}")
        val components = setOf("activity", "activity-alias", "service", "receiver", "provider")
        for (f in manifests) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            for (tag in components) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val cats = (nodes.item(i) as Element).getElementsByTagName("category")
                    for (j in 0 until cats.length) {
                        val name = (cats.item(j) as Element).getAttribute("android:name")
                        assertFalse(name == "android.intent.category.HOME", "$tag in ${f.path} declares HOME")
                    }
                }
            }
            val perms = doc.getElementsByTagName("uses-permission")
            for (i in 0 until perms.length) {
                assertFalse((perms.item(i) as Element).getAttribute("android:name") == "android.permission.WRITE_SECURE_SETTINGS", "${f.path} asks for WRITE_SECURE_SETTINGS")
            }
        }
    }

    // FR-9 — Android reads the service description from a resource; i18n/*.json is the source. One line, three languages, never drifting.
    @Test fun theServiceDescriptionMatchesTheJson() {
        for (lang in listOf("en", "da", "fo")) {
            val json = org.json.JSONObject(File("../i18n/$lang.json").readText())
            val entry = json.get("home_hook.service_description")
            val expected = if (entry is org.json.JSONObject) entry.getString("text") else entry as String
            val cfg = ctx.resources.configuration.let { android.content.res.Configuration(it).apply { setLocale(java.util.Locale.forLanguageTag(lang)) } }
            val actual = ctx.createConfigurationContext(cfg).getString(R.string.ravilo_home_hook_service_description)
            assertEquals(expected, actual, "values-$lang/strings.xml drifted from i18n/$lang.json")
        }
    }

    // FR-9 — all three languages, and no string names Jellyfin (the Ravilo constitution's invariant 12).
    @Test fun everyStringIsInAllThreeLanguagesAndNamesNoJellyfin() {
        val keys = org.json.JSONObject(File("../i18n/en.json").readText()).keys().asSequence().filter { it.startsWith("home_hook.") }.toList()
        assertTrue(keys.size >= 13)
        for (k in listOf("home_hook.state_needs_restart", "home_hook.disclosure_restart", "home_hook.disclosure_find")) assertTrue(k in keys, "en.json lacks $k")
        for (lang in listOf("en", "da", "fo")) {
            val json = org.json.JSONObject(File("../i18n/$lang.json").readText())
            for (k in keys) {
                assertTrue(json.has(k), "$lang.json lacks $k")
                assertFalse(json.get(k).toString().contains("jellyfin", ignoreCase = true), "$lang $k names Jellyfin")
            }
        }
    }

    // DEB-4912 (FR-11) — Android 11+ shows an app only what a <queries> entry names, so Apps needs the TV-launcher one, and the stock-home one stays.
    @Test fun theManifestQueriesTheTvLauncherAndTheStockHome() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/androidMain/AndroidManifest.xml"))
        val queries = doc.getElementsByTagName("queries").item(0) as Element
        val intents = queries.getElementsByTagName("intent")
        val categories = (0 until intents.length).flatMap { i ->
            val cats = (intents.item(i) as Element).getElementsByTagName("category")
            (0 until cats.length).map { (cats.item(it) as Element).getAttribute("android:name") }
        }
        assertTrue("android.intent.category.LEANBACK_LAUNCHER" in categories, "Apps cannot see the TV's apps without this query")
        assertTrue("android.intent.category.HOME" in categories)
    }

    @Test fun theServiceClassResolves() { assertNotNull(Class.forName("dev.jellystructure.ravilo.ui.homehook.RaviloHomeHookService")) }
}
