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
 * ACTION_SCROLL (amendment A2).
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
            onAction = { a, p -> actions.add(a to p) },
            onHostEvent = { t, p -> hostEvents.add(t to p) },
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
}
