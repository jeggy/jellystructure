package dev.jellystructure.autofetch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3388 (DEB-2478, FR-5.1) — the answer schema against the limits the real API enforces on a request's schemas: at most 24 optional
 * parameters and 16 union-typed ones (`type` arrays or `anyOf`). The scripted model of the other tests cannot see either; DEB-3373 found
 * the first at 74 against the live API.
 */
class ModelRecipeSchemaTest {
    private var optional = 0
    private var unions = 0

    private fun walk(node: JsonElement) {
        if (node !is JsonObject) return
        if (node["anyOf"] != null || node["type"] is JsonArray) unions++
        val props = node["properties"] as? JsonObject
        if (props != null) {
            val required = node["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            optional += props.keys.count { it !in required }
            props.values.forEach { walk(it) }
        }
        node["items"]?.let { walk(it) }
    }

    @Test
    fun `the answer schema stays inside the APIs limits on optional and union parameters`() {
        walk(ModelRecipe.SCHEMA)
        assertTrue(optional <= 24, "optional parameters: $optional")
        assertTrue(unions <= 16, "union-typed parameters: $unions")
    }

    @Test
    fun `an answer that fills every required property and leaves the unused ones blank reads as a recipe without them`() {
        val blank = "\"\""
        val text = """{"verdict":"series","reason":"x","recipe":{"list":{"kind":"html","item_selector":"li.ep","url":"","items_path":"",
            "fields":{"title":"a","link":"a@href","published":$blank,"description":$blank,
            "id":$blank,"season":$blank,"episode":$blank,"duration":$blank,"image":$blank}},
            "media":{"kind":"hls","manifest":"https://media.example.fo/{value}/master.m3u8","item_page":{"selector":"","attr":"","pattern":"id=(?<value>[a-z0-9]+)"}},
            "numbering":"source","pattern":[],"series":{"name":"h1","description":$blank,"image":$blank},
            "hosts":["media.example.fo"],"audio_language":"fao","subtitle_language":""}}"""
        val answer = assertNotNull(ModelRecipe.parse(text))
        val recipe = ModelRecipe.toRecipe(assertNotNull(answer.recipe), "https://site.example.fo/x", "m", "2026-11-02")
        assertEquals(setOf("title", "link"), recipe.list.fields.keys)
        assertNull(recipe.list.next)
        assertNull(recipe.media.manifestField)
        assertEquals("text", recipe.media.itemPage?.attr)
        assertEquals("h1", recipe.series?.name?.selector)
        assertNull(recipe.series?.description); assertNull(recipe.series?.image)
    }

    @Test
    fun `a listing field is one string and the schema holds no three-key field object`() {
        val fields = ModelRecipe.SCHEMA["properties"]!!.jsonObject["recipe"]!!.jsonObject["properties"]!!.jsonObject["list"]!!.jsonObject["properties"]!!.jsonObject["fields"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue(fields.values.all { it.jsonObject["type"]!!.jsonPrimitive.content == "string" })
        assertTrue(!ModelRecipe.SCHEMA.toString().contains("\"path\":{"), "a field is not an object with a path key")
    }

    @Test
    fun `a field string reads as selector at attribute or json path and blank is none`() {
        assertEquals(Field(selector = "a", attr = "text"), ModelRecipe.field("a"))
        assertEquals(Field(selector = "a", attr = "href"), ModelRecipe.field("a@href"))
        assertEquals(Field(selector = "", attr = "data-id"), ModelRecipe.field("@data-id"))
        assertEquals(Field(selector = "li > time", attr = "datetime"), ModelRecipe.field(" li > time@datetime "))
        assertEquals(Field(selector = "a[href*=\"@\"]", attr = "text"), ModelRecipe.field("a[href*=\"@\"]"))
        assertEquals(Field(path = "a.b[0].c"), ModelRecipe.field("json:a.b[0].c"))
        assertNull(ModelRecipe.field(""))
        assertNull(ModelRecipe.field("  "))
        assertNull(ModelRecipe.field("json:"))
        assertNull(ModelRecipe.field(null))
    }
}
