package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 298 — the pure rules: declarations, capabilities, the reading of every Jellyfin frame, the stale sweep. */
class JellyfinRemoteTest {

    @Test
    fun `a declaration keeps the known commands in order once case-insensitively`() {
        assertNull(parseRemoteDeclaration(null), "absent = an app older than R354")
        assertEquals(emptyList(), parseRemoteDeclaration(""))
        assertEquals(listOf("PlayState", "SetVolume", "ToggleMute"), parseRemoteDeclaration("togglemute, setvolume,PLAYSTATE,Bogus,playstate"))
        assertEquals(REMOTE_COMMANDS, parseRemoteDeclaration(REMOTE_COMMANDS.reversed().joinToString(",")))
    }

    @Test
    fun `an app that declares nothing registers phase 110's capabilities byte for byte`() {
        assertEquals(
            """{"PlayableMediaTypes":["Video"],"SupportedCommands":["DisplayMessage","Play","Playstate"],"SupportsMediaControl":true}""",
            capabilitiesBody(null),
        )
    }

    @Test
    fun `an app registers what it declared and Video only when it takes Play`() {
        assertEquals(
            """{"PlayableMediaTypes":["Video"],"SupportedCommands":["DisplayMessage","Play","PlayState","SetVolume","VolumeUp","VolumeDown","Mute","Unmute","ToggleMute"],"SupportsMediaControl":true}""",
            capabilitiesBody(REMOTE_COMMANDS),
        )
        // A receiver obeys and is never a *Play on* target.
        assertEquals(
            """{"PlayableMediaTypes":[],"SupportedCommands":["PlayState","SetVolume"],"SupportsMediaControl":true}""",
            capabilitiesBody(listOf("PlayState", "SetVolume")),
        )
    }

    private fun playstate(command: String, ticks: Long? = null) =
        """{"MessageType":"Playstate","MessageId":"m","Data":{"Command":"$command"${ticks?.let { ",\"SeekPositionTicks\":$it" } ?: ""},"ControllingUserId":"u"}}"""

    private fun general(name: String, args: String = "{}") =
        """{"MessageType":"GeneralCommand","MessageId":"m","Data":{"Name":"$name","ControllingUserId":"u","Arguments":$args}}"""

    @Test
    fun `every Playstate command passes through with its own name a seek in milliseconds`() {
        for (c in listOf("Stop", "Pause", "Unpause", "PlayPause", "NextTrack", "PreviousTrack", "Rewind", "FastForward")) {
            assertEquals(BridgeCommand.Playstate(c, null), parseJellyfinMessage(playstate(c)), c)
        }
        assertEquals(BridgeCommand.Playstate("Seek", 754_000L), parseJellyfinMessage(playstate("Seek", 7_540_000_000L)))
        assertNull(parseJellyfinMessage(playstate("Explode")), "an unknown command is not forwarded")
    }

    @Test
    fun `volume commands become player commands with Jellyfin's string arguments read`() {
        assertEquals(BridgeCommand.Player("set_volume", """{"volume":35}"""), parseJellyfinMessage(general("SetVolume", """{"Volume":"35"}""")))
        assertEquals(BridgeCommand.Player("set_volume", """{"volume":100}"""), parseJellyfinMessage(general("SetVolume", """{"Volume":"140"}""")))
        assertNull(parseJellyfinMessage(general("SetVolume", """{"Volume":"loud"}""")))
        assertEquals(BridgeCommand.Player("volume_up", null), parseJellyfinMessage(general("VolumeUp")))
        assertEquals(BridgeCommand.Player("volume_down", null), parseJellyfinMessage(general("VolumeDown")))
        assertEquals(BridgeCommand.Player("mute", """{"muted":true}"""), parseJellyfinMessage(general("Mute")))
        assertEquals(BridgeCommand.Player("mute", """{"muted":false}"""), parseJellyfinMessage(general("Unmute")))
        assertEquals(BridgeCommand.Player("mute", null), parseJellyfinMessage(general("ToggleMute")))
        assertNull(parseJellyfinMessage(general("GoHome")), "a navigation command is not a playback command")
    }

    @Test
    fun `a message and a play keep their old shapes and keepalives are nothing`() {
        assertEquals(
            BridgeCommand.Message("Dinner", "Mum", 5000L),
            parseJellyfinMessage(general("DisplayMessage", """{"Text":"Dinner","Header":"Mum","TimeoutMs":"5000"}""")),
        )
        assertEquals(
            BridgeCommand.PlayItem("abc", 60_000L),
            parseJellyfinMessage("""{"MessageType":"Play","Data":{"ItemIds":["abc"],"StartPositionTicks":600000000,"PlayCommand":"PlayNow"}}"""),
        )
        assertNull(parseJellyfinMessage("""{"MessageType":"ForceKeepAlive","Data":60}"""))
        assertNull(parseJellyfinMessage("""{"MessageType":"KeepAlive"}"""))
        assertNull(parseJellyfinMessage("not json"))
    }

    private fun device(id: String, user: String = "u1", kind: String = "tv") = DeviceData(
        deviceId = id, deviceToken = "dt-$id", jellyfinUserId = user, jellyfinUsername = user, jellyfinUserToken = "jt-$user", isAdmin = false, kind = kind,
    )

    @Test
    fun `the sweep ends only idle Ravilo sessions of devices it holds and does not bridge`() {
        val now = 1_000_000L
        val receiver = device("cast-aaaaaaaaaa", kind = "cast")
        val phone = device("phone1", kind = "phone")
        val tv = device("tv1")
        val web = device("web1", kind = "web")
        val sessions = parseJellyfinSessions(
            """[
              {"Client":"Ravilo","DeviceId":"ravilo-cast-aaaaaaaaaa-u1","IsActive":true,"LastActivityDate":"1970-01-12T13:44:00.0000000Z"},
              {"Client":"Ravilo","DeviceId":"ravilo-phone1-u1","IsActive":false,"LastActivityDate":"1970-01-12T13:44:00.0000000Z","NowPlayingItem":{"Id":"x"}},
              {"Client":"Ravilo","DeviceId":"ravilo-tv1-u1","IsActive":true,"LastActivityDate":"1970-01-12T13:44:00.0000000Z"},
              {"Client":"Ravilo","DeviceId":"ravilo-web1-u1","IsActive":false,"LastActivityDate":"1970-01-12T13:44:00.0000000Z"},
              {"Client":"Jellyfin Web","DeviceId":"ravilo-cast-aaaaaaaaaa-u1","IsActive":false,"LastActivityDate":"1970-01-01T00:00:00.0000000Z"},
              {"Client":"Ravilo","DeviceId":"ravilo-gone-u1","IsActive":false,"LastActivityDate":"1970-01-01T00:00:00.0000000Z"}
            ]""",
        )!!
        // 1970-01-12T13:44:00Z is 999 840 s: 160 s before "now", older than the grace — so web is left alone only
        // because it is bridged.
        val stale = staleRaviloSessions(sessions, listOf(receiver, phone, tv, web), bridged = { it == "web1" }, nowEpochSec = now, graceSec = 90)
        // Jellyfin 12.1 reports a session with no socket as IsActive: true (every stale receiver on production read
        // so, 2026-10-02), so IsActive never protects a session: the idle receiver and the idle, unbridged TV both end.
        assertEquals(listOf("cast-aaaaaaaaaa", "tv1"), stale.map { it.deviceId }, "playing, bridged, foreign and unknown sessions are left alone")

        val recent = staleRaviloSessions(sessions, listOf(receiver), bridged = { false }, nowEpochSec = 999_840L + 60L, graceSec = 90)
        assertTrue(recent.isEmpty(), "inside the grace nothing is ended")
    }
}
