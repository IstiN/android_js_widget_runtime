package com.test.jsr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRuntimeTest {
    private val messages = mutableListOf<Pair<String, String>>()

    @Test
    fun `boot evaluates iid, runtime half, widget half, widget file in order`() {
        val engine = FakeJsEngine()
        val runtime = WidgetRuntime(engine) { ch, p -> messages.add(ch to p) }

        val err = runtime.boot("iid-1", "RUNTIME;", "WIDGET;", "WIDGET_CODE;")

        assertNull(err)
        assertEquals(
            listOf(
                "var __IID = \"iid-1\";" to "__iid",
                "RUNTIME;" to "bootstrap-runtime.js",
                "WIDGET;" to "bootstrap-widget.js",
                "WIDGET_CODE;" to "widget.js",
            ),
            engine.evals.map { it.code to it.sourceName },
        )
    }

    @Test
    fun `boot escapes iid quotes`() {
        val engine = FakeJsEngine()
        val runtime = WidgetRuntime(engine) { ch, p -> messages.add(ch to p) }

        runtime.boot("a\"b\\c", "", "", "")

        assertEquals("var __IID = \"a\\\"b\\\\c\";", engine.evals[0].code)
    }

    @Test
    fun `boot returns the first eval error and stops`() {
        val engine = FakeJsEngine().apply { errors["bootstrap-widget.js"] = "boom" }
        val runtime = WidgetRuntime(engine) { ch, p -> messages.add(ch to p) }

        val err = runtime.boot("iid-1", "RUNTIME;", "WIDGET;", "WIDGET_CODE;")

        assertEquals("boom", err)
        assertEquals(3, engine.evals.size)
    }

    @Test
    fun `render message reaches the registered handler`() {
        val engine = FakeJsEngine()
        val runtime = WidgetRuntime(engine) { ch, p -> messages.add(ch to p) }

        runtime.onJsMessage("__jsr_render", "{\"type\":\"text\"}")
        runtime.onJsMessage("__jsr_set_title", "Hello")

        assertEquals(
            listOf("__jsr_render" to "{\"type\":\"text\"}", "__jsr_set_title" to "Hello"),
            messages,
        )
    }

    @Test
    fun `dispose releases the engine`() {
        val engine = FakeJsEngine()
        val runtime = WidgetRuntime(engine) { ch, p -> messages.add(ch to p) }

        runtime.dispose()

        assertTrue(engine.disposed)
    }
}
