package dev.jellystructure.media

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicReference

/**
 * DEB-4074 (DEB-4032) — a fake Jellyfin 12.1 that holds films, series, episodes and collections, answers the collection routes the way the
 * real one was measured to (a collection's `ParentId` list is empty without `Recursive=true`, `Recursive` also lists a series' episodes,
 * `GET /Items/{id}` wants a `userId`), and records every request it is sent. Names are stand-ins.
 */
internal class FakeJellyfinCollections {
    data class Item(val id: String, val type: String, val name: String, val members: List<String> = emptyList())

    private val state = AtomicReference(emptyList<Item>())
    private val calls = AtomicReference(emptyList<String>())
    private val bodies = AtomicReference(emptyList<String>())
    private val counter = AtomicReference(0)

    /** What a failing Jellyfin does: the list of collections, or any one collection's members, answers 500. */
    @kotlin.concurrent.Volatile var failList = false
    @kotlin.concurrent.Volatile var failMembers = false
    /** 12.1 has ignored an `Ids=` filter before (the `Path` one): answer with the whole library. */
    @kotlin.concurrent.Volatile var ignoreIdsFilter = false

    fun requests(): List<String> = calls.value
    fun bodiesPosted(): List<String> = bodies.value
    fun items(): List<Item> = state.value
    fun deletes(): List<String> = requests().filter { it.startsWith("DELETE /Items/") }

    private fun <T> AtomicReference<List<T>>.add(v: T) { while (true) { val c = value; if (compareAndSet(c, c + v)) return } }
    private fun update(f: (List<Item>) -> List<Item>) { while (true) { val c = state.value; if (state.compareAndSet(c, f(c))) return } }

    fun put(item: Item) = update { it + item }
    /** A change made in Jellyfin's own dashboard: no request reaches the fake for it. */
    fun setMembers(id: String, vararg members: String) = update { l -> l.map { if (it.id == id) it.copy(members = members.toList()) else it } }
    fun film(id: String, name: String = id) = put(Item(id, "Movie", name))
    fun series(id: String, name: String = id) { put(Item(id, "Series", name)); put(Item("$id-ep1", "Episode", "$name episode")) }
    fun collection(id: String, name: String, vararg members: String) = put(Item(id, "BoxSet", name, members.toList()))

    private fun json(i: Item) = """{"Id":"${i.id}","Name":"${i.name}","Type":"${i.type}"}"""

    /** A collection's members as a recursive list shows them: its titles, and the episodes of each member series. */
    private fun recursive(box: Item): List<Item> {
        val all = state.value.associateBy { it.id }
        return box.members.mapNotNull { all[it] }.flatMap { m -> listOf(m) + (if (m.type == "Series") all.values.filter { it.id.startsWith("${m.id}-ep") } else emptyList()) }
    }

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/Items") {
                val q = call.request.queryParameters
                calls.add("GET " + call.request.uri)
                val types = q["IncludeItemTypes"]?.split(',')?.toSet()
                val all = state.value
                val pool: List<Item> = when {
                    q["ParentId"] != null ->
                        if (failMembers) return@get call.respondText("boom", status = HttpStatusCode.InternalServerError)
                        else if (q["Recursive"] != "true") emptyList()
                        else all.firstOrNull { it.id == q["ParentId"] }?.let(::recursive).orEmpty()
                    q["Ids"] != null && !ignoreIdsFilter -> q["Ids"]!!.split(',').mapNotNull { id -> all.firstOrNull { it.id == id } }
                    else -> {
                        if (failList && types == setOf("BoxSet")) return@get call.respondText("boom", status = HttpStatusCode.InternalServerError)
                        all.filter { it.type != "Episode" || q["Recursive"] == "true" }
                    }
                }.filter { types == null || it.type in types }
                val start = q["StartIndex"]?.toIntOrNull() ?: 0
                val limit = q["Limit"]?.toIntOrNull() ?: Int.MAX_VALUE
                call.respondText(
                    """{"Items":[${pool.drop(start).take(limit).joinToString(",") { json(it) }}],"TotalRecordCount":${pool.size},"StartIndex":$start}""",
                    ContentType.Application.Json,
                )
            }
            get("/Items/{id}") {
                calls.add("GET " + call.request.uri)
                val item = state.value.firstOrNull { it.id == call.parameters["id"] }
                when {
                    call.request.queryParameters["userId"] == null -> call.respondText("Error processing request.", status = HttpStatusCode.BadRequest)
                    item == null -> call.respondText("", status = HttpStatusCode.NotFound)
                    else -> call.respondText(
                        """{"Id":"${item.id}","Name":"${item.name}","Type":"${item.type}","Overview":"kept overview","ProviderIds":{"Tmdb":"7"},"LockData":false,"LockedFields":[],"Tags":["a"]}""",
                        ContentType.Application.Json,
                    )
                }
            }
            post("/Items/{id}") {
                calls.add("POST " + call.request.uri)
                val body = call.receiveText()
                bodies.add(body)
                val id = call.parameters["id"]
                val name = Regex("\"Name\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                update { l -> l.map { if (it.id == id && name != null) it.copy(name = name) else it } }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
            delete("/Items/{id}") {
                calls.add("DELETE " + call.request.uri)
                val id = call.parameters["id"]
                update { l -> l.filter { it.id != id } }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
            post("/Collections") {
                calls.add("POST " + call.request.uri)
                val n = counter.value + 1; counter.value = n
                val id = "new-box-$n"
                val ids = call.request.queryParameters["Ids"]?.split(',').orEmpty()
                put(Item(id, "BoxSet", call.request.queryParameters["Name"] ?: "", ids))
                call.respondText("""{"Id":"$id"}""", ContentType.Application.Json)
            }
            post("/Collections/{id}/Items") {
                calls.add("POST " + call.request.uri)
                val ids = call.request.queryParameters["Ids"]?.split(',').orEmpty()
                update { l -> l.map { if (it.id == call.parameters["id"]) it.copy(members = (it.members + ids).distinct()) else it } }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
            delete("/Collections/{id}/Items") {
                calls.add("DELETE " + call.request.uri)
                val ids = call.request.queryParameters["Ids"]?.split(',').orEmpty().toSet()
                update { l -> l.map { if (it.id == call.parameters["id"]) it.copy(members = it.members.filter { m -> m !in ids }) else it } }
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
    }.start(wait = false)

    val url = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
    fun stop() = server.stop(0, 0)
}
