package dev.jellystructure.ops

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 221 — verification 1: three failures raise the finding, one success clears it; a blank URL never
 *  raises it; the deprecated route's finding appears on a hit and not after 7 quiet days (30 until the
 *  2026-09-25 amendment). */
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
    fun `a deprecated arr hit is a finding for 7 days logged once per day`() {
        val day = 24 * 3_600_000L
        assertTrue(WebhookStatus.recordArrHit("radarr"), "first hit of the day logs")
        assertFalse(WebhookStatus.recordArrHit("radarr"), "second hit the same day is silent")
        val f = WebhookStatus.findings("", jellyfinWebhookLastAt = now - 5 * day)
        assertEquals(listOf("arr-deprecated-radarr"), f.map { it.id })
        assertTrue(f.single().summary.startsWith("Radarr"))
        assertTrue(f.single().currentValue.contains("so in 7 days if Radarr stops calling"), f.single().currentValue)
        assertTrue(f.single().currentValue.contains("Jellyfin's webhook last delivered 5 days ago"), f.single().currentValue)
        assertTrue(f.single().fieldLabel.endsWith("/api/webhooks/radarr"))
        now += 6 * day + 12 * 3_600_000L
        val late = WebhookStatus.findings("", null).single()
        assertTrue(late.currentValue.contains("in 12 h"), "the countdown rounds up and never says 0 days: ${late.currentValue}")
        assertTrue(late.currentValue.contains("has not delivered since jellystructure last started"))
        assertTrue(late.tradeoff.contains("Test delivery now"), "no Jellyfin delivery on record ⇒ say how to check it")
        now += 13 * 3_600_000L
        assertTrue(WebhookStatus.findings("", null).isEmpty(), "silent after 7 quiet days")
        assertTrue(WebhookStatus.recordArrHit("radarr"), "a new day logs again")
    }
}
