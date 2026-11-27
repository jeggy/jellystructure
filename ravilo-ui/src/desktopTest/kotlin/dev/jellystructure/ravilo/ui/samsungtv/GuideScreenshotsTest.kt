package dev.jellystructure.ravilo.ui.samsungtv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import dev.jellystructure.ravilo.ui.theme.AuroraColors
import dev.jellystructure.ravilo.ui.theme.DaylightColors
import dev.jellystructure.ravilo.ui.theme.LocalRaviloColors
import dev.jellystructure.shared.tv.SamsungTvRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DEB-4843 (FR-9, design gate) — renders every guide screen, light and dark, to PNG for the owner's sign-off. Set
 * `-Dsamsung.shots=<dir>` to choose where; the files are the owner's review, not an assertion on pixels.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class GuideScreenshotsTest {
    private val tv = SamsungTv("192.168.1.9", "UE55RU7445", "Living room", "5.0", developerMode = true, developerIp = "192.168.1.52")
    private val tvNew = SamsungTv("192.168.1.10", "QE65QN90DAT", "Kitchen", "8.0", developerMode = false, developerIp = null)
    private fun req(state: String, note: String? = null) = SamsungTvRequest(1, "EXCKEBBVWCGFC", "UE55RU7445", "5.0", "Living room", state, 1, decisionNote = note, wgtUrl = "https://x/y.wgt")

    private val screens: List<Pair<String, GuideScreen>> = listOf(
        "01-intro" to GuideScreen.Intro(true),
        "02-searching" to GuideScreen.Searching,
        "03-found" to GuideScreen.Pick(listOf(tv)),
        "04-found-several" to GuideScreen.Pick(listOf(tv, tvNew)),
        "05-not-found" to GuideScreen.NotFound,
        "06-step1-question" to GuideScreen.Step1(tv),
        "06a-step1-keys" to GuideScreen.Step1(tv, Remote.KEYS),
        "06b-step1-keys-2024" to GuideScreen.Step1(tvNew, Remote.KEYS),
        "07-step1-nokeys" to GuideScreen.Step1(tv, Remote.NOKEYS),
        "07a-step1-nokeys-2024" to GuideScreen.Step1(tvNew, Remote.NOKEYS),
        "08-step2" to GuideScreen.Step2(tv, "192.168.1.52"),
        "09-step3" to GuideScreen.Step3(tv),
        "10-checking" to GuideScreen.Checking(tv),
        "11-problem-devmode" to GuideScreen.Problem("samsungtv.problem.devmode_off", emptyMap(), Target.STEP1),
        "12-problem-address" to GuideScreen.Problem("samsungtv.problem.ip_differs", mapOf("tv" to "192.168.1.40", "phone" to "192.168.1.52"), Target.STEP2),
        "13-step4-ask" to GuideScreen.Ask(tv),
        "18-installing" to GuideScreen.Installing(GuideScreen.Stage.SENDING, 0.4f),
        "19-done" to GuideScreen.Done(req("READY")),
        "20-no-wifi" to GuideScreen.Intro(false),
    )

    // ─── DEB-5905 (FR-10, FR-12, FR-14) — Your Samsung TVs. The names and model codes are invented stand-ins; nothing here is the household's. ───

    private fun found(ip: String, name: String, model: String) = SamsungTv(ip, model, name, "5.0", developerMode = true, developerIp = "192.168.1.52")
    private val living = found("192.168.1.9", "Living Room TV", "UE50TU7105")
    private val bedroom = found("192.168.1.10", "Bedroom TV", "QE43Q60B")
    private fun row(id: Long, tv: SamsungTv, state: String, release: String? = null, note: String? = null) =
        SamsungTvRequest(id, "DUID$id", tv.model, "5.0", tv.name, state, 1, decisionNote = note, readyFromRelease = release, wgtUrl = release?.let { "https://x/y.wgt" })
    private val ready = row(1, living, "READY", "v1.71")
    private fun on(v: String) = InstalledRavilo.Version(RaviloVersion.parse(v)!!)
    private fun ov(vararg cards: TvCard, found: Int = cards.count { it.tv != null }, scanning: Boolean = false, wifi: Boolean = true, sent: Boolean = false) =
        GuideScreen.Overview(cards.toList(), scanning, found, wifi, sent)

    private val overviews: List<Pair<String, GuideScreen>> = listOf(
        "30-ov-uptodate" to ov(TvCard(living, ready, on("1.71"))),
        "31-ov-update-and-notsetup" to ov(TvCard(living, ready, on("1.70")), TvCard(bedroom, null)),
        "32-ov-next-release" to ov(TvCard(living, row(1, living, "APPROVED"), InstalledRavilo.Unknown)),
        "33-ov-not-found" to ov(TvCard(bedroom, null), TvCard(null, ready), found = 1),
        "34-ov-no-wifi" to ov(TvCard(null, ready), wifi = false),
        "35-ov-approved-and-waiting" to ov(TvCard(living, ready, on("1.71")), TvCard(bedroom, row(2, bedroom, "REQUESTED"))),
        "36-ov-request-sent" to ov(TvCard(living, ready, on("1.71")), TvCard(bedroom, row(2, bedroom, "REQUESTED")), sent = true),
        "37-ov-rejected-note" to ov(TvCard(living, ready, on("1.71")), TvCard(bedroom, row(2, bedroom, "REJECTED", note = "Not this one: it is the one in the guest room."))),
        "38-ov-server-first-scanning" to ov(TvCard(null, ready), scanning = true, found = 0),
        "39-ov-not-installed" to ov(TvCard(living, ready, InstalledRavilo.NotInstalled)),
        "40-ov-two-approved" to ov(TvCard(living, ready, on("1.70")), TvCard(bedroom, row(2, bedroom, "READY", "v1.71"), InstalledRavilo.NotInstalled)),
        "41-ov-version-unknown" to ov(TvCard(living, ready, InstalledRavilo.Unknown)),
        // FR-10c: every found TV first (Approved, Waiting, Not approved, Not set up), then the requests the scan did not find.
        "42-ov-all-badges" to ov(
            TvCard(living, ready, on("1.70")), TvCard(bedroom, row(2, bedroom, "REQUESTED")),
            TvCard(found("192.168.1.11", "Hallway TV", "QE32Q50A"), null),
            TvCard(null, row(3, living, "REJECTED", note = "Not this one.")), found = 3,
        ),
    )

    private val stub = object : SamsungTvPlatform {
        override fun phoneAddress() = "192.168.1.52"
        override suspend fun probe(ip: String): String? = null
        override suspend fun download(url: String, onProgress: (Long, Long?) -> Unit) = ByteArray(0)
        override fun packageDeviceIds(wgt: ByteArray): Set<String>? = null
        override val sdb = SdbClient(SdbDialer { _, _ -> error("not used") })
        override fun log(line: String) {}
    }
    private val backend = object : SamsungTvBackend {
        override suspend fun list() = emptyList<SamsungTvRequest>()
        override suspend fun create(body: dev.jellystructure.shared.tv.SamsungTvRequestCreate) = error("not used")
        override suspend fun withdraw(id: Long) = error("not used")
    }

    private fun render(dir: File, name: String, themeName: String, colors: dev.jellystructure.ravilo.ui.theme.RaviloColors, screen: GuideScreen, locale: String = "en", fontScale: Float = 1f) {
        val guide = SamsungGuide(stub, backend, CoroutineScope(Dispatchers.Unconfined))
        runComposeUiTest {
            setContent {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    WithLocale(locale) {
                        CompositionLocalProvider(LocalRaviloColors provides colors) {
                            // The overview is a list page with its own 18 dp gutter; every other screen is a centred 24 dp page.
                            val listPage = screen is GuideScreen.Overview
                            Column(Modifier.size(412.dp, 860.dp).background(colors.background).padding(horizontal = if (listPage) 0.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                GuideBody(screen, guide)
                            }
                        }
                    }
                }
            }
            val file = File(dir, "$name-$themeName.png")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            assertTrue(file.length() > 1000, "$file is empty")
        }
    }

    private val themes = listOf("dark" to AuroraColors, "light" to DaylightColors)
    private fun shots() = File(System.getProperty("samsung.shots") ?: "build/samsung-guide-shots").also { it.mkdirs() }

    @Test fun `every guide screen renders in light and dark`() {
        val dir = shots()
        for ((themeName, colors) in themes) for ((name, screen) in screens) render(dir, name, themeName, colors, screen)
    }

    /** DEB-5504 (FR-13a) — the question in Faroese at the 200 % font setting: the labels no longer fit side by side, so the cards stack. */
    @Test fun `the question stacks its cards at the largest font`() {
        val dir = shots()
        for ((themeName, colors) in themes) {
            render(dir, "06c-step1-question-fo-200", themeName, colors, GuideScreen.Step1(tv), locale = "fo", fontScale = 2f)
            render(dir, "06d-step1-question-da", themeName, colors, GuideScreen.Step1(tv), locale = "da")
            render(dir, "06e-step1-nokeys-fo-200", themeName, colors, GuideScreen.Step1(tv, Remote.NOKEYS), locale = "fo", fontScale = 2f)
        }
    }

    /** FR-12, FR-14, FR-10h — the overview in every state the spec names, light and dark. */
    @Test fun `the overview renders in every state in light and dark`() {
        val dir = shots()
        for ((themeName, colors) in themes) for ((name, screen) in overviews) render(dir, name, themeName, colors, screen)
    }

    /** FR-10h — `da` and `fo`, and the `fo` overview at the 200 % font setting, where the *Waiting for approval* badge drops to its own line. */
    @Test fun `the overview renders in da and fo and at the largest font`() {
        val dir = shots()
        val two = overviews.first { it.first == "35-ov-approved-and-waiting" }.second
        val update = overviews.first { it.first == "31-ov-update-and-notsetup" }.second
        for ((themeName, colors) in themes) {
            render(dir, "50-ov-da", themeName, colors, two, locale = "da")
            render(dir, "51-ov-fo", themeName, colors, two, locale = "fo")
            render(dir, "52-ov-fo-200", themeName, colors, two, locale = "fo", fontScale = 2f)
            render(dir, "53-ov-update-fo-200", themeName, colors, update, locale = "fo", fontScale = 2f)
            render(dir, "54-ov-update-en-200", themeName, colors, update, fontScale = 2f)
        }
    }
}
