package com.test.jsr

/** Записывающая подмена движка для JVM-тестов: без native-библиотеки. */
class FakeJsEngine : JsEngine {
    data class Eval(val code: String, val sourceName: String)

    /** Все eval в порядке вызова. */
    val evals = mutableListOf<Eval>()

    /** Тексты ошибок по sourceName; отсутствие записи = успех. */
    val errors = mutableMapOf<String, String>()
    var disposed = false

    override fun eval(code: String, sourceName: String): String? {
        evals += Eval(code, sourceName)
        return errors[sourceName]
    }

    override fun dispose() {
        disposed = true
    }
}
