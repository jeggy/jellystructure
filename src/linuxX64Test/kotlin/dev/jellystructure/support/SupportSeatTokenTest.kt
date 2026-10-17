package dev.jellystructure.support

import dev.jellystructure.auth.SeatTokenCheck
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.support.SupportService.Outcome
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * DEB-1508 (FR-1 … FR-14) — Hervør's support seat token and what it may do, against the real store and no socket: the
 * token's lifecycle, the effective switch, who she sees and how anonymously, the six rules of a reply, the forward, the
 * hand-back, and the audit. The routes (the fence, the status codes, the admin controls) are in [SupportSeatRoutesTest].
 */
class SupportSeatTokenTest {
    private val json = Json { encodeDefaults = true }

    /** Switches her on with a live token; returns the plaintext and the token's id. */
    private fun SupportHarness.hervorOn(): Pair<String, String> {
        configure { copy(hervorEnabled = true) }
        val plaintext = service.seatTokens.issue("root").token
        return plaintext to service.seatTokens.validate(plaintext)!!
    }

    /** A ticket an admin forwarded to Hervør: she holds it and owes the viewer the next message. */
    private fun SupportHarness.toHervor(user: String = "u-anna"): Long {
        val n = file(user).ticket.number
        ok(service.forward(n, "root", "HERVOR", null, "HERVOR", ""))
        return n
    }

    private fun SupportHarness.lines(n: Long) = db.supportQueries.messages(n).executeAsList()

    // ── FR-1, FR-2 — the token ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun issuingShowsAJssTokenOnceAndStoresOnlyItsHash() = support("seat-issue") {
        val issued = service.seatTokens.issue("root")
        assertTrue(issued.token.startsWith("jss_") && issued.token.length > 40, "a jss_ bearer with a 256-bit secret")
        val row = db.supportQueries.liveSeatToken("HERVOR").executeAsOne()
        assertEquals(sha256Hex(issued.token), row.token_hash)
        assertNotEquals(issued.token, row.token_hash)
        assertEquals("root", row.created_by)
        // Nothing an admin can read again carries the plaintext, the hash or the id.
        val state = json.encodeToString(dev.jellystructure.model.SupportSeatTokenState.serializer(), service.seatTokens.state())
        assertTrue(issued.token !in state && row.token_hash !in state && row.id !in state, state)
        assertEquals("root", service.seatTokens.state().live?.issuedBy)
    }

    @Test fun replacingKillsTheOldTokenOnItsNextRequestAndLeavesTheSwitchAsItWas() = support("seat-replace") {
        val (first, _) = hervorOn()
        assertIs<SeatTokenCheck.Live>(service.checkSeatToken(first))
        val second = service.seatTokens.issue("root").token
        assertSame(SeatTokenCheck.Invalid, service.checkSeatToken(first), "the old token is dead on its next request")
        assertIs<SeatTokenCheck.Live>(service.checkSeatToken(second))
        assertTrue(cfg.support.hervorEnabled && service.hervorEffective, "replace leaves the switch as it was")
        val past = service.seatTokens.state().past.single()
        assertEquals("SYSTEM", past.revokedBy, "a replacement is revoked by SYSTEM")
        assertEquals(1, db.supportQueries.liveSeatToken("HERVOR").executeAsList().size, "at most one live row")
    }

    @Test fun aRevokedTokenIsUnknownAndItsRowIsKept() = support("seat-revoke") {
        val (token, _) = hervorOn()
        assertTrue(service.seatTokens.revoke("root"))
        assertSame(SeatTokenCheck.Invalid, service.checkSeatToken(token))
        assertFalse(service.hervorEffective, "the file says true but no token is live")
        assertFalse(service.seatTokens.revoke("root"), "nothing live: nothing changes")
        assertEquals("root", service.seatTokens.state().past.single().revokedBy)
        assertNull(service.seatTokens.state().live)
    }

    @Test fun aLiveTokenWithTheSwitchOffIsDisabledNotInvalid() = support("seat-disabled") {
        val token = service.seatTokens.issue("root").token
        assertSame(SeatTokenCheck.Disabled, service.checkSeatToken(token))
        configure { copy(hervorEnabled = true) }
        assertIs<SeatTokenCheck.Live>(service.checkSeatToken(token))
        assertSame(SeatTokenCheck.Invalid, service.checkSeatToken("jss_not-a-token"))
    }

    @Test fun lastUsedMovesAtMostOnceAMinute() = support("seat-used") {
        configure { copy(hervorEnabled = true) }
        val token = service.seatTokens.issue("root").token
        assertNull(service.seatTokens.state().live?.lastUsedAt, "never used")
        service.seatTokens.validate(token)
        val first = service.seatTokens.state().live?.lastUsedAt
        assertEquals(now, first)
        now += 59
        service.seatTokens.validate(token)
        assertEquals(first, service.seatTokens.state().live?.lastUsedAt, "inside the minute")
        now += 2
        service.seatTokens.validate(token)
        assertEquals(now, service.seatTokens.state().live?.lastUsedAt)
    }

    // ── FR-8 — the effective value ───────────────────────────────────────────────────────────────────────────────────

    @Test fun theEffectiveValueGovernsWhoOwesTheNextMessageAndTheSummary() = support("seat-effective") {
        configure { copy(hervorEnabled = true) }
        assertFalse(service.hervorEffective, "true in the file and no token")
        val n = file().ticket.number
        ok(service.forward(n, "root", "HERVOR", null, "HERVOR", ""))
        ok(service.reply(n, "root", "answer"))
        ok(service.post("u-anna", n, "phone", "d1", "again"))
        assertEquals("ADMIN", db.supportQueries.ticket(n).executeAsOne().owes, "FR-320-13 reads the effective value: nobody sends it to her")
        assertFalse(service.summary().hervorEnabled)
        val agent = SupportAi.rule(FakeAnswers.forwardToHervor, "en", service.hervorEffective)
        assertEquals("ADMIN", agent.to, "AI Agent's forward target reads it too")

        service.seatTokens.issue("root")
        assertTrue(service.summary().hervorEnabled)
        assertEquals("HERVOR", SupportAi.rule(FakeAnswers.forwardToHervor, "en", service.hervorEffective).to)
        ok(service.reply(n, "root", "answer again"))
        ok(service.post("u-anna", n, "phone", "d1", "and again"))
        assertEquals("HERVOR", db.supportQueries.ticket(n).executeAsOne().owes, "she holds it and may answer now")
    }

    @Test fun theSummaryCountsWhatHervorOwes() = support("seat-owed") {
        hervorOn()
        toHervor(); toHervor()
        val s = service.summary()
        assertEquals(2, s.owedByHervor)
        assertEquals(0, s.owedByAdmin)
    }

    // ── FR-6, FR-7 — what she sees ───────────────────────────────────────────────────────────────────────────────────

    @Test fun herTicketsAreTheOnesSheHoldsOwesOrHasWrittenInAndNeverAFlaggedOne() = support("seat-visible") {
        val (_, id) = hervorOn()
        val hers = toHervor()
        val adminOnly = file().ticket.number
        val flagged = toHervor()
        ok(service.setFlag(flagged, true, "root"))
        val parked = toHervor()
        ok(service.seatForward(parked, id, "SEAT", "ops", "HERVOR", "Needs the ops seat"))   // she holds none of it now, still owes
        val handed = toHervor()
        ok(service.seatForward(handed, id, "ADMIN", null, "ADMIN", "Over to you"))   // she wrote in it

        assertEquals(setOf(hers, parked, handed), service.seatList().tickets.map { it.number }.toSet())
        assertEquals(setOf(hers, parked, handed), service.seatList(SupportService.AdminFilter(flagged = true)).tickets.map { it.number }.toSet(), "whatever the query asks")
        assertEquals(listOf(hers, parked), service.seatList(SupportService.AdminFilter(owes = "HERVOR")).tickets.map { it.number }.sorted(), "her queue")
        assertNull(service.seatThread(adminOnly), "another ticket does not exist for her")
        assertNull(service.seatThread(flagged), "a flagged ticket is absent")
        assertNull(service.seatThread(9_999))
        assertNotNull(service.seatThread(handed), "readable after she handed it on")
        assertEquals(404, refused(service.seatReply(adminOnly, id, "hello")).status)
        assertEquals(404, refused(service.seatForward(flagged, id, "ADMIN", null, "ADMIN", "x")).status)
    }

    @Test fun theViewerStaysAnonymousToHerAndAnAdminStaysAnAdmin() = support("seat-anon") {
        val (_, id) = hervorOn()
        val n = toHervor()
        ok(service.reply(n, "Anna-the-admin", "an admin spoke"))
        ok(service.post("u-anna", n, "phone", "d1", "I am Anna"))
        val seat = service.seatThread(n)!!
        assertEquals("" to "", seat.ticket.ownerId to seat.ticket.ownerName)
        assertEquals(setOf("Admin"), seat.messages.filter { it.author == "ADMIN" }.map { it.authorLabel }.toSet())
        assertTrue(seat.messages.any { it.body == "I am Anna" }, "the bodies are served as written")
        assertEquals("", service.seatList().tickets.single().ownerName)
        assertEquals("", ok(service.seatReply(n, id, "hello")).ticket.ownerName)
        // The admin's own reading is unchanged.
        val admin = service.adminThread(n)!!
        assertEquals("u-anna" to "Name-of-u-anna", admin.ticket.ownerId to admin.ticket.ownerName)
        assertTrue(admin.messages.any { it.authorLabel == "Anna-the-admin" })
    }

    // ── FR-11 — a reply, as Hervør ───────────────────────────────────────────────────────────────────────────────────

    @Test fun aReplyIsAuthoredHervorSetsNothingOwedAndNamesTheTokenThatWroteIt() = support("seat-reply") {
        val (_, id) = hervorOn()
        val n = toHervor()
        val thread = ok(service.seatReply(n, id, "Try the other player"))
        assertEquals("NONE", thread.ticket.owes)
        val line = lines(n).last { it.kind == "MESSAGE" }
        assertEquals(listOf("HERVOR", "Hervør", "HERVOR", "Hervør", id, "VIEWER"), listOf(line.author, line.author_label, line.actor, line.actor_label, line.seat_token_id, line.visibility))
        assertEquals("Try the other player", line.body)
        assertTrue(lines(n).filter { it.id != line.id }.all { it.seat_token_id == null }, "every other line has no token")
        // The viewer reads it as Hervør, unread.
        val viewer = service.thread("u-anna", n)!!
        assertEquals("HERVOR", viewer.messages.last().author)
        assertEquals(2, viewer.ticket.unread, "the forward line and her reply, both unread")
    }

    @Test fun eachOfTheSixRulesFailsAloneWithItsOwnStatus() = support("seat-rules") {
        val (_, id) = hervorOn()
        val n = toHervor()

        // 1 — hers.
        assertEquals(404, refused(service.seatReply(file().ticket.number, id, "hello")).status)

        // 4 — a body.
        assertEquals("body_empty", refused(service.seatReply(n, id, "   ")).code)
        configure { copy(maxBodyChars = 10) }
        assertEquals("body_too_long", refused(service.seatReply(n, id, "a".repeat(11))).code)
        configure { copy(maxBodyChars = 4_000) }

        // 5 — the machine check for internal strings, named by kind and never quoted.
        val internal = refused(service.seatReply(n, id, "It is tracked as FR-321-4, sorry"))
        assertEquals(422 to "internal_string", internal.status to internal.code)
        assertTrue("FR-321" !in internal.reason && "321" !in internal.reason, internal.reason)
        assertEquals(422, refused(service.seatReply(n, id, "Ask Tróndur")).status)
        assertNull(SupportAi.internalString("Hervør will look at it."), "she may name herself")

        // 2 — support is available (checked before whether it is her turn).
        configure { copy(enabled = false) }
        assertEquals(409 to "support_unavailable", refused(service.seatReply(n, id, "hello")).let { it.status to it.code })
        configure { copy(enabled = true) }

        // 6 — her two rates, counted from the store.
        configure { copy(hervorMessagesPerTicketPerDay = 1) }
        ok(service.seatReply(n, id, "first"))
        ok(service.post("u-anna", n, "phone", "d1", "and?"))
        val perTicket = refused(service.seatReply(n, id, "second"))
        assertEquals(429 to "limit_hervor_messages_per_ticket_per_day", perTicket.status to perTicket.code)
        assertNotNull(perTicket.retryAfterSeconds)
        now += SupportService.DAY + 1
        ok(service.seatReply(n, id, "a day later"))

        configure { copy(hervorMessagesPerTicketPerDay = 6, hervorMessagesPerHour = 1) }
        val other = toHervor("u-ben")
        val perHour = refused(service.seatReply(other, id, "hello"))
        assertEquals(429 to "limit_hervor_messages_per_hour", perHour.status to perHour.code)
        assertTrue((perHour.retryAfterSeconds ?: 0) in 1..SupportService.HOUR)
        now += SupportService.HOUR + 1
        ok(service.seatReply(other, id, "an hour later"))

        // 3 — her turn: it has gone to an admin.
        ok(service.seatForward(n, id, "ADMIN", null, "ADMIN", "Over to you"))
        assertEquals(409 to "not_hervors_turn", refused(service.seatReply(n, id, "hello")).let { it.status to it.code })
    }

    // ── FR-12 — a forward, as Hervør ─────────────────────────────────────────────────────────────────────────────────

    @Test fun aForwardNeedsANoteAndNeverNamesHerselfAndItsLinesAreHers() = support("seat-forward") {
        val (_, id) = hervorOn()
        val n = toHervor()
        assertEquals(400, refused(service.seatForward(n, id, "ADMIN", null, "ADMIN", "")).status)
        assertEquals(400, refused(service.seatForward(n, id, "ADMIN", null, "ADMIN", "   ")).status)
        assertEquals(400, refused(service.seatForward(n, id, "ADMIN", null, "ADMIN", "x".repeat(601))).status)
        assertEquals(400, refused(service.seatForward(n, id, "HERVOR", null, "HERVOR", "mine already")).status, "she already has it")
        assertEquals(400, refused(service.seatForward(n, id, "AI_AGENT", null, "ADMIN", "no")).status)
        assertEquals(400, refused(service.seatForward(n, id, "SEAT", " ", "ADMIN", "no seat name")).status)
        assertEquals(400, refused(service.seatForward(n, id, "ADMIN", null, "NONE", "owes nobody")).status)
        assertEquals("HERVOR", db.supportQueries.ticket(n).executeAsOne().holder, "a refused forward changes nothing")

        val thread = ok(service.seatForward(n, id, "ADMIN", null, "ADMIN", "Needs a refund decision"))
        assertEquals("ADMIN" to "ADMIN", thread.ticket.holder to thread.ticket.owes)
        val forwarded = lines(n).last { it.event == "FORWARDED" }
        assertEquals(listOf("SYSTEM", "HERVOR", id, "VIEWER"), listOf(forwarded.kind, forwarded.actor, forwarded.seat_token_id, forwarded.visibility))
        val note = lines(n).last { it.kind == "MESSAGE" }
        assertEquals(listOf("HERVOR", "Needs a refund decision", "ADMIN_ONLY", id), listOf(note.author, note.body, note.visibility, note.seat_token_id))
        // An admin's own forward still writes its note as the admin.
        val m = toHervor("u-ben")
        ok(service.forward(m, "root", "ADMIN", null, "ADMIN", "from an admin"))
        assertEquals("ADMIN", lines(m).last { it.kind == "MESSAGE" }.author)
    }

    @Test fun sheCanParkATicketWithASeatAndStillOweTheViewer() = support("seat-park") {
        val (_, id) = hervorOn()
        val n = toHervor()
        val t = ok(service.seatForward(n, id, "SEAT", "ops", "HERVOR", "Ask ops about the codec"))
        assertEquals(Triple("SEAT", "ops", "HERVOR"), Triple(t.ticket.holder, t.ticket.holderSeat, t.ticket.owes))
        assertEquals("TEAM", lines(n).last { it.event == "FORWARDED" }.event_args.orEmpty().trim('[', ']', '"'), "the viewer is told TEAM, never the seat's name")
    }

    // ── FR-9 — switching her off ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun handBackMovesWhatSheHoldsAndOwesToAdminsKeepingTheAgeAndSayingNothingToTheViewer() = support("seat-handback") {
        val (_, tokenId) = hervorOn()
        val held = toHervor()
        val heldAt = db.supportQueries.ticket(held).executeAsOne().owed_since
        val parked = file().ticket.number
        ok(service.forward(parked, "root", "SEAT", "ops", "HERVOR", ""))
        val answered = toHervor("u-ben")
        ok(service.seatReply(answered, tokenId, "done"))
        val untouched = file("u-cleo").ticket.number
        now += 3 * 86_400L   // far past owed_reply_hours: they are overdue the moment an admin owes them

        val moved = service.handBackFromHervor("root")
        assertEquals(setOf(held, parked, answered), moved.toSet())

        val h = db.supportQueries.ticket(held).executeAsOne()
        assertEquals(listOf("ADMIN", "ADMIN", heldAt), listOf(h.holder, h.owes, h.owed_since), "owed_since keeps its age")
        assertTrue(service.adminThread(held)!!.ticket.overdue, "a ticket that was already late shows overdue at once")
        val p = db.supportQueries.ticket(parked).executeAsOne()
        assertEquals(listOf("SEAT", "ADMIN"), listOf(p.holder, p.owes), "owed by an admin though a seat still holds it")
        assertEquals(listOf("ADMIN", "NONE"), db.supportQueries.ticket(answered).executeAsOne().let { listOf(it.holder, it.owes) }, "owed nothing stays owed nothing")
        assertEquals("ADMIN", db.supportQueries.ticket(untouched).executeAsOne().holder)

        val forwardLines = lines(held).filter { it.event == "FORWARDED" }
        assertEquals(2, forwardLines.size, "the admin's forward and the hand-back's")
        val back = forwardLines.last()
        assertEquals(listOf("VIEWER", "ADMIN", "root"), listOf(back.visibility, back.actor, back.actor_label))
        val reason = lines(held).last { it.kind == "MESSAGE" }
        assertEquals(listOf("ADMIN_ONLY", "Hervør was switched off"), listOf(reason.visibility, reason.body))
        assertTrue(lines(held).none { it.author == "HERVOR" || it.author == "AI_AGENT" }, "no forward notice: she cannot speak once she is off")

        // Nothing moves back when she is switched on again.
        configure { copy(hervorEnabled = true) }
        assertEquals("ADMIN", db.supportQueries.ticket(held).executeAsOne().holder)
        assertEquals(emptyList(), service.handBackFromHervor("root").filter { it == held })
    }

    // ── FR-14 — the audit ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theLogNamesHervorAsTheActorAndCarriesNoTokenHashOrBody() = support("seat-audit") {
        val (token, id) = hervorOn()
        val n = toHervor()
        ok(service.seatReply(n, id, "A private sentence of hers"))
        ok(service.post("u-anna", n, "phone", "d1", "reply"))
        ok(service.seatForward(n, id, "ADMIN", null, "ADMIN", "A private note of hers"))
        val entries = service.log().entries
        val replied = entries.single { it.action == "replied" && it.actor == "HERVOR" }
        assertEquals("Hervør", replied.actorLabel)
        val forwarded = entries.first { it.action == "forwarded" }
        assertEquals("HERVOR" to "Hervør", forwarded.actor to forwarded.actorLabel)
        val wire = Json.encodeToString(dev.jellystructure.model.SupportLog.serializer(), service.log())
        val hash = sha256Hex(token)
        assertTrue(token !in wire && hash !in wire && id !in wire && "private" !in wire, wire)
    }

    private object FakeAnswers {
        val forwardToHervor = SupportAi.Answer(
            ticketClass = "BUG", summary = "s", reproduction = null, replyLanguage = "en", reply = "r", decision = "FORWARD",
            forwardTo = "HERVOR", confidence = "HIGH", languageConfidence = "HIGH", promisesSomething = false, claimsState = false,
        )
    }
}
