package dev.jellystructure.music

import dev.jellystructure.model.ProviderKeyCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2026-09-28 amendment — the providers card's dots come from what each provider answered. The bodies below are the
 *  providers' own answers, recorded against the live services the same day. */
class ProviderKeyChecksTest {
    private fun ProviderKeyCheck.state() = when { ok -> "ok"; answered -> "refused"; else -> "untested" }

    @Test fun fanartReadsTheKeyVerdict() {
        assertEquals("ok", ProviderKeyChecks.fanartVerdict(200, """{"name":"x","artistthumb":[]}""").state())
        assertEquals("ok", ProviderKeyChecks.fanartVerdict(200, "{}").state())   // an unknown artist, key accepted
        val bad = ProviderKeyChecks.fanartVerdict(401, """{"error":"invalid API key"}""")
        assertEquals("refused", bad.state())
        assertEquals("fanart.tv refused the key: invalid API key.", bad.message)
        assertEquals("untested", ProviderKeyChecks.fanartVerdict(503, null).state())
        assertEquals("untested", ProviderKeyChecks.fanartVerdict(null, null).state())
    }

    @Test fun acoustIdReadsTheKeyVerdict() {
        assertEquals("ok", ProviderKeyChecks.acoustIdVerdict(200, """{"results": [{"id": "9ff43b6a-4f16-427c-93c2-92307ca505e0", "score": 1.0}], "status": "ok"}""").state())
        val bad = ProviderKeyChecks.acoustIdVerdict(400, """{"error": {"code": 4, "message": "invalid API key"}, "status": "error"}""")
        assertEquals("refused", bad.state())
        assertEquals("AcoustID refused the key: invalid API key.", bad.message)
        // Another error (a bad parameter) says nothing about the key.
        assertEquals("untested", ProviderKeyChecks.acoustIdVerdict(400, """{"error": {"code": 2, "message": "missing required parameter \"client\""}, "status": "error"}""").state())
    }

    @Test fun googleBooksReadsTheKeyVerdict() {
        assertEquals("ok", ProviderKeyChecks.googleBooksVerdict(200, """{"kind":"books#volumes","totalItems":1}""").state())
        val bad = ProviderKeyChecks.googleBooksVerdict(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""")
        assertEquals("refused", bad.state())
        assertEquals("Google Books refused the key: API key not valid. Please pass a valid API key.", bad.message)
        assertEquals("untested", ProviderKeyChecks.googleBooksVerdict(429, "{}").state())   // quota, not the key
    }

    @Test fun anAnswerBelongsToTheKeyItWasMadeWith() {
        val memo = ProviderKeyChecks.Memo()
        memo.record("key-a", ProviderKeyChecks.fanartVerdict(200, "{}"))
        assertTrue(memo.last("key-a")!!.ok)
        assertTrue(memo.last(" key-a ")!!.ok)
        assertNull(memo.last("key-b"))   // a new or hand-edited key has not been tried
        assertNull(memo.last(""))
        // No answer says nothing about the key: the last real answer stands.
        memo.record("key-a", ProviderKeyChecks.fanartVerdict(503, null))
        assertTrue(memo.last("key-a")!!.ok)
        memo.record("key-a", ProviderKeyChecks.fanartVerdict(401, """{"error":"invalid API key"}"""))
        assertFalse(memo.last("key-a")!!.ok)
    }
}
