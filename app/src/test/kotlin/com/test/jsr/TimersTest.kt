package com.test.jsr

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Реальные Timers: Handler для интервалов, Choreographer для кадров. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimersTest {
    private val engine = FakeJsEngine()
    private val timers = Timers(engine)
    private val looper = shadowOf(Looper.getMainLooper())

    private fun advance(ms: Long) = looper.idleFor(Duration.ofMillis(ms))

    @Test
    fun `setTimeout fires once through __jsrInterval`() {
        timers.schedule("""{"id":"i1","ms":1000,"once":true}""")

        advance(1000)

        assertEquals(1, engine.evals.size)
        assertTrue(engine.evals[0].code.contains("__jsrInterval(\"i1\");"))
        // Повтора нет: одноразовый таймер удаляется после тика.
        advance(1000)
        assertEquals(1, engine.evals.size)
    }

    @Test
    fun `setInterval repeats until cleared`() {
        timers.schedule("""{"id":"i2","ms":1000}""")
        advance(1000)
        advance(1000)
        assertEquals(2, engine.evals.size)

        timers.cancel("i2")
        advance(5000)
        assertEquals(2, engine.evals.size)
    }

    @Test
    fun `rescheduling an existing id replaces its timer`() {
        timers.schedule("""{"id":"i3","ms":1000,"once":true}""")
        timers.schedule("""{"id":"i3","ms":1000,"once":true}""")
        advance(1000)

        assertEquals(1, engine.evals.size)
    }

    @Test
    fun `raf fires once through __jsrRaf with elapsed`() {
        timers.requestFrame("i4")

        looper.idle()

        assertEquals(1, engine.evals.size)
        assertTrue(engine.evals[0].code.startsWith("__jsrRaf(\"i4\", "))
        looper.idle()
        assertEquals(1, engine.evals.size)
    }

    @Test
    fun `caf cancels the pending frame`() {
        timers.requestFrame("i5")
        timers.cancelFrame("i5")
        looper.idle()

        assertEquals(0, engine.evals.size)
    }

    @Test
    fun `dispose clears timers and frames`() {
        timers.schedule("""{"id":"i6","ms":1000}""")
        timers.requestFrame("i7")
        timers.dispose()
        advance(5000)

        assertEquals(0, engine.evals.size)
    }

    @Test
    fun `capabilities come from the manifest meta-data`() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Тест-приложение заявляет только NETWORK.
        assertEquals(setOf("NETWORK"), AndroidHostServices.declaredCapabilities(app))
    }
}
