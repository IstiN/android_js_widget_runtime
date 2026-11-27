package com.test.jsr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mapper registry (plan 6.1 A3, issue #4): every node type of the pinned
 * inventory resolves to a real mapper, a host-contract container, or the
 * documented fallback; registry maps are disjoint; pure prop helpers hold.
 *
 * Plain JVM test (real org.json): NodeProps and the registry stay
 * JaCoCo-visible for the CRAP gate.
 */
class MapperRegistryTest {

    private fun inventory(): List<String> {
        val root = listOf("app/.conformance", ".conformance", "../.conformance")
            .map { File("$it/js_widget_runtime/docs/contract/nodes") }
            .first { it.isDirectory }
        return root.listFiles()!!.map { it.name.removeSuffix(".md") }.sorted()
    }

    @Test
    fun `every inventory type resolves in the registry`() {
        val types = inventory()
        assertEquals(87, types.size)
        for (type in types) {
            val (status, reason) = NodeMapperRegistry.statusOf(type)
            assertTrue("bad status for $type", status == "yes" || status == "partial")
            if (status == "partial") {
                assertTrue(
                    "bad reason for $type",
                    reason == "host contract" || reason == "fallback" || reason == "reference parity",
                )
            }
            if (type in HostContractMapper.types) {
                assertEquals("host contract", reason)
            }
        }
    }

    @Test
    fun `host contract set matches the plan list plus the audio family`() {
        assertEquals(
            setOf("webView", "three", "scene3d", "voxel", "map", "video", "audio", "audio_player"),
            HostContractMapper.types,
        )
    }

    @Test
    fun `per-category factory keys are disjoint and configure is a subset`() {
        val perMapper = NodeMapperRegistry.mappers.sumOf { it.factories.size }
        assertEquals(perMapper, NodeMapperRegistry.factories.size)
        // Simple views (center, column, spacer, host containers) need no configure.
        for (type in NodeMapperRegistry.configure.keys) {
            assertTrue("configure without factory for $type", NodeMapperRegistry.factories.containsKey(type))
        }
    }

    @Test
    fun `statuses of representative types`() {
        assertEquals("yes" to null, NodeMapperRegistry.statusOf("center"))
        assertEquals("yes" to null, NodeMapperRegistry.statusOf("slider"))
        assertEquals(
            "partial" to "host contract",
            NodeMapperRegistry.statusOf("webView"),
        )
        assertEquals("partial" to "fallback", NodeMapperRegistry.statusOf("align"))
        assertEquals(
            "partial" to "reference parity",
            NodeMapperRegistry.statusOf("popupMenu"),
        )
        assertEquals(
            "partial" to "reference parity",
            NodeMapperRegistry.statusOf("tabBar"),
        )
    }

    // ---------- NodeProps (pure) ----------

    private fun props(s: String) = JSONObject(s)

    @Test
    fun `textOf reads data then text then label`() {
        assertEquals("d", NodeProps.textOf(props("""{"data":"d","text":"t","label":"l"}""")))
        assertEquals("t", NodeProps.textOf(props("""{"text":"t","label":"l"}""")))
        assertEquals("l", NodeProps.textOf(props("""{"label":"l"}""")))
        assertEquals("", NodeProps.textOf(props("{}")))
    }

    @Test
    fun `transformText applies the reference text transform`() {
        assertEquals("ABC", NodeProps.transformText(props("""{"data":"abc","textTransform":"uppercase"}""")))
        assertEquals("abc", NodeProps.transformText(props("""{"data":"ABC","textTransform":"lowercase"}""")))
        assertEquals("Abc", NodeProps.transformText(props("""{"data":"abc","textTransform":"capitalize"}""")))
        assertEquals("abc", NodeProps.transformText(props("""{"data":"abc","textTransform":"none"}""")))
        assertEquals("abc", NodeProps.transformText(props("""{"data":"abc"}""")))
    }

    @Test
    fun `firstTab reads the first label of the tabs prop`() {
        assertEquals("One", NodeProps.firstTab(props("""{"tabs":["One","Two"]}""")))
        assertEquals("", NodeProps.firstTab(props("""{"tabs":[]}""")))
        assertEquals("", NodeProps.firstTab(props("{}")))
    }

    @Test
    fun `firstEvent keeps the documented key order`() {
        val events = JSONObject("""{"onPressed":"p","onTap":"t"}""")
        assertEquals("t", NodeProps.firstEvent(events, "onTap", "onPressed"))
        assertEquals("p", NodeProps.firstEvent(events, "onPressed", "onTap"))
        assertEquals(null, NodeProps.firstEvent(events, "onScroll"))
    }

    @Test
    fun `payloadOf defaults to empty object`() {
        val node = JsNode("button", props("""{"payload":{"x":1}}"""), JSONObject(), emptyList(), "n0")
        assertEquals("""{"x":1}""", NodeProps.payloadOf(node))
        val bare = JsNode("button", props("{}"), JSONObject(), emptyList(), "n0")
        assertEquals("{}", NodeProps.payloadOf(bare))
    }

    @Test
    fun `pxOf converts logical px and defaults to zero`() {
        assertEquals(0, NodeProps.pxOf(props("{}"), "width"))
        assertEquals(24, NodeProps.pxOf(props("""{"width":16}"""), "width"))
        assertEquals(true, NodeProps.hasSize(10, 0))
        assertEquals(true, NodeProps.hasSize(0, 10))
        assertEquals(false, NodeProps.hasSize(0, 0))
    }

    @Test
    fun `clampedAlpha clamps and defaults to opaque`() {
        assertEquals(1f, NodeProps.clampedAlpha(props("{}")))
        assertEquals(0.5f, NodeProps.clampedAlpha(props("""{"opacity":0.5}""")))
        assertEquals(1f, NodeProps.clampedAlpha(props("""{"opacity":5}""")))
        assertEquals(0f, NodeProps.clampedAlpha(props("""{"opacity":-1}""")))
    }

    @Test
    fun `sliderProgress maps the min max scale onto 0 to 100`() {
        assertEquals(50, NodeProps.sliderProgress(props("""{"min":0,"max":1,"value":0.5}""")))
        assertEquals(0, NodeProps.sliderProgress(props("""{"min":0,"max":100}""")))
        assertEquals(0, NodeProps.sliderProgress(props("""{"min":5,"max":5,"value":5}""")))
        assertEquals(100, NodeProps.sliderProgress(props("""{"min":0,"max":1,"value":2}""")))
    }

    @Test
    fun `unitProgress maps the unit interval`() {
        assertEquals(50, NodeProps.unitProgress(props("""{"value":0.5}""")))
        assertEquals(0, NodeProps.unitProgress(props("{}")))
        assertEquals(100, NodeProps.unitProgress(props("""{"value":1.5}""")))
    }

    @Test
    fun `indexOfItem falls back to zero`() {
        assertEquals(1, NodeProps.indexOfItem(listOf("a", "b"), "b"))
        assertEquals(0, NodeProps.indexOfItem(listOf("a"), "zz"))
    }

    @Test
    fun `radioChecked requires a matching non-empty group`() {
        assertTrue(NodeProps.radioChecked(props("""{"value":"a","groupValue":"a"}""")))
        assertEquals(false, NodeProps.radioChecked(props("""{"value":"a","groupValue":"b"}""")))
        assertEquals(false, NodeProps.radioChecked(props("""{"value":"a"}""")))
    }

    @Test
    fun `itemsOf reads items and options of both shapes`() {
        assertEquals(
            listOf("Alpha", "Beta"),
            NodeProps.itemsOf(props("""{"options":["Alpha","Beta"]}""")),
        )
        assertEquals(
            listOf("One", "Two"),
            NodeProps.itemsOf(props("""{"items":[{"label":"One"},{"text":"Two"}]}""")),
        )
        assertEquals(emptyList<String>(), NodeProps.itemsOf(props("{}")))
    }

    @Test
    fun `tileTitle falls back to the text-bearing order`() {
        assertEquals("T", NodeProps.tileTitle(props("""{"title":"T"}""")))
        assertEquals("L", NodeProps.tileTitle(props("""{"label":"L"}""")))
        assertEquals("S", NodeProps.tileSubtitle(props("""{"subtitle":"S"}""")))
    }

    @Test
    fun `decorationColor reads the normalize box`() {
        assertEquals("#1E293B", NodeProps.decorationColor(props("""{"decoration":{"color":"#1E293B"}}""")))
        assertEquals("", NodeProps.decorationColor(props("""{"color":"#1E293B"}""")))
    }

    @Test
    fun `valuePayload matches the reference change payload shape`() {
        assertEquals("""{"value":true}""", NodeProps.valuePayload(true))
        assertEquals("""{"value":0.5}""", NodeProps.valuePayload(0.5))
        assertEquals("""{"value":"Beta"}""", NodeProps.valuePayload("Beta"))
        assertEquals("{\"value\":\"x\\ny\"}", NodeProps.valuePayload("x\ny"))
    }
}
