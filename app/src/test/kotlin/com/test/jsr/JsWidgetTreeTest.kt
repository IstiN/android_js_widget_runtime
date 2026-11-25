package com.test.jsr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsWidgetTreeTest {
    @Test
    fun `hello payload normalizes to the harness shape`() {
        val payload = JSONObject(
            """{"type":"center","child":{"type":"column","mainAxisSize":"min","spacing":12,
               "children":[{"type":"text","data":"Hello from JS!","style":{"fontSize":24,"fontWeight":"bold","color":"#f1f5f9"}},
               {"type":"sizedBox","height":12},{"type":"textButton","text":"Tap me","onTap":"tap"}]}}""",
        )

        val tree = JsWidgetTree.normalize(payload)

        assertEquals("center", tree.type)
        assertEquals(1, tree.children.size)
        val column = tree.children[0]
        assertEquals("column", column.type)
        assertEquals("n1", column.nodeId)
        assertEquals("min", column.props.optString("mainAxisSize"))
        assertEquals(12, column.props.getInt("spacing"))
        val text = column.children[0]
        assertEquals("text", text.type)
        assertEquals("Hello from JS!", text.props.optString("data"))
        assertEquals(24, text.props.getInt("fontSize"))
        assertEquals("#F1F5F9", text.props.optString("color")) // правило 6
        assertEquals(0, text.events.length())
        val button = column.children[2]
        assertEquals("textButton", button.type)
        assertEquals("Tap me", button.props.optString("data")) // алиас text -> data
        assertEquals("tap", button.events.optString("onTap"))  // правило 2
    }

    @Test
    fun `key prop is captured but never in props`() {
        val tree = JsWidgetTree.normalize(JSONObject("""{"type":"text","key":"hero","data":"x"}"""))

        assertEquals("hero", tree.key)
        assertEquals(null, tree.props.optJSONObject("key"))
        assertTrue(!tree.props.has("key"))
    }

    @Test
    fun `child and items unify into children, string child becomes text`() {
        val tree = JsWidgetTree.normalize(
            JSONObject(
                """{"type":"row","child":{"type":"text","data":"single"},
                   "other":{"type":"column","items":[{"type":"text","data":"a"},"str"]}}""",
            ),
        )
        // Контракт: только child/children/items верхнего уровня становятся детьми;
        // произвольные ключи остаются в props как есть (normalize.mjs пина).
        assertEquals(1, tree.children.size)
        assertEquals("text", tree.children[0].type)
        assertEquals("single", tree.children[0].props.optString("data"))
        assertEquals("column", tree.props.getJSONObject("other").optString("type"))
    }

    @Test
    fun `string item becomes a text node`() {
        val tree = JsWidgetTree.normalize(
            JSONObject("""{"type":"column","items":["str",{"type":"text","data":"t"}]}"""),
        )
        assertEquals(2, tree.children.size)
        assertEquals("text", tree.children[0].type)
        assertEquals("str", tree.children[0].props.optString("data"))
    }

    @Test
    fun `style merges under node keys`() {
        val tree = JsWidgetTree.normalize(
            JSONObject("""{"type":"text","data":"x","color":"#112233","style":{"color":"#445566","fontSize":9}}"""),
        )

        assertEquals("#112233", tree.props.optString("color")) // ключ узла старше
        assertEquals(9, tree.props.getInt("fontSize"))
    }

    @Test
    fun `field aliases apply idempotently`() {
        // onChanged -> onChange (switch), placeholder -> hint и value -> initialValue (textField),
        // content -> data, title/text -> data (button), uri -> url (image), decoration у container.
        val switch = JsWidgetTree.normalize(JSONObject("""{"type":"switch","onChanged":"sel","value":true}"""))
        assertEquals("switch", switch.type)
        assertEquals("sel", switch.events.optString("onChange"))
        assertEquals(true, switch.props.optBoolean("value"))

        val field = JsWidgetTree.normalize(
            JSONObject("""{"type":"textField","placeholder":"name","value":"Ann","onChanged":"c"}"""),
        )
        assertEquals("textField", field.type)
        assertEquals("name", field.props.optString("hint"))
        assertEquals("Ann", field.props.optString("initialValue"))
        assertEquals("c", field.events.optString("onChange"))

        val button = JsWidgetTree.normalize(JSONObject("""{"type":"button","title":"Go","content":"Go"}"""))
        assertEquals("Go", button.props.optString("data"))

        val image = JsWidgetTree.normalize(JSONObject("""{"type":"img","uri":"http://x/y.png"}"""))
        assertEquals("image", image.type)
        assertEquals("http://x/y.png", image.props.optString("url"))

        val container = JsWidgetTree.normalize(JSONObject("""{"type":"container","color":"#1e293b"}"""))
        assertEquals("#1E293B", container.props.optJSONObject("decoration")?.optString("color"))

        // Идемпотентность: обе пропсы уже в dump — алиасы ничего не двигают.
        val both = JsWidgetTree.normalize(JSONObject("""{"type":"textButton","text":"T","data":"T"}"""))
        assertEquals("T", both.props.optString("text"))
        assertEquals("T", both.props.optString("data"))
    }

    @Test
    fun `type aliases resolve`() {
        val tree = JsWidgetTree.normalize(
            JSONObject("""{"type":"Column","child":{"type":"CircleAvatar","child":{"type":"SVG"}}}"""),
        )
        assertEquals("column", tree.type)
        assertEquals("circleAvatar", tree.children[0].type)
        assertEquals("svg", tree.children[0].children[0].type)
    }

    @Test
    fun `rgb colors normalize to hex and nodeIds are depth-first`() {
        val tree = JsWidgetTree.normalize(
            JSONObject(
                """{"type":"container","backgroundColor":"rgb(30, 41, 59)",
                   "children":[{"type":"text","data":"a"},{"type":"text","data":"b"}]}""",
            ),
        )

        assertEquals("#1E293B", tree.props.optJSONObject("decoration")?.optString("color"))
        assertEquals("n0", tree.nodeId)
        assertEquals("n1", tree.children[0].nodeId)
        assertEquals("n2", tree.children[1].nodeId)
    }

    @Test
    fun `colors normalize across all accepted shapes`() {
        assertEquals("#112233", JsWidgetTree.normalizeColor("#112233"))
        assertEquals("#AABBCC", JsWidgetTree.normalizeColor("#aabbcc"))
        assertEquals("#11223344", JsWidgetTree.normalizeColor("#11223344"))
        assertEquals("#AABBCCDD", JsWidgetTree.normalizeColor("#aabbccdd"))
        assertEquals("#112233", JsWidgetTree.normalizeColor("#123"))
        assertEquals("#11223344", JsWidgetTree.normalizeColor("#1234"))
        assertEquals("#112233", JsWidgetTree.normalizeColor("rgb(17, 34, 51)"))
        assertEquals("#11223380", JsWidgetTree.normalizeColor("rgba(17, 34, 51, 0.5)"))
        assertEquals("#FF000080", JsWidgetTree.normalizeColor("rgba(255, 0, 0, 128)"))
        assertEquals("#112233", JsWidgetTree.normalizeColor(" rgb(17 34 51) "))
        assertEquals("beige", JsWidgetTree.normalizeColor("beige")) // не цвет — как есть
        assertEquals("#12345", JsWidgetTree.normalizeColor("#12345")) // 5 цифр — как есть
        assertEquals(42, JsWidgetTree.normalizeColor(42)) // не строка
    }

    @Test
    fun `value and dropdown families alias onChanged and type`() {
        val dropdown = JsWidgetTree.normalize(
            JSONObject("""{"type":"select","onChanged":"pick","items":[]}"""),
        )
        assertEquals("dropdown", dropdown.type)
        assertTrue(dropdown.events.has("onChange") || dropdown.events.has("onChanged"))

        val slider = JsWidgetTree.normalize(
            JSONObject("""{"type":"slider","onChanged":"v","value":0.5}"""),
        )
        assertTrue(slider.events.has("onChange") || slider.events.has("onChanged"))
        assertEquals(0.5, slider.props.getDouble("value"), 0.0)
    }

    @Test
    fun `svg and image families alias to url and data`() {
        val img = JsWidgetTree.normalize(
            JSONObject("""{"type":"networkImage","uri":"https://x/y.png"}"""),
        )
        assertEquals("image", img.type)
        assertEquals("https://x/y.png", img.props.optString("url"))

        val svg = JsWidgetTree.normalize(
            JSONObject("""{"type":"svg","markup":"<svg/>"}"""),
        )
        assertEquals("<svg/>", svg.props.optString("data"))
    }
}
