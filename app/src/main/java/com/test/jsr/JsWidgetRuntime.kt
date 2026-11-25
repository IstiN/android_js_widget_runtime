package com.test.jsr

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * QuickJS-движок поверх JNI-моста. Имя класса зафиксировано quickjs_jni.c
 * (символы Java_com_test_jsr_JsWidgetRuntime_*), поэтому обёртка JNI живёт
 * здесь, а не в отдельном QuickJsEngine.
 */
class JsWidgetRuntime : JsEngine {
    private var handle: Long = 0

    /** Точка выхода сообщений из JS; вызывается через onJsMessage из C. */
    var messageSink: ((channel: String, payload: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timers = mutableMapOf<String, Runnable>()

    fun start() {
        check(handle == 0L) { "engine already started" }
        handle = nativeCreate()
        check(handle != 0L) { "QuickJS init failed" }
    }

    override fun eval(code: String, sourceName: String): String? {
        check(handle != 0L) { "engine not started" }
        return nativeEval(handle, code)
    }

    /** JNI сливает очередь задач после каждого eval/callEvent — no-op. */
    override fun executePendingJobs() = Unit

    override fun dispose() {
        timers.values.forEach(mainHandler::removeCallbacks)
        timers.clear()
        if (handle != 0L) {
            nativeDispose(handle)
            handle = 0
        }
    }

    /** Доставляет событие в виджет: jsr._handler(actionId, payload). */
    fun callEvent(actionId: String, payloadJson: String = "{}"): String? {
        check(handle != 0L) { "engine not started" }
        return nativeCallEvent(handle, actionId, payloadJson)
    }

    /** Вызывается из JNI (глобальная JS-функция sendMessage). */
    fun onJsMessage(channel: String, payload: String) {
        messageSink?.invoke(channel, payload)
    }

    /**
     * Планирует JS-таймер из канала __jsr_set_interval.
     * payload: {"id":"i1","ms":1000,"once":true}  (once=true -> setTimeout)
     */
    fun scheduleTimer(payload: String, tick: () -> Unit) {
        try {
            val obj = JSONObject(payload)
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
