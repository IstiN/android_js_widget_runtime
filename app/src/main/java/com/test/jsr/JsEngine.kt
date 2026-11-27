package com.test.jsr

/**
 * Адаптер движка (docs/ENGINE_ADAPTER.md пина js_runtime): три операции,
 * которые нужны рантайму виджетов. eval возвращает null при успехе или текст
 * исключения ({ ok, error? }); движки, сливающие очередь задач сами
 * (QuickJS JNI — после каждого eval/callEvent), держат executePendingJobs пустым.
 */
interface JsEngine {
    fun eval(code: String, sourceName: String = "eval"): String?
    fun executePendingJobs() {}
    fun dispose()
}
