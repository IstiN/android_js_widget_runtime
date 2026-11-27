package com.test.jsr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

/**
 * A3 (kit 68e149d): узел resolved-типа scroll несёт scrollOffset /
 * scrollExtent / viewportExtent в логических px в каждом дампе; conversion
 * device px -> logical по density. ScrollView в Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi") // density 2.0: conversion видно в числе
class AuiDumpScrollTest {
    private fun scrollNode(): JsNode = JsWidgetTree.normalize(
        JSONObject("""{"type":"scroll","children":[{"type":"text","data":"L0 text"}]}"""),
    )

    private fun scrollOf(): ScrollColumn {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val sv = ScrollColumn(context)
        val content = sv.getChildAt(0) as android.widget.LinearLayout
        sv.layout(0, 0, 300, 400)
        // Ребёнок ПОСЛЕ родителя: layout родителя пере-укладывает ребёнка в 400.
        content.layout(0, 0, 300, 2000)
        return sv
    }

    @Test
    fun `scroll node dumps offset extent and viewport in logical px`() {
        val sv = scrollOf()
        sv.scrollTo(0, 600)

        val props = AuiDump.dump(scrollNode(), viewOf = { if (it == "n0") sv else null })
            .getJSONObject("props")

        // density 2.0: device px 600/2000/400 -> logical 300/1000/200.
        assertEquals(300.0, props.getDouble("scrollOffset"), 1e-6)
        assertEquals(sv.scrollRangePx() / 2f, props.getDouble("scrollExtent").toFloat(), 1e-3f)
        assertEquals(200.0, props.getDouble("viewportExtent"), 1e-6)
    }

    @Test
    fun `unscrolled scroll node dumps zero offset and non-negative state`() {
        val props = AuiDump.dump(scrollNode(), viewOf = { if (it == "n0") scrollOf() else null })
            .getJSONObject("props")

        assertEquals(0.0, props.getDouble("scrollOffset"), 1e-6)
        assertTrue(props.getDouble("scrollExtent") >= 0.0)
        assertTrue(props.getDouble("viewportExtent") >= 0.0)
    }

    @Test
    fun `non scroll nodes carry no scroll props`() {
        val sv = scrollOf()
        val tree = JsWidgetTree.normalize(
            JSONObject("""{"type":"column","children":[{"type":"text","data":"x"}]}"""),
        )
        val dump = AuiDump.dump(tree, viewOf = { if (it == "n0") sv else null })

        assertEquals(false, dump.getJSONObject("props").has("scrollOffset"))
        assertEquals(false, dump.getJSONObject("props").has("scrollExtent"))
        assertEquals(false, dump.getJSONObject("props").has("viewportExtent"))
    }
}
