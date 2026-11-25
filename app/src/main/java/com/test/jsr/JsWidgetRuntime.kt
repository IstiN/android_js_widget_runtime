package com.test.jsr

/**
 * QuickJS-движок поверх JNI-моста. Имя класса зафиксировано quickjs_jni.c
 * (символы Java_com_test_jsr_JsWidgetRuntime_*), поэтому обёртка JNI живёт
 * здесь, а не в отдельном QuickJsEngine. Таймеры — в Timers (план A1b).
 */
class JsWidgetRuntime : JsEngine {
    private var handle: Long = 0

    /** Точка выхода сообщений из JS; вызывается через onJsMessage из C. */
    var messageSink: ((channel: String, payload: String) -> Unit)? = null

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

    companion object {
        init {
            System.loadLibrary("jsr_bridge")
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDispose(handle: Long)
    private external fun nativeEval(handle: Long, code: String): String?
    private external fun nativeCallEvent(handle: Long, actionId: String, payloadJson: String): String?
}
