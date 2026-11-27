package com.test.jsr

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
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
 * Mapped views per category (plan 6.1 A3, issue #4): the factory builds the
 * documented view class and configure applies the node page props. Runs under
 * Robolectric only — every tested lambda stays at complexity <= 2 (JaCoCo
 * cannot see this file's executions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapperViewsTest {

    private val actions = mutableListOf<Pair<String, String>>()
    private val sink = NativeRenderer.EventSink { a, p -> actions.add(a to p) }

    @Before
    fun setUp() {
        actions.clear()
    }

    private fun viewOf(type: String, props: String = "{}"): View {
        val factory = NodeMapperRegistry.factories.getValue(type)
        val view = factory(ApplicationProvider.getApplicationContext())
        // JsWidgetTree.normalize moves string on*-props to events; mirror that.
        val raw = JSONObject(props)
        val events = JSONObject()
        for (key in raw.keys().asSequence().toList()) {
            if (key.matches(Regex("on[A-Z].*")) && raw.opt(key) is String) {
                events.put(key, raw.getString(key))
                raw.remove(key)
            }
        }
        val node = JsNode(type, raw, events, emptyList(), "n0")
        NodeMapperRegistry.configure[type]?.invoke(view, node, sink)
        return view
    }

    private fun tap(view: View) = view.performClick()

    // ---------- layout ----------

    @Test
    fun `layout maps center column row stack and sized box`() {
        assertTrue(viewOf("center") is FrameLayout)
        val column = viewOf("column")
        assertTrue(column is LinearLayout)
        assertEquals(LinearLayout.VERTICAL, (column as LinearLayout).orientation)
        val row = viewOf("row")
        assertTrue(row is LinearLayout)
        assertEquals(LinearLayout.HORIZONTAL, (row as LinearLayout).orientation)
        assertTrue(viewOf("stack") is FillLayout)
        val box = viewOf("sizedBox", """{"width":120,"height":40}""")
        assertTrue(box is FillLayout)
        assertEquals(180, box.layoutParams.width) // 120 logical px * 1.5
        assertEquals(60, box.layoutParams.height)
    }

    @Test
    fun `layout applies padding and fill color`() {
        val padded = viewOf("padding", """{"padding":16}""")
        assertEquals(24, padded.paddingLeft)
        val fill = viewOf("fill", """{"color":"#1E293B"}""")
        assertTrue(fill.background != null)
        assertTrue(viewOf("spacer") is View)
    }

    @Test
    fun `layout maps container card and host contract containers`() {
        assertTrue(viewOf("container") is LinearLayout)
        assertTrue(viewOf("card") is LinearLayout)
        val host = viewOf("webView") as HostContractView
        assertTrue(host.slot.childCount == 0)
        assertTrue(viewOf("three") is HostContractView)
        assertTrue(viewOf("map") is HostContractView)
    }

    // ---------- text ----------

    @Test
    fun `text applies data size color and weight`() {
        val tv = viewOf("text", """{"data":"Hi","fontSize":24,"color":"#F1F5F9","fontWeight":"bold"}""") as TextView
        assertEquals("Hi", tv.text.toString())
        assertEquals(36f, tv.textSize)
        assertEquals(android.graphics.Color.parseColor("#F1F5F9"), tv.currentTextColor)
        assertEquals(android.graphics.Typeface.DEFAULT_BOLD, tv.typeface)
    }

    @Test
    fun `input maps text field and area`() {
        val field = viewOf("textField", """{"hint":"H","initialValue":"V"}""") as EditText
        assertEquals("H", field.hint.toString())
        assertEquals("V", field.text.toString())
        val area = viewOf("textArea") as EditText
        assertEquals(3, area.minLines)
    }

    @Test
    fun `badge chip divider and progress apply their props`() {
        val badge = viewOf("badge", """{"label":"L0"}""") as ViewGroup
        assertEquals("L0", (badge.getChildAt(0) as TextView).text.toString())
        val chip = viewOf("chip", """{"label":"chip"}""") as TextView
        assertEquals("chip", chip.text.toString())
        val divider = viewOf("divider", """{"thickness":2}""")
        assertEquals(3, divider.layoutParams.height)
        val circular = viewOf("circularProgressIndicator") as ProgressBar
        assertTrue(circular.isIndeterminate)
        // Robolectric's shadow does not track setProgress/setIndeterminate; the
        // 0..100 value mapping is covered by the pure NodeProps tests
        // (unitProgress, sliderProgress). max IS observable in the shadow.
        val linear = viewOf("linearProgressIndicator", """{"value":0.5}""") as ProgressBar
        assertEquals(100, linear.max)
        val slider = viewOf("slider", """{"min":0,"max":1,"value":0.5}""") as SeekBar
        assertEquals(100, slider.max)
    }

    // ---------- input ----------

    @Test
    fun `button family maps label and tap events`() {
        for (type in listOf("button", "textButton", "outlinedButton", "fab")) {
            val b = viewOf(type, """{"text":"Go","onTap":"act"}""") as Button
            assertEquals("Go", b.text.toString())
            tap(b)
            assertEquals(listOf("act" to "{}"), actions)
            actions.clear()
        }
    }

    @Test
    fun `checkbox switch slider dropdown and radio apply values`() {
        val cb = viewOf("checkbox", """{"value":true,"label":"L0 box"}""") as CheckBox
        assertTrue(cb.isChecked)
        assertEquals("L0 box", cb.text.toString())
        val sw = viewOf("switch", """{"value":true}""") as android.widget.Switch
        assertTrue(sw.isChecked)
        val slider = viewOf("slider", """{"min":0,"max":1,"value":0.5}""") as SeekBar
        assertEquals(50, slider.progress)
        val dropdown = viewOf("dropdown", """{"options":["Alpha","Beta"],"value":"Beta"}""") as android.widget.Spinner
        assertEquals(1, dropdown.selectedItemPosition)
        val radio = viewOf("radio", """{"value":"a","groupValue":"a","label":"L0"}""") as RadioButton
        assertTrue(radio.isChecked)
        assertEquals("L0", radio.text.toString())
    }

    @Test
    fun `gesture wrapper fires the first present event`() {
        val g = viewOf("gestureDetector", """{"onTap":"gd"}""")
        tap(g)
        assertEquals(listOf("gd" to "{}"), actions)
        actions.clear()
        val ink = viewOf("inkWell", """{"onPressed":"iw"}""")
        tap(ink)
        assertEquals(listOf("iw" to "{}"), actions)
    }

    // ---------- review NodeMappers.kt:210: input family из реального взаимодействия ----------

    @Test
    fun `checkbox switch and radio emit change from a real click`() {
        val cb = viewOf("checkbox", """{"value":false,"label":"cb","onChange":"ch"}""") as CheckBox
        cb.performClick()
        assertEquals(listOf("ch" to """{"value":true}"""), actions)
        actions.clear()
        cb.performClick()
        assertEquals(listOf("ch" to """{"value":false}"""), actions)
        actions.clear()

        val sw = viewOf("switch", """{"value":false,"onChange":"sw"}""") as android.widget.Switch
        sw.performClick()
        assertEquals(listOf("sw" to """{"value":true}"""), actions)
        actions.clear()

        val rd = viewOf("radio", """{"value":"a","groupValue":"","label":"L0","onChange":"rd"}""") as RadioButton
        rd.performClick()
        assertEquals(listOf("rd" to """{"value":"a"}"""), actions) // value узла, как в референсе
    }

    @Test
    fun `slider emits the dragged value and stays silent on programmatic set`() {
        val bar = viewOf("slider", """{"min":0,"max":1,"value":0.0,"onChange":"sl"}""") as SeekBar
        bar.layout(0, 0, 300, 40)
        touch(bar, 10f, MotionEvent.ACTION_DOWN)
        touch(bar, 200f, MotionEvent.ACTION_MOVE)
        assertTrue(actions.isNotEmpty())
        assertEquals("sl", actions.last().first)
        assertTrue(JSONObject(actions.last().second).getDouble("value") in 0.0..1.0)

        actions.clear()
        bar.emitSuppressed = true
        bar.progress = 50
        bar.emitSuppressed = false
        assertEquals(0, actions.size)
    }

    @Test
    fun `dropdown emits the picked item through the view listener`() {
        val dd = viewOf("dropdown", """{"options":["Alpha","Beta"],"value":"Alpha","onChange":"dd"}""") as android.widget.Spinner
        dd.onItemSelectedListener!!.onItemSelected(dd, dd, 1, 1)
        assertEquals(listOf("dd" to """{"value":"Beta"}"""), actions)
    }

    @Test
    fun `text field emits onChange on typing and onSubmitted on done`() {
        val field = viewOf("textField", """{"initialValue":"V","onChange":"ch","onSubmit":"sb"}""") as EditText
        field.text.insert(field.text.length, "x") // печатная правка через Editable
        assertEquals(listOf("ch" to """{"value":"Vx"}"""), actions)
        actions.clear()
        field.onEditorAction(EditorInfo.IME_ACTION_DONE) // путь IME done
        assertEquals(listOf("sb" to """{"value":"Vx"}"""), actions)
        actions.clear()

        // Mount/patch-путь (setTextSilently) событие не эмитит.
        setTextSilently(field, "reset")
        assertEquals(0, actions.size)
    }

    @Test
    fun `text area emits onChange on typing`() {
        val area = viewOf("textArea", """{"onChange":"ta"}""") as EditText
        area.text.insert(0, "q")
        assertEquals(listOf("ta" to """{"value":"q"}"""), actions)
    }

    @Test
    fun `input family without change events stays silent`() {
        val cb = viewOf("checkbox", """{"value":false}""") as CheckBox
        cb.performClick()
        assertEquals(0, actions.size)
        val field = viewOf("textField", """{"initialValue":""}""") as EditText
        field.text.insert(0, "q")
        assertEquals(0, actions.size)
        val dd = viewOf("dropdown", """{"options":["Alpha"]}""") as android.widget.Spinner
        assertEquals(null, dd.onItemSelectedListener)
    }

    private fun touch(view: View, x: Float, action: Int) {
        val event = MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, x, 20f, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    // ---------- lists ----------

    @Test
    fun `scroll listView and listTile map their views`() {
        val scroll = viewOf("scroll")
        assertTrue(scroll is ScrollView)
        assertTrue(viewOf("listView") is ScrollView)
        val tile = viewOf("listTile", """{"title":"T","subtitle":"S"}""") as ViewGroup
        assertEquals("T", (tile.getChildAt(0) as TextView).text.toString())
        assertEquals("S", (tile.getChildAt(1) as TextView).text.toString())
    }

    // ---------- material ----------

    @Test
    fun `appBar and banner apply title and message`() {
        val bar = viewOf("appBar", """{"title":"L0 title"}""") as ViewGroup
        assertEquals("L0 title", (bar.getChildAt(0) as TextView).text.toString())
        val banner = viewOf("banner", """{"message":"L0 banner"}""") as ViewGroup
        assertEquals("L0 banner", (banner.getChildAt(0) as TextView).text.toString())
    }

    // ---------- effects and draw ----------

    @Test
    fun `opacity clamps onto view alpha`() {
        assertEquals(0.5f, viewOf("opacity", """{"opacity":0.5}""").alpha)
        assertEquals(1f, viewOf("opacity", """{"opacity":9}""").alpha)
    }

    @Test
    fun `rect and circle size from props`() {
        val rect = viewOf("rect", """{"width":100,"height":60,"radius":8,"fill":"#22C55E"}""")
        assertEquals(150, rect.layoutParams.width)
        assertEquals(90, rect.layoutParams.height)
        assertTrue(rect.background != null)
        val circle = viewOf("circle", """{"size":60}""")
        assertEquals(90, circle.layoutParams.width)
    }
}
