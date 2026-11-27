package com.test.jsr

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import org.json.JSONObject

/** Контракт таймеров для маршрутизатора каналов; тестируется через него. */
interface TimerDriver {
    fun schedule(payload: String)
    fun cancel(id: String)
    fun requestFrame(id: String)
    fun cancelFrame(id: String)
    fun dispose()
}

/**
 * Таймеры и кадры: интервалы на Handler, кадры на Choreographer. Тик
 * интервала — eval глобала __jsrInterval(id), кадр — __jsrRaf(id, elapsed)
 * (docs/PROTOCOL.md §6). rAF одноразовый: виджет запрашивает каждый кадр
 * заново, отдельного тикера нет.
 */
class Timers(
    private val engine: JsEngine,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : TimerDriver {
    private val timers = mutableMapOf<String, Runnable>()
    private val frames = mutableMapOf<String, Choreographer.FrameCallback>()

    /** Разбор payload __jsr_set_interval; null — некорректный payload. */
    private fun parseTimer(payload: String): TimerSpec? = try {
        parseTimerPayload(payload)
    } catch (e: Exception) {
        Log.e(TAG, "schedule: $e")
        null
    }

    private fun parseTimerPayload(payload: String): TimerSpec? {
        val obj = JSONObject(payload)
        val id = obj.optString("id")
        if (id.isEmpty()) return null
        return TimerSpec(id, obj.optLong("ms", 0L), obj.optBoolean("once", false))
    }

    private data class TimerSpec(val id: String, val ms: Long, val once: Boolean)

    /** Канал __jsr_set_interval: {"id":"i1","ms":250,"once":true}. */
    override fun schedule(payload: String) {
        val spec = parseTimer(payload) ?: return
        cancel(spec.id)
        val runnable = TimerRunnable(spec)
        synchronized(timers) { timers[spec.id] = runnable }
        mainHandler.postDelayed(runnable, spec.ms)
    }

    private inner class TimerRunnable(private val spec: TimerSpec) : Runnable {
        override fun run() {
            tickInterval(spec.id)
            if (!spec.once) {
                synchronized(timers) { timers[spec.id] = this }
                mainHandler.postDelayed(this, spec.ms)
            } else {
                synchronized(timers) { timers.remove(spec.id) }
            }
        }
    }

    /** Канал __jsr_clear_interval: raw id; неизвестные id игнорируются. */
    override fun cancel(id: String) {
        val old = synchronized(timers) { timers.remove(id) }
        old?.let(mainHandler::removeCallbacks)
    }

    /** Канал __jsr_raf: {"id":"i3","iid":"..."} — один кадр на запрос. */
    @SuppressLint("NewApi")
    override fun requestFrame(id: String) {
        val requestedAt = System.nanoTime() / 1_000_000L
        val callback = Choreographer.FrameCallback { frameTimeNanos ->
            synchronized(frames) { frames.remove(id) }
            val elapsed = frameTimeNanos / 1_000_000L - requestedAt
            eval("__jsrRaf(${JsStrings.quote(id)}, $elapsed);", "raf")
        }
        synchronized(frames) { frames[id] = callback }
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** Канал __jsr_caf: raw id. */
    override fun cancelFrame(id: String) {
        val callback = synchronized(frames) { frames.remove(id) }
        callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
    }

    private fun tickInterval(id: String) {
        eval("__jsrInterval(${JsStrings.quote(id)});", "interval")
    }

    private fun eval(code: String, sourceName: String) {
        val err = engine.eval(code, sourceName)
        if (err != null) Log.e(TAG, "$sourceName: $err")
    }

    override fun dispose() {
        synchronized(timers) {
            timers.values.forEach(mainHandler::removeCallbacks)
            timers.clear()
        }
        cancelFrameAll()
    }

    private fun cancelFrameAll() {
        val all = synchronized(frames) {
            val copy = frames.values.toList()
            frames.clear()
            copy
        }
        all.forEach { Choreographer.getInstance().removeFrameCallback(it) }
    }

    companion object {
        private const val TAG = "Timers"
    }
}
