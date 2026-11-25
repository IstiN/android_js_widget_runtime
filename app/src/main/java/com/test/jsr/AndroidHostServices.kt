package com.test.jsr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Android-реализации сервисов (issue #3); тонкие — CC <= 2, см. A1b-отчёт. */
object AndroidHostServices {
    const val CAPABILITY_PREFIX = "dev.fa1.jsr."
    val CAPABILITY_KEYS = listOf("NETWORK", "EXEC")

    fun storage(context: Context): StorageService =
        SharedPreferencesStorage(
            context.getSharedPreferences("jsr_storage", Context.MODE_PRIVATE))

    fun secrets(context: Context): SecretsService = EncryptedSecrets.of(context)

    fun http(): HttpService = HttpURLConnectionHttp()

    fun assets(context: Context): AssetLoaderService = AssetManagerLoader(context.assets)

    fun processRunner(): ProcessRunnerService = ProcessBuilderRunner()

    /**
     * Возможности, заявленные хост-приложением в манифесте
     * (<meta-data android:name="dev.fa1.jsr.NETWORK" android:value="true"/>).
     */
    fun declaredCapabilities(context: Context): Set<String> {
        val meta = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA).metaData
            ?: return emptySet()
        return CAPABILITY_KEYS.filter { meta.getBoolean(CAPABILITY_PREFIX + it, false) }.toSet()
    }
}

class SharedPreferencesStorage(private val prefs: android.content.SharedPreferences) : StorageService {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, valueJson: String?) {
        prefs.edit().apply {
            if (valueJson == null) remove(key) else putString(key, valueJson)
        }.apply()
    }
}

/** EncryptedSharedPreferences (issue #3); value null = удаление. */
class EncryptedSecrets private constructor(private val prefs: android.content.SharedPreferences) : SecretsService {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, valueJson: String?): Boolean {
        prefs.edit().apply {
            if (valueJson == null) remove(key) else putString(key, valueJson)
        }.apply()
        return true
    }

    companion object {
        fun of(context: Context): EncryptedSecrets {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSecrets(EncryptedSharedPreferences.create(
                context, "jsr_secrets", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
        }
    }
}

/** HttpURLConnection на фоновом потоке; ноль новых зависимостей. */
class HttpURLConnectionHttp(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : HttpService {
    override fun fetchJson(url: String, method: String, headers: Map<String, String>, onResult: (HttpResult) -> Unit) {
        executor.execute {
            onResult(fetchViaConnection(url, method, headers) {
                URL(url).openConnection() as HttpURLConnection
            })
        }
    }
}

/**
 * Чистая логика запроса — открывается через [open] для JVM-тестов с
 * подменным HttpURLConnection. Тело разбирается как JSON (референс:
 * vm_default_handlers.dart); сетевые сбои и невалидный JSON дают __error.
 */
internal fun fetchViaConnection(
    url: String,
    method: String,
    headers: Map<String, String>,
    open: () -> HttpURLConnection,
): HttpResult = try {
    val connection = open()
    connection.requestMethod = method.uppercase()
    headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
    val stream = try {
        connection.inputStream
    } catch (e: IOException) {
        connection.errorStream
    }
    val body = stream?.bufferedReader()?.use { it.readText() }
        ?: return HttpResult(error = "fetch $url: empty body")
    JSONObject(body) // валидация как JSON — референс делает jsonDecode
    HttpResult(bodyText = body)
} catch (e: Exception) {
    HttpResult(error = "fetch $url: $e")
}

class AssetManagerLoader(private val assets: android.content.res.AssetManager) : AssetLoaderService {
    override fun load(path: String): String? = try {
        assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        null
    }
}

/** ProcessBuilder без shell-интерполяции: cmd разбивается по пробелам. */
class ProcessBuilderRunner(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : ProcessRunnerService {
    override fun exec(cmd: String, onResult: (ExecResult) -> Unit) {
        executor.execute {
            onResult(runCommand(cmd))
        }
    }
}

internal fun runCommand(cmd: String): ExecResult = try {
    val process = ProcessBuilder(*cmd.split(" ").toTypedArray())
        .redirectErrorStream(false)
        .start()
    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }
    ExecResult(process.waitFor(), stdout, stderr)
} catch (e: Exception) {
    ExecResult(-1, "", e.toString())
}

class ActivityOpenUrl(private val activity: Activity) : OpenUrlService {
    override fun open(url: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: Exception) {
        Log.w(TAG, "openUrl: $e")
        false
    }

    companion object {
        private const val TAG = "ActivityOpenUrl"
    }
}
