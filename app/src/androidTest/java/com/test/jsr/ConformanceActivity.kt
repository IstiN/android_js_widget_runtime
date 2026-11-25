package com.test.jsr

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Пустой хост для рендера фикстур (инструментальные тесты). */
class ConformanceActivity : Activity() {
    lateinit var root: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }
}
