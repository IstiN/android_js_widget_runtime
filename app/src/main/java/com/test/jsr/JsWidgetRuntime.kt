package com.test.jsr

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Обёртка над QuickJS через JNI. Контракт зеркалит flutter_js_widget_runtime:
 * JS -> хост через sendMessage(channel, payloadJson),
 * хост -> JS через jsr._handler(actionId, payload).
 */
class JsWidgetRuntime(private val listener: Listener) {
    interface Listener {
        fun onJsMessage(channel: String, payload: String)
    }

    private var handle: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timers = mutableMapOf<String, Runnable>()

    /** Каналы таймеров из bootstrap: __jsr_set_interval / __jsr_clear_interval */
    var timerHandler: ((channel: String, payload: String) -> Unit)? = null

    fun start() {
        handle = nativeCreate()
        check(handle != 0L) { "QuickJS init failed" }
    }

    fun dispose() {
        timers.values.forEach(mainHandler::removeCallbacks)
        timers.clear()
        if (handle != 0L) {
            nativeDispose(handle)
            handle = 0
        }
    }

    /** Вызывается из JNI (глобальная JS-функция sendMessage). */
    fun onJsMessage(channel: String, payload: String) {
        when (channel) {
            "__jsr_set_interval", "__jsr_clear_interval" ->
                timerHandler?.invoke(channel, payload)
            else -> listener.onJsMessage(channel, payload)
        }
    }

    /** Оценивает JS-код; возвращает текст ошибки или null. */
    fun eval(code: String): String? = nativeEval(handle, code)

    /** Доставляет событие в виджет: jsr._handler(actionId, payload). */
    fun callEvent(actionId: String, payloadJson: String = "{}"): String? =
        nativeCallEvent(handle, actionId, payloadJson)

    /**
     * Планирует JS-таймер из канала __jsr_set_interval.
     * payload: {"id":"i1","ms":1000,"once":true}  (once=true -> setTimeout)
     */
    fun scheduleTimer(payload: String, tick: () -> Unit) {
        try {
            val obj = org.json.JSONObject(payload)
            val id = obj.optString("id")
            val ms = obj.optLong("ms", 0L)
            val once = obj.optBoolean("once", false)
            val old = timers.remove(id)
            old?.let(mainHandler::removeCallbacks)
            val runnable = object : Runnable {
                override fun run() {
                    tick()
                    if (!once) {
                        timers[id] = this
                        mainHandler.postDelayed(this, ms)
                    } else {
                        timers.remove(id)
                    }
                }
            }
            timers[id] = runnable
            mainHandler.postDelayed(runnable, ms)
        } catch (e: Exception) {
            Log.e(TAG, "scheduleTimer: $e")
        }
    }

    fun cancelTimer(id: String) {
        timers.remove(id)?.let(mainHandler::removeCallbacks)
    }

    companion object {
        private const val TAG = "JsWidgetRuntime"

        init {
            System.loadLibrary("jsr_bridge")
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDispose(handle: Long)
    private external fun nativeEval(handle: Long, code: String): String?
    private external fun nativeCallEvent(handle: Long, actionId: String, payloadJson: String): String?
}
