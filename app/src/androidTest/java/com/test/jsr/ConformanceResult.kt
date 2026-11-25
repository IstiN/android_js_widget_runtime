package com.test.jsr

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * result.json фикстуры (план 1.4): DONE пишут раннеры, PASS ставит обвязка.
 * Дополнительный вывод AGP: /sdcard/Android/media/<pkg>/additional_test_output
 * (каталог additional_test_output на устройстве, AGP >= 7); дублируем в
 * getExternalFilesDir на случай образов без media-каталога.
 */
object ConformanceResult {

    fun write(target: Context, fixtureId: String, json: JSONObject) {
        val dirs = listOfNotNull(
            File("/sdcard/Android/media/" + target.packageName + "/additional_test_output/conformance"),
            target.getExternalFilesDir(null)?.let { File(it, "conformance") },
        )
        dirs.forEach { dir -> writeQuietly(dir, fixtureId, json) }
    }

    private fun writeQuietly(dir: File, fixtureId: String, json: JSONObject) {
        try {
            dir.mkdirs()
            File(dir, "$fixtureId.result.json").writeText(json.toString(2))
        } catch (e: Exception) {
            android.util.Log.w("ConformanceResult", "write failed: $dir: $e")
        }
    }
}
