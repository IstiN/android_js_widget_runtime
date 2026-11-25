package com.test.jsr

/**
 * Загружает виджет поверх любого JsEngine и передаёт сообщения
 * sendMessage(channel, payload) маршрутизатору каналов. Порядок загрузки —
 * контракт (план 1.2): var __IID, bootstrap-runtime.js, bootstrap-widget.js,
 * код виджета.
 */
class WidgetRuntime(
    private val engine: JsEngine,
    private val sink: (channel: String, payload: String) -> Unit,
) {
    /**
     * Оценивает bootstrap в порядке контракта; возвращает первую ошибку
     * (текст исключения) или null.
     */
    fun boot(iid: String, runtimeJs: String, widgetJs: String, widgetCode: String): String? {
        engine.eval("var __IID = ${JsStrings.quote(iid)};", "__iid")?.let { return it }
        engine.eval(runtimeJs, "bootstrap-runtime.js")?.let { return it }
        engine.eval(widgetJs, "bootstrap-widget.js")?.let { return it }
        engine.eval(widgetCode, "widget.js")?.let { return it }
        return null
    }

    /** Единственный вход сообщений от движка. */
    fun onJsMessage(channel: String, payload: String) = sink(channel, payload)

    fun dispose() = engine.dispose()
}
