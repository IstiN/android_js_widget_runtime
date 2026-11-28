package com.test.jsr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.SharedPreferences
import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Android-реализации сервисов (issue #3); тонкие — CC <= 2, см. A1b-отчёт. */
object AndroidHostServices {
    const val CAPABILITY_PREFIX = "dev.fa1.jsr."
    val CAPABILITY_KEYS = listOf("NETWORK", "EXEC")

    fun storage(context: Context): StorageService =
        SharedPreferencesStorage(
            context.getSharedPreferences("jsr_storage", Context.MODE_PRIVATE))

    /**
     * API 23+: ключ в AndroidKeystore (AES-256-GCM). API 17-22: случайный
     * ключ в приватных prefs (AES/CBC + HMAC-SHA256) — AndroidKeystore
     * появляется в API 18, AES в нём только в API 23 (issue #6).
     */
    fun secrets(context: Context): SecretsService = PrefSecrets.of(context)

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

/**
 * Секреты (issue #3, минSDK 17 — issue #6): value null = удаление.
 * Носитель — [SecretCipher]; API-ветку выбирает [of].
 * Ветка prefs-ключа тестируется в SecretsTest; ветка AndroidKeystore не
 * загружается на JVM — её строки N/A в гейте и 0% — известный предел
 * (Device-only). Правило A1b: функции, видимые только Robolectric, держим
 * CC <= 2 (JaCoCo их не видит, CRAP-гейт считает 0%).
 */
class PrefSecrets internal constructor(private val prefs: SharedPreferences, private val cipher: SecretCipher) : SecretsService {
    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return decodeSecret(stored)
    }

    /** Любой сбой расшифровки — отсутствие значения, не падение. */
    private fun decodeSecret(stored: String): String? =
        cipher.unseal(stored)?.let(::String)

    override fun set(key: String, valueJson: String?): Boolean {
        if (valueJson == null) return erase(key)
        return store(key, valueJson)
    }

    private fun erase(key: String): Boolean {
        prefs.edit().remove(key).apply()
        return true
    }

    private fun store(key: String, valueJson: String): Boolean {
        val stored = cipher.seal(valueJson.toByteArray()) ?: return false
        prefs.edit().putString(key, stored).apply()
        return true
    }

    companion object {
        fun of(context: Context): PrefSecrets {
            val prefs = context.getSharedPreferences("jsr_secrets", Context.MODE_PRIVATE)
            val cipher = if (Build.VERSION.SDK_INT >= 23) KeystoreSecretCipher()
            else PrefsSecretCipher(context.getSharedPreferences("jsr_secrets_key", Context.MODE_PRIVATE))
            return PrefSecrets(prefs, cipher)
        }
    }
}

/** Шифр носителя секретов; сбой шифрования = null (не исключение). */
internal interface SecretCipher {
    fun seal(plain: ByteArray): String?
    fun unseal(stored: String): ByteArray?
}

/**
 * API 17-22 (нет Keystore-AES): случайные ключи в отдельном prefs-файле
 * приложения. AES/CBC/PKCS5 + HMAC-SHA256 поверх (iv || ct) — GCM-шифр
 * есть только с API 19. Предел (PR #6): ключ лежит в том же приватном
 * хранилище, что и значения — защита от соседних приложений и adb backup,
 * не от root.
 */
internal class PrefsSecretCipher(keys: SharedPreferences) : SecretCipher {
    private val random = strongRandom()
    private val encKey = SecretKeySpec(randomKey(keys, "enc", 16), "AES")
    private val macKey = SecretKeySpec(randomKey(keys, "mac", 32), "HmacSHA256")

    override fun seal(plain: ByteArray): String? = runCatching {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey, IvParameterSpec(iv))
        val ct = cipher.doFinal(plain)
        B64(iv) + ":" + B64(ct) + ":" + B64(mac0(iv, ct))
    }.getOrNull()

    override fun unseal(stored: String): ByteArray? = runCatching { open(stored) }.getOrNull()

    /** iv:ct:mac; неверная форма или база64 -> null. */
    private fun open(stored: String): ByteArray? {
        val p = stored.split(":")
        if (p.size != 3) return null
        return verified(unB64(p[0]), unB64(p[1]), unB64(p[2]))
    }

    private fun verified(iv: ByteArray, ct: ByteArray, mac: ByteArray): ByteArray? =
        if (MessageDigest.isEqual(mac0(iv, ct), mac)) decrypt(iv, ct) else null

    private fun decrypt(iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, encKey, IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    private fun mac0(iv: ByteArray, ct: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKey)
        return mac.doFinal(iv + ct)
    }

    companion object {
        private fun randomKey(keys: SharedPreferences, alias: String, size: Int): ByteArray {
            val existing = keys.getString(alias, null)
            if (existing != null) return android.util.Base64.decode(existing, android.util.Base64.DEFAULT)
            val key = ByteArray(size).also { strongRandom().nextBytes(it) }
            keys.edit().putString(alias, B64(key)).apply()
            return key
        }
    }
}

/**
 * API 23+: ключ AES-256-GCM в AndroidKeystore (аппаратный бэкенд, где есть).
 * Device-only: JVM-тесты не грузят AndroidKeyStore. Конструируется только
 * из PrefSecrets.of за guard'ом SDK_INT >= 23; @TargetApi глушит NewApi
 * внутри класса (issue #6: ноль NewApi при minSdk 17).
 */
@TargetApi(23)
internal class KeystoreSecretCipher : SecretCipher {
    override fun seal(plain: ByteArray): String? = runCatching {
        val iv = ByteArray(12).also { strongRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        B64(iv) + ":" + B64(cipher.doFinal(plain))
    }.getOrNull()

    override fun unseal(stored: String): ByteArray? = runCatching { open(stored) }.getOrNull()

    private fun open(stored: String): ByteArray? {
        val p = stored.split(":")
        if (p.size != 2) return null
        return decrypt(unB64(p[0]), unB64(p[1]))
    }

    private fun decrypt(iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(PREF_NAME, null) as? SecretKey
        if (existing != null) return existing
        return generate()
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            PREF_NAME,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    companion object {
        private const val PREF_NAME = "jsr_master"
    }
}

/**
 * Android <= 4.3 (наши API 17-18): SecureRandom мог стартовать одинаково
 * в разных процессах (рекомендация Google, 2013 — «Some SecureRandom
 * Thoughts») — подмешиваем /dev/urandom. setSeed дополняет существующий
 * энтропийный пул; сбой чтения оставляет дефолтное поведение.
 */
internal fun strongRandom(): SecureRandom {
    val seed = ByteArray(64)
    runCatching {
        java.io.DataInputStream(java.io.FileInputStream("/dev/urandom")).use { it.readFully(seed) }
    }
    val random = SecureRandom()
    random.setSeed(seed)
    return random
}

internal fun B64(bytes: ByteArray): String =
    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

/** Битая base64 -> IllegalArgumentException; вызывающий ловит runCatching. */
internal fun unB64(text: String): ByteArray =
    android.util.Base64.decode(text, android.util.Base64.DEFAULT)

/** HttpURLConnection на фоновом потоке; ноль новых зависимостей. */
class HttpURLConnectionHttp(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : HttpService {
    override fun fetchJson(url: String, method: String, headers: Map<String, String>, onResult: (HttpResult) -> Unit) {
        executor.execute {
            onResult(fetchViaConnection(url, method, headers, open = {
                URL(url).openConnection() as HttpURLConnection
            }))
        }
    }
}

/**
 * Чистая логика запроса — открывается через [open] для JVM-тестов с
 * подменным HttpURLConnection. Тело разбирается как JSON (референс:
 * vm_default_handlers.dart); сетевые сбои и невалидный JSON дают __error.
 * Таймауты обязательны (review AndroidHostServices.kt:100): значение 0 =
 * бесконечность — один молчащий сервер навсегда занимает single-thread
 * executor и все последующие fetch зависают.
 */
internal const val HTTP_CONNECT_TIMEOUT_MS = 10_000
internal const val HTTP_READ_TIMEOUT_MS = 30_000

internal fun fetchViaConnection(
    url: String,
    method: String,
    headers: Map<String, String>,
    open: () -> HttpURLConnection,
    connectTimeoutMs: Int = HTTP_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = HTTP_READ_TIMEOUT_MS,
): HttpResult = try {
    val connection = open()
    connection.connectTimeout = connectTimeoutMs
    connection.readTimeout = readTimeoutMs
    connection.requestMethod = method.uppercase()
    headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
    val stream = try {
        connection.inputStream
    } catch (e: SocketTimeoutException) {
        // Таймаут — не HTTP-ошибка с телом: текст сбоя обязан дойти до __error.
        throw e
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

/** Верхняя граница внешнего процесса (review AndroidHostServices.kt:100). */
internal const val EXEC_TIMEOUT_SECONDS = 10L

/**
 * API 17: waitFor(long, TimeUnit)/destroyForcibly() — API 26 (issue #6).
 * Опрос exitValue() с бюджетом; потоковые чтения — пул из двух потоков
 * (CompletableFuture.commonPool() — API 24), как и раньше: readText висит
 * вечно, если ребёнок не закрыл pipe.
 */
internal fun runCommand(cmd: String, timeoutSeconds: Long = EXEC_TIMEOUT_SECONDS): ExecResult = try {
    val process = ProcessBuilder(*cmd.split(" ").toTypedArray())
        .redirectErrorStream(false)
        .start()
    val pool = Executors.newFixedThreadPool(2)
    try {
        val stdout = pool.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
        val stderr = pool.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
        val exit = awaitExit(process, timeoutSeconds * 1000)
        if (exit == null) {
            terminate(process)
            ExecResult(-1, "", "exec '$cmd': timed out after ${timeoutSeconds}s (SIGTERM sent)")
        } else {
            ExecResult(exit, stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS))
        }
    } finally {
        pool.shutdownNow()
    }
} catch (e: Exception) {
    ExecResult(-1, "", e.toString())
}

/** Код возврата или null после бюджета (опрос exitValue() — API 1). */
private fun awaitExit(process: Process, budgetMs: Long): Int? {
    val deadline = System.currentTimeMillis() + budgetMs
    while (System.currentTimeMillis() < deadline) {
        try {
            return process.exitValue()
        } catch (_: IllegalThreadStateException) {
            Thread.sleep(50)
        }
    }
    return try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }
}

/**
 * Принудительного убийства до API 26 нет: destroy() шлёт SIGTERM и ждёт
 * до 1 с; упрямый процесс остаётся жить — известный предел (PR #6).
 */
private fun terminate(process: Process) {
    process.destroy()
    repeat(10) {
        try {
            process.exitValue()
            return
        } catch (_: IllegalThreadStateException) {
            Thread.sleep(100)
        }
    }
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
