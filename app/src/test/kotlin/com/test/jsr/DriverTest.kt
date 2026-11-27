package com.test.jsr

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Действия драйвера производят документированные события (план 1.4 §3.3):
 * тап — node.payload, input — {value} c onChange ?? onChanged, submit —
 * onSubmit ?? onSubmitted, back — __jsrHostEvent('back', {}), scroll —
 * ACTION_SCROLL (amendment A2). Неприменимое действие возвращает причину
 * (никаких тихих no-op) и не диспатчит событие.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverTest {
    private lateinit var driver: InteractionDriver
    private val actions = mutableListOf<Pair<String, String>>()
    private val hostEvents = mutableListOf<Pair<String, String>>()
    private val views = HashMap<String, View>()
    private var root: JsNode? = null

    @Before
    fun setUp() {
        actions.clear()
        hostEvents.clear()
        views.clear()
        root = null
        driver = InteractionDriver(
            views = { views[it] },
            allViews = { views.values },
            root = { root },
            onAction = { a, p -> actions.add(a to p); null },
            onHostEvent = { t, p -> hostEvents.add(t to p); null },
        )
    }

    private fun mount(payload: String): JsNode {
        val tree = JsWidgetTree.normalize(JSONObject(payload))
        root = tree
        return tree
    }

    @Test
    fun `tap by node id dispatches onTap with node payload`() {
        val tree = mount(
            """{"type":"column","children":[
               {"type":"textButton","text":"Tap me","onTap":"tap","payload":{"origin":"ui"}}]}""",
        )
        views[tree.nodeId] = android.widget.LinearLayout(ApplicationProvider.getApplicationContext())
        views["n1"] = Button(ApplicationProvider.getApplicationContext())

        driver.tap("n1")

        assertEquals(listOf("tap" to """{"origin":"ui"}"""), actions)
    }

    @Test
    fun `tap by visible text and payload defaults to empty object`() {
        val tree = mount(
            """{"type":"column","children":[{"type":"textButton","text":"Tap me","onTap":"tap"},
               {"type":"textButton","text":"Reset","onPressed":"reset"}]}""",
        )
        views["n1"] = Button(ApplicationProvider.getApplicationContext())
        views["n2"] = Button(ApplicationProvider.getApplicationContext())

        driver.tap("Reset")

        assertEquals(listOf("reset" to "{}"), actions)
    }

    @Test
    fun `tap prefers onTap over onPress`() {
        val tree = mount(
            """{"type":"column","children":[{"type":"listTile","title":"Row","onTap":"t1","onPress":"p1"}]}""",
        )
        views["n1"] = android.widget.LinearLayout(ApplicationProvider.getApplicationContext())

        driver.tap("Row")

        assertEquals(listOf("t1" to "{}"), actions)
    }

    @Test
    fun `input sets text and dispatches value payload`() {
        val tree = mount(
            """{"type":"column","children":[{"type":"textField","placeholder":"name","onChanged":"c"}]}""",
        )
        val field = EditText(ApplicationProvider.getApplicationContext())
        views["n1"] = field

        driver.input("n1", "hello")

        assertEquals("hello", field.text.toString())
        assertEquals(listOf("c" to """{"value":"hello"}"""), actions)
    }

    @Test
    fun `submit dispatches onSubmitted with the field text`() {
        val tree = mount(
            """{"type":"column","children":[{"type":"textField","value":"query","onSubmitted":"s"}]}""",
        )
        val field = EditText(ApplicationProvider.getApplicationContext())
        field.setText("query")
        views["n1"] = field

        driver.submit("n1")

        assertEquals(listOf("s" to """{"value":"query"}"""), actions)
    }

    @Test
    fun `back sends the host event of the reference`() {
        driver.back()

        assertEquals(listOf("back" to "{}"), hostEvents)
    }

    // ---------- никаких тихих no-op: каждая причина видна ----------

    @Test
    fun `tap by missing text reports the reason`() {
        mount("""{"type":"column","children":[]}""")

        val error = driver.tap("+1")

        assertEquals("tap: no view with text \"+1\"", error)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `tap by missing node id reports the reason`() {
        mount("""{"type":"column","children":[]}""")

        val error = driver.tap("n7")

        assertEquals("tap: no view with node id n7", error)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `tap on a node without tap event reports the reason`() {
        val tree = mount(
            """{"type":"column","children":[{"type":"text","data":"Label"}]}""",
        )
        views[tree.nodeId] = android.widget.LinearLayout(ApplicationProvider.getApplicationContext())

        val error = driver.tap("Label")

        assertEquals("tap: no tap event on node n1 (Label)", error)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `input on a non editable view reports the reason`() {
        val tree = mount(
            """{"type":"column","children":[{"type":"text","data":"Not an input","onChange":"c"}]}""",
        )
        views["n1"] = android.widget.TextView(ApplicationProvider.getApplicationContext())

        val error = driver.input("n1", "hello")

        assertEquals("input: view is not an EditText", error)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `input on a missing node reports the reason`() {
        mount("""{"type":"column","children":[]}""")

        assertEquals("input: no view with node id n3", driver.input("n3", "x"))
    }

    @Test
    fun `input on an editable node without change event reports the reason`() {
        mount("""{"type":"column","children":[{"type":"textField"}]}""")
        views["n1"] = EditText(ApplicationProvider.getApplicationContext())

        assertEquals("input: no change event on node n1", driver.input("n1", "x"))
    }

    @Test
    fun `submit on a missing node reports the reason`() {
        mount("""{"type":"column","children":[]}""")

        assertEquals("submit: no view with node id n1", driver.submit("n1"))
    }

    @Test
    fun `scroll without a target reports the reason`() {
        mount("""{"type":"column","children":[]}""")

        assertEquals("scroll: no scroll target", driver.scroll(300))
    }

    @Test
    fun `swipe without a target reports the reason`() {
        mount("""{"type":"column","children":[]}""")

        assertEquals("swipe: no scroll target", driver.swipe(0, 0, 0, 10, 100))
    }

    @Test
    fun `back propagates the engine error of the host event`() {
        val failing = InteractionDriver(
            views = { null },
            allViews = { emptyList() },
            root = { null },
            onAction = { _, _ -> null },
            onHostEvent = { _, _ -> "ReferenceError: no global" },
        )

        assertEquals("ReferenceError: no global", failing.back())
    }

    @Test
    fun `scroll dispatches an ACTION_SCROLL generic motion event`() {
        val tree = mount("""{"type":"scroll","children":[{"type":"column","children":[]}]}""")
        val received = mutableListOf<MotionEvent>()
        val sv = object : ScrollView(ApplicationProvider.getApplicationContext()) {
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                received.add(event)
                println("GENERIC MOTION RECEIVED action=" + event.action)
                return true
            }
        }
        sv.addView(android.widget.LinearLayout(ApplicationProvider.getApplicationContext()))
        views[tree.nodeId] = sv

        driver.scroll(600)

        assertEquals(1, received.size)
        assertEquals(MotionEvent.ACTION_SCROLL, received[0].action)
        assertTrue(received[0].getAxisValue(MotionEvent.AXIS_VSCROLL) < 0)
    }

    @Test
    fun `swipe dispatches a touch drag on the scroll target`() {
        val tree = mount("""{"type":"scroll","children":[{"type":"column","children":[]}]}""")
        val received = mutableListOf<Int>()
        val sv = object : ScrollView(ApplicationProvider.getApplicationContext()) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                received.add(event.action)
                return true
            }
        }
        sv.addView(android.widget.LinearLayout(ApplicationProvider.getApplicationContext()))
        views[tree.nodeId] = sv

        driver.swipe(50, 500, 50, 100, 300)

        assertEquals(MotionEvent.ACTION_DOWN, received.first())
        assertEquals(MotionEvent.ACTION_UP, received.last())
        assertTrue(received.count { it == MotionEvent.ACTION_MOVE } >= 8)
    }

    @Test
    fun `swipe converts logical coordinates to device px by density`() {
        val tree = mount("""{"type":"scroll","children":[{"type":"column","children":[]}]}""")
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        val sv = object : ScrollView(ApplicationProvider.getApplicationContext()) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                xs.add(event.x)
                ys.add(event.y)
                return true
            }
        }
        sv.addView(android.widget.LinearLayout(ApplicationProvider.getApplicationContext()))
        views[tree.nodeId] = sv
        val scaled = InteractionDriver(
            views = { views[it] },
            allViews = { views.values },
            root = { root },
            onAction = { a, p -> actions.add(a to p); null },
            onHostEvent = { t, p -> hostEvents.add(t to p); null },
            density = 2f,
        )

        scaled.swipe(10, 90, 30, 40, 100)

        // Логические px фикстуры x density: x 10->30 лог. = 20->60 device, y 90->40 = 180->80.
        assertEquals(20f, xs.first(), 1e-3f)
        assertEquals(180f, ys.first(), 1e-3f)
        assertEquals(60f, xs.last(), 1e-3f)
        assertEquals(80f, ys.last(), 1e-3f)
    }
}
