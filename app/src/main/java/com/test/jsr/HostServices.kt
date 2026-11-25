package com.test.jsr

import org.json.JSONObject

/** Экранирование строки в JSON-литерал (для eval глобалов и payload). */
object JsStrings {
    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}

/** Хранилище: значения — JSON-текст; null — отсутствие ключа. */
interface StorageService {
    fun get(key: String): String?
    fun set(key: String, valueJson: String?)
}

class InMemoryStorage : StorageService {
    private val map = HashMap<String, String?>()
    override fun get(key: String): String? = synchronized(map) { map[key] }
    override fun set(key: String, valueJson: String?): Unit = synchronized(map) {
        if (valueJson == null) map.remove(key) else map[key] = valueJson
        Unit
    }
}

/** Секреты: get возвращает значение или null; set сообщает успех. */
interface SecretsService {
    fun get(key: String): String?
    fun set(key: String, valueJson: String?): Boolean
}

class InMemorySecrets : SecretsService {
    private val map = HashMap<String, String?>()
    override fun get(key: String): String? = synchronized(map) { map[key] }
    override fun set(key: String, valueJson: String?): Boolean = synchronized(map) {
        if (valueJson == null) map.remove(key) != null else {
            map[key] = valueJson; true
        }
    }
}

/** Результат HTTP: тело текстом; error != null означает сетевой сбой. */
data class HttpResult(val bodyText: String? = null, val error: String? = null)

/** HTTP: вызов может быть асинхронным; результат приходит в onResult. */
fun interface HttpService {
    fun fetchJson(url: String, method: String, headers: Map<String, String>, onResult: (HttpResult) -> Unit)
}

class FakeHttpService : HttpService {
    data class Request(val url: String, val method: String, val headers: Map<String, String>)
    val requests = mutableListOf<Request>()
    var next = HttpResult(bodyText = "{}")

    override fun fetchJson(url: String, method: String, headers: Map<String, String>, onResult: (HttpResult) -> Unit) {
        requests += Request(url, method, headers)
        onResult(next)
    }
}

/** Загрузчик ассетов: текст файла или null. */
fun interface AssetLoaderService {
    fun load(path: String): String?
}

class FakeAssetLoader(private val files: MutableMap<String, String> = HashMap()) : AssetLoaderService {
    override fun load(path: String): String? = files[path]
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Исполнение процессов: асинхронно, результат в onResult. */
fun interface ProcessRunnerService {
    fun exec(cmd: String, onResult: (ExecResult) -> Unit)
}

class FakeProcessRunner(var next: ExecResult = ExecResult(0, "", "")) : ProcessRunnerService {
    val commands = mutableListOf<String>()
    override fun exec(cmd: String, onResult: (ExecResult) -> Unit) {
        commands += cmd
        onResult(next)
    }
}

/** Точка расширения хоста: jsr.hostCall; результат — JSON-текст или null. */
fun interface HostCallService {
    fun call(name: String, args: JSONObject): String?
}

/** Открытие внешних ссылок; true — запуск принят. */
fun interface OpenUrlService {
    fun open(url: String): Boolean
}

data class CaptureResult(val path: String, val width: Int, val height: Int)

/** Снимок экрана виджета: {path, width, height} или null. */
fun interface CaptureService {
    fun capture(): CaptureResult?
}

/** Совокупность сервисов хоста; null — сервис не поддержан этим хостом. */
data class Services(
    val storage: StorageService,
    val secrets: SecretsService,
    val http: HttpService,
    val assets: AssetLoaderService,
    val processRunner: ProcessRunnerService,
    val hostCall: HostCallService? = null,
    val openUrl: OpenUrlService? = null,
    val capture: CaptureService? = null,
)
