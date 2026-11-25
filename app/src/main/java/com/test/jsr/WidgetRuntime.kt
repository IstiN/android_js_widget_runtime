package com.test.jsr

/**
 * Загружает виджет поверх любого JsEngine и маршрутизирует сообщения
 * sendMessage(channel, payload) в слушателя хоста. Порядок загрузки —
 * контракт (план 1.2): var __IID, bootstrap-runtime.js, bootstrap-widget.js,
 * код виджета.
 */
class WidgetRuntime(private val engine: JsEngine, val listener: Listener) {
    interface Listener {
        fun onJsMessage(channel: String, payload: String)
    }

    /**
     * Оценивает bootstrap в порядке контракта; возвращает первую ошибку
     * (текст исключения) или null.
     */
    fun boot(iid: String, runtimeJs: String, widgetJs: String, widgetCode: String): String? {
        engine.eval("var __IID = ${jsString(iid)};", "__iid")?.let { return it }
        engine.eval(runtimeJs, "bootstrap-runtime.js")?.let { return it }
        engine.eval(widgetJs, "bootstrap-widget.js")?.let { return it }
        engine.eval(widgetCode, "widget.js")?.let { return it }
        return null
    }

    /** Единственный вход сообщений от движка. */
    fun onJsMessage(channel: String, payload: String) {
        listener.onJsMessage(channel, payload)
    }

    fun dispose() = engine.dispose()

    private fun jsString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
