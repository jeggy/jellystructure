package dev.jellystructure.ops

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 221 — verification 1: three failures raise the finding, one success clears it; a blank URL never
 *  raises it; the deprecated route's finding appears on a hit and not after 30 quiet days. */
class WebhookStatusTest {
    private var now = 1_700_000_000_000L
    private val url = "http://hook.example:8585/notify"

    @BeforeTest
    fun setUp() {
        WebhookStatus.resetForTests()
        WebhookStatus.clock = { now }
    }

    @Test
    fun `three consecutive failures raise the finding and one success clears it`() {
        assertTrue(WebhookStatus.findings(url, null).isEmpty(), "nothing before any delivery")
        repeat(3) { WebhookStatus.recordDelivery(url, ok = false, reason = "could not connect", elapsedMs = 12); now += 2 * 24 * 3_600_000L }
        val f = WebhookStatus.findings(url, null)
        assertEquals(listOf("webhook-failing"), f.map { it.id })
        assertTrue(f.single().currentValue.contains("3 deliveries"))
        assertTrue(f.single().currentValue.contains("could not connect"))
        WebhookStatus.recordDelivery(url, ok = true, reason = null, elapsedMs = 40)
        assertTrue(WebhookStatus.findings(url, null).isEmpty(), "one success clears it")
        assertEquals(0, WebhookStatus.target(url)!!.consecutiveFailures)
    }

    @Test
    fun `any failure in the last hour raises it too and a blank URL never does`() {
        WebhookStatus.recordDelivery(url, ok = false, reason = "HTTP 502", elapsedMs = 5)
        assertEquals(1, WebhookStatus.findings(url, null).size, "the open question's recommendation: either condition raises")
        assertTrue(WebhookStatus.findings("", null).isEmpty(), "unconfigured is not broken")
        now += 2 * 3_600_000L
        assertTrue(WebhookStatus.findings(url, null).isEmpty(), "one old failure below the threshold is silent again")
    }

    @Test
    fun `a deprecated arr hit is a finding for 30 days logged once per day`() {
        assertTrue(WebhookStatus.recordArrHit("radarr"), "first hit of the day logs")
        assertFalse(WebhookStatus.recordArrHit("radarr"), "second hit the same day is silent")
        val f = WebhookStatus.findings("", jellyfinWebhookSince = now - 5 * 24 * 3_600_000L)
        assertEquals(listOf("arr-deprecated-radarr"), f.map { it.id })
        assertTrue(f.single().summary.startsWith("Radarr"))
        assertTrue(f.single().currentValue.contains("has been delivering since"))
        now += 31L * 24 * 3_600_000L
        assertTrue(WebhookStatus.findings("", null).isEmpty(), "silent after 30 quiet days")
        assertTrue(WebhookStatus.recordArrHit("radarr"), "a new day logs again")
    }
}
