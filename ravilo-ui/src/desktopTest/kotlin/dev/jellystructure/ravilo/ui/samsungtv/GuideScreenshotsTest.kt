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
        "03-found" to GuideScreen.Pick(listOf(tv), false),
        "04-found-several" to GuideScreen.Pick(listOf(tv, tvNew), false),
        "05-not-found" to GuideScreen.NotFound,
        "06-step1" to GuideScreen.Step1(tv),
        "07-step1-2024" to GuideScreen.Step1(tvNew),
        "08-step2" to GuideScreen.Step2(tv, "192.168.1.52"),
        "09-step3" to GuideScreen.Step3(tv),
        "10-checking" to GuideScreen.Checking(tv),
        "11-problem-devmode" to GuideScreen.Problem("samsungtv.problem.devmode_off", emptyMap(), Target.STEP1),
        "12-problem-address" to GuideScreen.Problem("samsungtv.problem.ip_differs", mapOf("tv" to "192.168.1.40", "phone" to "192.168.1.52"), Target.STEP2),
        "13-step4-ask" to GuideScreen.Ask(tv),
        "14-waiting" to GuideScreen.Waiting(req("REQUESTED")),
        "15-approved" to GuideScreen.Waiting(req("APPROVED")),
        "16-ready" to GuideScreen.Ready(req("READY")),
        "17-rejected" to GuideScreen.Rejected(req("REJECTED", "We can only add a few TVs right now.")),
        "18-installing" to GuideScreen.Installing(GuideScreen.Stage.SENDING, 0.4f),
        "19-done" to GuideScreen.Done(req("READY")),
        "20-no-wifi" to GuideScreen.Intro(false),
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

    @Test fun `every guide screen renders in light and dark`() {
        val dir = File(System.getProperty("samsung.shots") ?: "build/samsung-guide-shots").also { it.mkdirs() }
        val guide = SamsungGuide(stub, backend, CoroutineScope(Dispatchers.Unconfined))
        for ((themeName, colors) in listOf("dark" to AuroraColors, "light" to DaylightColors)) {
            for ((name, screen) in screens) runComposeUiTest {
                setContent {
                    WithLocale("en") {
                        CompositionLocalProvider(LocalRaviloColors provides colors) {
                            Column(Modifier.size(412.dp, 860.dp).background(colors.background).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                GuideBody(screen, guide)
                            }
                        }
                    }
                }
                val file = File(dir, "$name-$themeName.png")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
                assertTrue(file.length() > 1000, "$file is empty")
            }
        }
    }
}
