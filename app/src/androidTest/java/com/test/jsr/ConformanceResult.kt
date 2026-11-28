package com.test.jsr

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * result.json фикстуры (план 1.4): DONE пишут раннеры, PASS ставит обвязка.
 * Три копии: внутренняя filesDir (пишется всегда; собирается через
 * `adb run-as` — AGP additionalTestOutput pull на api 24 пустой),
 * /sdcard/Android/media/<pkg>/additional_test_output (путь AGP) и
 * getExternalFilesDir. Каждый успех/провал — в logcat (шаг диагностики в CI).
 */
object ConformanceResult {

    private const val TAG = "ConformanceResult"

    fun write(target: Context, fixtureId: String, json: JSONObject) {
        val dirs = listOfNotNull(
            File(target.filesDir, "conformance"),
            File("/sdcard/Android/media/" + target.packageName + "/additional_test_output/conformance"),
            target.getExternalFilesDir(null)?.let { File(it, "conformance") },
            // Локальный прогон на GT-P7510 (ROM multi-user 10 + permissive SELinux):
            // adb не видит внешние каталоги приложения, /data/local/tmp — видит.
            File("/data/local/tmp/conformance"),
        )
        dirs.forEach { dir -> writeQuietly(dir, fixtureId, json) }
    }

    private fun writeQuietly(dir: File, fixtureId: String, json: JSONObject) {
        try {
            dir.mkdirs()
            File(dir, "$fixtureId.result.json").writeText(json.toString(2))
            Log.i(TAG, "result written: $dir/$fixtureId.result.json")
        } catch (e: Exception) {
            Log.w(TAG, "write failed: $dir: $e")
        }
    }
}
