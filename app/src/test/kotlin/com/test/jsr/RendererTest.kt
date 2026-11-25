package com.test.jsr

import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Реальный рендерер: ровно один updateProps на тап счётчика (issue #2),
 * сохранение view при moveChild, replaceSubtree при смене типа,
 * property keeping EditText/ScrollView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RendererTest {
    private lateinit var renderer: NativeRenderer
    private lateinit var container: FrameLayout
    private val actions = mutableListOf<Pair<String, String>>()

    private val render1 = normalize(
        """{"type":"column","spacing":16,"children":[
           {"type":"text","data":"count","style":{"fontSize":20}},
           {"type":"text","data":"0","style":{"fontSize":64}},
           {"type":"row","spacing":12,"children":[
             {"type":"textButton","text":"-1","onTap":"dec"},
             {"type":"textButton","text":"+1","onTap":"inc"}]}]}""",
    )

    private val render2 = normalize(
        """{"type":"column","spacing":16,"children":[
           {"type":"text","data":"count","style":{"fontSize":20}},
           {"type":"text","data":"1","style":{"fontSize":64}},
           {"type":"row","spacing":12,"children":[
             {"type":"textButton","text":"-1","onTap":"dec"},
             {"type":"textButton","text":"+1","onTap":"inc"}]}]}""",
    )

    private fun normalize(s: String): JsNode = JsWidgetTree.normalize(JSONObject(s))

    @Before
    fun setUp() {
        container = FrameLayout(ApplicationProvider.getApplicationContext())
        renderer = NativeRenderer(
            ApplicationProvider.getApplicationContext(),
            { action, payload -> actions.add(action to payload) },
            container,
        )
    }

    @Test
    fun `counter render mounts views tagged with node ids`() {
        renderer.render(render1)

        assertEquals(1, container.childCount)
        val root = container.getChildAt(0)
        assertEquals("n0", root.getTag(R.id.jsr_node_id))
        assertTrue(renderer.viewOf("n1") is TextView)
        assertTrue(renderer.viewOf("n3") is android.widget.LinearLayout)
    }

    @Test
    fun `one counter tap applies exactly one updateProps and keeps the view`() {
        renderer.render(render1)
        val textViewBefore = renderer.viewOf("n2") as TextView

        val ops = renderer.render(render2)

        assertEquals(listOf<PatchOp>(PatchOp.UpdateProps("n2", render2.children[1])), ops)
        assertSame(textViewBefore, renderer.viewOf("n2"))
        assertEquals("1", (renderer.viewOf("n2") as TextView).text.toString())
        assertEquals(0, container.childCount - 1) // пересборки не было
    }

    @Test
    fun `keyed child keeps its view when siblings move`() {
        val old = normalize(
            """{"type":"column","children":[{"type":"text","key":"a","data":"A"},
               {"type":"text","key":"b","data":"B"}]}""",
        )
        val new = normalize(
            """{"type":"column","children":[{"type":"text","key":"b","data":"B"},
               {"type":"text","key":"a","data":"A"}]}""",
        )

        renderer.render(old)
        val viewB = renderer.viewOf("n2") as TextView

        val ops = renderer.render(new)

        assertEquals(2, ops.count { it is PatchOp.MoveChild })
        // View экземпляр b пережил перестановку; текст не потерян.
        val viewsAfter = renderer.allViews().toList()
        assertTrue(viewsAfter.any { it === viewB })
        assertEquals("B", (renderer.viewOf("n1") as TextView).text.toString())
    }

    @Test
    fun `type change at a position replaces the subtree`() {
        val old = normalize("""{"type":"column","children":[{"type":"text","data":"x"}]}""")
        val new = normalize("""{"type":"column","children":[{"type":"textButton","text":"x","onTap":"go"}]}""")

        renderer.render(old)
        renderer.render(new)

        assertTrue(renderer.viewOf("n1") is Button)
        assertEquals("x", (renderer.viewOf("n1") as Button).text.toString())
    }

    @Test
    fun `edit text keeps its text when the related prop does not change`() {
        val withField = normalize(
            """{"type":"column","children":[{"type":"textField","placeholder":"name","value":"Ann","onChanged":"c"}]}""",
        )
        val same = normalize(
            """{"type":"column","children":[{"type":"textField","placeholder":"name","value":"Ann","onChanged":"c"}]}""",
        )
        val renamed = normalize(
            """{"type":"column","children":[{"type":"textField","placeholder":"name","value":"Bob","onChanged":"c"}]}""",
        )

        renderer.render(withField)
        val field = renderer.viewOf("n1") as EditText
        field.setText("Ann")

        renderer.render(same)
        assertEquals("Ann", field.text.toString()) // пустой diff: текст не тронут

        renderer.render(renamed)
        assertEquals("Bob", field.text.toString()) // initialValue изменился
    }

    @Test
    fun `scroll view keeps its offset across an unrelated re-render`() {
        val items = (1..30).joinToString(",") { i ->
            """{"type":"text","data":"Item #$i"}"""
        }
        val tree = normalize("""{"type":"scroll","children":[{"type":"column","children":[$items]}]}""")

        renderer.render(tree)
        val sv = renderer.allViews().filterIsInstance<ScrollView>().single()
        // Layout вручную: без него ScrollView.clamp вернёт 0.
        sv.layout(0, 0, 100, 400)
        sv.getChildAt(0).layout(0, 0, 100, 2000)
        sv.scrollTo(0, 600)

        renderer.render(tree)

        assertEquals(600, sv.scrollY)
    }

    @Test
    fun `fallback card carries the node id tag`() {
        val tree = normalize("""{"type":"column","children":[{"type":"map","center":[0.0,0.0]}]}""")

        renderer.render(tree)

        val card = renderer.viewOf("n1")
        assertTrue(card != null)
        assertEquals("n1", card!!.getTag(R.id.jsr_node_id))
        assertTrue((card as android.view.ViewGroup).getChildAt(0) is TextView)
    }
}
