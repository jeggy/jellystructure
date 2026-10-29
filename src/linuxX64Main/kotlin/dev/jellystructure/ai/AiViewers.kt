package dev.jellystructure.ai

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.util.epochTicksToIso
import kotlinx.serialization.Serializable

/**
 * DEB-2826 (DEB-2823, FR-1.7) — one row of `GET /api/ai/viewers`: a Jellyfin user, whether the AI re-ranks them, and the sentences the
 * admin shows as they are (FR-12 of DEB-1664: built here, not in the browser). Additive wire.
 */
@Serializable
data class AiViewerDto(
    val userId: String,
    val name: String,
    /** The effective switch: the stored choice, else the default. */
    val rerank: Boolean,
    /** True when the switch is a stored choice; false when it is the default. */
    val explicit: Boolean,
    /** Why the switch reads as it does: *off — test account*, *on by default*, or who set it and when. */
    val reason: String,
    val lastSeen: String,
    val lastSeenAt: Long? = null,
    val aiOrder: String,
    val aiOrderAt: Long? = null,
)

object AiViewers {
    private fun day(sec: Long) = epochTicksToIso(sec * 10_000_000L).take(10)

    /** Every user in [users] (id to name), by name, with the newest Ravilo device of theirs from [devices] (`lastSeen` is in milliseconds). */
    fun rows(users: List<Pair<String, String>>, devices: List<DeviceData>, switch: RerankSwitch): List<AiViewerDto> {
        val seen = devices.groupBy { RerankSwitch.key(it.jellyfinUserId) }.mapValues { (_, d) -> d.maxOf { it.lastSeen } / 1000 }
        val stored = switch.stored()
        return users.sortedWith(compareBy({ it.second.lowercase() }, { it.first })).map { (id, name) ->
            val k = RerankSwitch.key(id)
            val row = stored[k]
            val choice = row?.rerank?.let { it != 0L }
            val testAccount = dev.jellystructure.suggestions.SuggestionEngine.isTestAccount(name)
            val on = choice ?: !testAccount
            val reason = when {
                choice != null -> "${if (choice) "on" else "off"} — switched ${if (choice) "on" else "off"} by ${row.set_by?.ifBlank { null } ?: "an admin"}" +
                    (row.set_at?.let { " on ${day(it)}" } ?: "")
                testAccount -> "off — test account"
                else -> "on by default"
            }
            val lastSeenAt = seen[k]?.takeIf { it > 0 }
            val ordered = switch.orderedAt(id)
            AiViewerDto(
                userId = id, name = name, rerank = on, explicit = choice != null, reason = reason,
                lastSeen = lastSeenAt?.let { "last seen ${day(it)}" } ?: "never seen on a Ravilo device", lastSeenAt = lastSeenAt,
                aiOrder = ordered?.let { "follows an AI order from ${day(it)}" } ?: "no AI order yet", aiOrderAt = ordered,
            )
        }
    }
}
