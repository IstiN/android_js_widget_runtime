package com.test.jsr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcilerTest {
    private fun normalize(s: String): JsNode = JsWidgetTree.normalize(JSONObject(s))

    /** render1/render2 счётчика из примера samples/flutter/counter. */
    private val counterRender1 = normalize(
        """{"type":"center","child":{"type":"column","spacing":16,"children":[
           {"type":"text","data":"QuickJS counter","style":{"fontSize":20,"color":"#94a3b8"}},
           {"type":"text","data":"0","style":{"fontSize":64,"fontWeight":"bold","color":"#f1f5f9"}},
           {"type":"row","spacing":12,"children":[
             {"type":"textButton","text":"-1","onTap":"dec"},
             {"type":"textButton","text":"+1","onTap":"inc"},
             {"type":"textButton","text":"reset","onTap":"reset"}]},
           {"type":"text","data":"footer","style":{"fontSize":12,"color":"#64748b"}}]}}""",
    )

    private val counterRender2 = normalize(
        """{"type":"center","child":{"type":"column","spacing":16,"children":[
           {"type":"text","data":"QuickJS counter","style":{"fontSize":20,"color":"#94a3b8"}},
           {"type":"text","data":"1","style":{"fontSize":64,"fontWeight":"bold","color":"#f1f5f9"}},
           {"type":"row","spacing":12,"children":[
             {"type":"textButton","text":"-1","onTap":"dec"},
             {"type":"textButton","text":"+1","onTap":"inc"},
             {"type":"textButton","text":"reset","onTap":"reset"}]},
           {"type":"text","data":"footer","style":{"fontSize":12,"color":"#64748b"}}]}}""",
    )

    @Test
    fun `one counter tap is exactly one updateProps and nothing else`() {
        val ops = Reconciler.diff(counterRender1, counterRender2).ops

        assertEquals(1, ops.size)
        val op = ops[0] as PatchOp.UpdateProps
        assertEquals("n3", op.nodeId)
        assertEquals("1", op.node.props.optString("data"))
    }

    @Test
    fun `keyed child keeps identity when siblings move`() {
        val old = normalize(
            """{"type":"column","children":[
               {"type":"text","key":"a","data":"A"},{"type":"text","key":"b","data":"B"},
               {"type":"text","key":"c","data":"C"}]}""",
        )
        val new = normalize(
            """{"type":"column","children":[
               {"type":"text","key":"c","data":"C"},{"type":"text","key":"a","data":"A"},
               {"type":"text","key":"b","data":"B"}]}""",
        )

        val ops  = Reconciler.diff(old, new).ops

        assertEquals(0, ops.count { it is PatchOp.InsertChild })
        assertEquals(0, ops.count { it is PatchOp.RemoveChild })
        assertEquals(0, ops.count { it is PatchOp.ReplaceSubtree })
        val moves = ops.filterIsInstance<PatchOp.MoveChild>()
        assertEquals(3, moves.size)
        // Все три keyed-ребёнка сместились: c 2->0, a 0->1, b 1->2.
        assertTrue(moves.any { it.nodeId == "n3" && it.toIndex == 0 })
        assertTrue(moves.any { it.nodeId == "n1" && it.toIndex == 1 })
        assertTrue(moves.any { it.nodeId == "n2" && it.toIndex == 2 })
    }

    @Test
    fun `type change at a position is replaceSubtree`() {
        val old = normalize("""{"type":"column","children":[{"type":"text","data":"x"}]}""")
        val new = normalize("""{"type":"column","children":[{"type":"textButton","text":"x"}]}""")

        val ops  = Reconciler.diff(old, new).ops

        assertEquals(listOf<PatchOp>(PatchOp.ReplaceSubtree("n1", new.children[0])), ops)
    }

    @Test
    fun `insert and remove for unmatched children`() {
        val old = normalize(
            """{"type":"column","children":[{"type":"text","data":"a"},{"type":"text","data":"b"}]}""",
        )
        val new = normalize(
            """{"type":"column","children":[{"type":"text","data":"a"},{"type":"text","data":"b"},
               {"type":"text","data":"c"}]}""",
        )

        val ops  = Reconciler.diff(old, new).ops

        assertEquals(listOf<PatchOp>(PatchOp.InsertChild("n0", 2, new.children[2])), ops)
    }

    @Test
    fun `identical trees produce no ops`() {
        assertTrue(Reconciler.diff(counterRender1, counterRender1).ops.isEmpty())
    }

    @Test
    fun `props differ compares deeply`() {
        val a = JSONObject("""{"decoration":{"color":"#1E293B"}}""")
        val b = JSONObject("""{"decoration":{"color":"#334155"}}""")
        val c = JSONObject("""{"decoration":{"color":"#1E293B"}}""")

        assertTrue(Reconciler.propsDiffer(a, b))
        assertTrue(!Reconciler.propsDiffer(a, c))
        assertTrue(Reconciler.propsDiffer(a, JSONObject("{}")))
    }

    @Test
    fun `array props compare deeply and numbers compare by value`() {
        val same = normalize(
            """{"type":"map","center":[1,2.5],"style":{"stops":[1,2],"pad":10}}""")
        val equal = normalize(
            """{"type":"map","center":[1,2.5],"style":{"stops":[1,2],"pad":10.0}}""")
        val longer = normalize(
            """{"type":"map","center":[1,2.5,3],"style":{"stops":[1,2],"pad":10}}""")
        val changed = normalize(
            """{"type":"map","center":[1,2.5],"style":{"stops":[1,9],"pad":10}}""")

        assertTrue(!Reconciler.propsDiffer(same.props, equal.props)) // числа: 10 == 10.0
        assertTrue(Reconciler.propsDiffer(same.props, longer.props)) // длина массива
        assertTrue(Reconciler.propsDiffer(same.props, changed.props)) // элемент внутри массива
    }
}
