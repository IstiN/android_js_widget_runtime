package com.test.jsr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Равенство dump с нормализатором обвязки пина (harness/normalize.mjs). */
class AuiDumpTest {

    /** normalizeTree(hello renders[0]) обвязки пина + raw-ключи union-дампа. */
    private val harnessHello =
        """{"type":"center","props":{},"events":{},"children":[{"type":"column","props":{"mainAxisSize":"min","spacing":12},"events":{},"children":[{"type":"text","props":{"data":"Hello from JS!","fontSize":24,"fontWeight":"bold","color":"#F1F5F9"},"events":{},"children":[],"nodeId":"n2"},{"type":"sizedBox","props":{"height":12},"events":{},"children":[],"nodeId":"n3"},{"type":"textButton","props":{"data":"Tap me","text":"Tap me"},"events":{"onTap":"tap"},"children":[],"nodeId":"n4"}],"nodeId":"n1"}],"nodeId":"n0"}"""

    @Test
    fun `dump of the hello sample equals the harness normalizer output`() {
        val file = listOf(
            "app/.conformance", ".conformance", "../.conformance",
        ).map { "$it/js_widget_runtime/docs/conformance/samples/flutter/hello.result.json" }
            .map { java.io.File(it) }.first { it.exists() }
        val sample = JSONObject(file.bufferedReader().use { it.readText() })
        val payload = JSONObject(sample.getJSONArray("renders").getString(0))

        val tree = JsWidgetTree.normalize(payload)
        val dump = AuiDump.dump(tree, viewOf = { null })

        assertTrue(
            "dump differs from harness normalizeTree",
            jsonEquals(dump, JSONObject(harnessHello)),
        )
    }

    @Test
    fun `dump keeps the raw vocabulary next to the aliases`() {
        // l0_button / l0_container class: the delta paths assert raw names
        // (button.text, container.color) while the record holds the aliased
        // data/decoration; the dump union carries both (plan 1.3).
        val tree = JsWidgetTree.normalize(
            JSONObject(
                """{"type":"column","children":[
                   {"type":"button","text":"L0"},
                   {"type":"container","color":"#1e293b","child":{"type":"text","data":"x"}}]}""",
            ),
        )
        val button = tree.children[0]
        assertEquals(
            setOf("data", "text"),
            button.dumpProps().keys().asSequence().toSet(),
        )
        val container = tree.children[1]
        assertEquals("#1E293B", container.dumpProps().optString("color"))
        assertEquals(
            "#1E293B",
            container.dumpProps().optJSONObject("decoration")?.optString("color"),
        )
    }

    /** Глубокое сравнение JSON (порядок ключей не важен; числа как double). */
    private fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> {
            if (a.length() != b.length()) false
            else a.keys().asSequence().all { k -> b.has(k) && jsonEquals(a.get(k), b.get(k)) }
        }
        a is org.json.JSONArray && b is org.json.JSONArray -> {
            if (a.length() != b.length()) false
            else (0 until a.length()).all { i -> jsonEquals(a.get(i), b.get(i)) }
        }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
}
