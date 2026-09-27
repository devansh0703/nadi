package com.nadi.health.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model answers in JSON; the screen must show label/value lines.
 *
 * Object key order is deliberately NOT asserted: Android's org.json preserves
 * document order while the JVM test implementation does not, and the UI contract
 * is "every label appears, correctly indented, without braces" — not ordering.
 * Arrays are ordered, so those are asserted exactly.
 */
class ReadableBodyTest {

    /** Compares output line-by-line as a sorted multiset: content + indentation pinned, order ignored. */
    private fun assertSameLines(expected: String, actual: String) =
        assertEquals(expected.trim().lines().sorted(), actual.trim().lines().sorted())

    // ── Structured payload already parsed by the viewmodel ────────────

    @Test
    fun `parsed json object renders label value lines not braces`() {
        val json = JSONObject(
            """{"dish_name":"Grilled salmon","confidence":0.87,"calories":412}"""
        )

        val out = readableBody(json, "")

        assertSameLines(
            """
            Dish name: Grilled salmon
            Confidence: 0.87
            Calories: 412
            """.trimIndent(),
            out
        )
        assertFalse("no raw JSON braces should reach the screen", out.contains("{"))
    }

    @Test
    fun `snake case keys are humanised`() {
        val json = JSONObject("""{"blood_sugar_level":6.2,"resting_heart_rate":58}""")

        val out = readableBody(json, "")

        assertTrue(out.contains("Blood sugar level: 6.2"))
        assertTrue(out.contains("Resting heart rate: 58"))
    }

    // ── Raw body text that happens to be JSON ─────────────────────────

    @Test
    fun `raw json text body is parsed and rendered`() {
        val body = """{"plan":[{"day":"Mon","minutes":30},{"day":"Tue","minutes":45}]}"""

        val out = readableBody(null, body)

        assertSameLines(
            """
            Plan:
              •
                Day: Mon
                Minutes: 30
              •
                Day: Tue
                Minutes: 45
            """.trimIndent(),
            out
        )
    }

    @Test
    fun `top level array renders bullets in order`() {
        val out = readableBody(null, """["water","spinach","lentils"]""")

        assertEquals(
            """
            • water
            • spinach
            • lentils
            """.trimIndent(),
            out
        )
    }

    @Test
    fun `nested object values indent by two spaces per level`() {
        val json = JSONObject(
            """{"activity":{"type":"walk","steps":4210},"score":81}"""
        )

        val out = readableBody(json, "")

        assertSameLines(
            """
            Activity:
              Type: walk
              Steps: 4210
            Score: 81
            """.trimIndent(),
            out
        )
        // Nested keys are indented two spaces deeper than their parent label.
        assertTrue(out.lines().any { it == "  Type: walk" })
    }

    @Test
    fun `null and json null render as em dash not literal null`() {
        val json = JSONObject("""{"note":null}""")

        val out = readableBody(json, "")

        assertEquals("Note: —", out)
        assertFalse(out.contains("null"))
    }

    @Test
    fun `json null inside an array renders as em dash`() {
        val out = readableBody(null, """[1,null,3]""")

        assertEquals("• 1\n• —\n• 3", out)
    }

    // ── Non-JSON answers pass through untouched ───────────────────────

    @Test
    fun `plain prose is returned verbatim`() {
        val prose = "Your resting heart rate has been stable this week."

        assertEquals(prose, readableBody(null, prose))
    }

    @Test
    fun `malformed json looking text falls back to raw text`() {
        val broken = "{not valid json"

        assertEquals(broken, readableBody(null, broken))
    }

    @Test
    fun `parsed object wins over body text when both present`() {
        val json = JSONObject("""{"winner":"parsed"}""")

        val out = readableBody(json, """{"winner":"body"}""")

        assertEquals("Winner: parsed", out)
    }

    // ── Array of objects: the common report shape ─────────────────────

    @Test
    fun `array of objects nests each entry under a bullet`() {
        val json = JSONObject(
            """{"meals":[{"name":"Oats","kcal":310},{"name":"Soup","kcal":240}]}"""
        )

        val out = readableBody(json, "")

        assertSameLines(
            """
            Meals:
              •
                Name: Oats
                Kcal: 310
              •
                Name: Soup
                Kcal: 240
            """.trimIndent(),
            out
        )
        assertEquals("exactly one bullet per meal", 2, out.lines().count { it.trim() == "•" })
    }

    // ── Renderer primitives ───────────────────────────────────────────

    @Test
    fun `renderReadable on primitive scalar does not crash`() {
        assertEquals("42", renderReadable(42))
        assertEquals("—", renderReadable(null))
    }

    @Test
    fun `renderReadable accepts arrays directly`() {
        assertEquals("• a\n• b", renderReadable(JSONArray("""["a","b"]""")))
    }
}
