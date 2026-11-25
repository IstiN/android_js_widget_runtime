package com.test.jsr

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Приёмники хоста для fire-and-forget каналов (UI-сторона приложения). */
interface HostSinks {
    fun onRender(tree: JSONObject)
    fun onTitle(title: String)
    fun onLog(line: String)
    fun onExportState(state: JSONObject)
}

/**
 * Маршрутизатор каналов __jsr_* (docs/PROTOCOL.md §3): один класс-обработчик
 * на канал, карта вместо цепочки if. Raw-каналы приходят в конверте
 * {iid, id} (шаг __tag) — маршрутизатор разворачивает его до исходной
 * строки. Канал с незаявлённой возможностью отвечает
 * {__error: "<channel> is not permitted by this host"}.
 */
class ChannelHandlers(
    private val engine: JsEngine,
    private val sinks: HostSinks,
    private val services: Services,
    private val timers: TimerDriver,
    private val capabilities: Set<String> = emptySet(),
) {
    /** Одна таблица канал -> возможность (план A1); остальные — без гейта. */
    private val capabilityOf: Map<String, String> = mapOf(
        "__jsr_fetch" to "NETWORK",
        "__jsr_exec" to "EXEC",
    )

    private val handlers: Map<String, ChannelHandler> = mapOf(
        "__jsr_log" to LogHandler(sinks),
        "__jsr_render" to RenderHandler(sinks),
        "__jsr_set_title" to SetTitleHandler(sinks),
        "__jsr_set_interval" to SetIntervalHandler(timers),
        "__jsr_clear_interval" to ClearIntervalHandler(timers),
        "__jsr_raf" to RafHandler(timers),
        "__jsr_caf" to CafHandler(timers),
        "__jsr_fetch" to FetchHandler(services.http, ::resolve),
        "__jsr_exec" to ExecHandler(services.processRunner, ::resolve),
        "__jsr_open_url" to OpenUrlHandler(services.openUrl, ::resolve),
        "__jsr_host_call" to HostCallHandler(services.hostCall, ::resolve),
        "__jsr_host_call_binary" to HostCallBinaryHandler(),
        "__jsr_capture" to CaptureHandler(services.capture, ::resolve),
        "__jsr_storage_get" to StorageGetHandler(services.storage, ::resolve),
        "__jsr_storage_set" to StorageSetHandler(services.storage),
        "__jsr_secrets_get" to SecretsGetHandler(services.secrets, ::resolve),
        "__jsr_secrets_set" to SecretsSetHandler(services.secrets, ::resolve),
        "__jsr_load_asset" to LoadAssetHandler(services.assets, ::resolve),
        "__jsr_export_state" to ExportStateHandler(sinks),
        "__jsr_scene3d_command" to Scene3dCommandHandler(),
        "__jsr_event_done" to EventDoneHandler(),
    )

    fun dispatch(channel: String, payload: String) {
        val handler = handlers[channel]
        if (handler == null) {
            Log.w(TAG, "unknown channel $channel")
            return
        }
        val gate = capabilityOf[channel]
        if (gate != null && gate !in capabilities) {
            resolveError(payload, "$channel is not permitted by this host")
            return
        }
        handler.handle(if (channel == "__jsr_raf") payload else unwrap(payload))
    }

    /**
     * Разворачивает конверт {iid, id} raw-каналов (__tag в bootstrap);
     * JSON-объекты (render, fetch, ...) проходят как есть. __jsr_raf держит
     * свой iid по контракту и не разворачивается. Публичный: JaCoCo
     * записывает internal-функции под синтетическим именем с $, и гейт
     * CRAP их пропускает.
     */
    fun unwrap(payload: String): String = try {
        val obj = JSONObject(payload)
        if (obj.length() == 2 && obj.has("iid") && obj.has("id")) obj.getString("id") else payload
    } catch (e: Exception) {
        payload
    }

    /** __jsrResolve(id, value): промис __cbs[id] разрешается значением. */
    private fun resolve(id: String, valueJson: String) {
        val err = engine.eval("__jsrResolve(${JsStrings.quote(id)}, $valueJson);", "resolve")
        if (err != null) Log.e(TAG, "resolve $id: $err")
    }

    private fun resolveError(payload: String, message: String) {
        val id = try {
            JSONObject(payload).optString("id")
        } catch (e: Exception) {
            ""
        }
        if (id.isNotEmpty()) resolve(id, errorValue(message)) else Log.w(TAG, message)
    }

    private fun logUnsupported(channel: String) {
        Log.w(TAG, "$channel is not supported by this host")
    }

    companion object {
        private const val TAG = "ChannelHandlers"
        internal fun errorValue(message: String): String =
            "{\"__error\":" + JsStrings.quote(message) + "}"

        /** Исходный JSON-текст значения (optString теряет форму: "dark" -> dark). */
        internal fun rawJson(obj: JSONObject, key: String): String? {
            if (obj.isNull(key)) return null
            return when (val v = obj.get(key)) {
                is JSONObject -> v.toString()
                is JSONArray -> v.toString()
                is String -> JsStrings.quote(v)
                else -> v.toString()
            }
        }
    }

    // ---- обработчики: documented payload in, documented payload out ----

    private class LogHandler(private val sinks: HostSinks) : ChannelHandler {
        override fun handle(payload: String) = sinks.onLog(payload)
    }

    private class RenderHandler(private val sinks: HostSinks) : ChannelHandler {
        override fun handle(payload: String) = sinks.onRender(JSONObject(payload))
    }

    private class SetTitleHandler(private val sinks: HostSinks) : ChannelHandler {
        override fun handle(payload: String) = sinks.onTitle(payload)
    }

    private class SetIntervalHandler(private val timers: TimerDriver) : ChannelHandler {
        override fun handle(payload: String) = timers.schedule(payload)
    }

    private class ClearIntervalHandler(private val timers: TimerDriver) : ChannelHandler {
        override fun handle(payload: String) = timers.cancel(payload)
    }

    private class RafHandler(private val timers: TimerDriver) : ChannelHandler {
        override fun handle(payload: String) =
            timers.requestFrame(JSONObject(payload).optString("id"))
    }

    private class CafHandler(private val timers: TimerDriver) : ChannelHandler {
        override fun handle(payload: String) = timers.cancelFrame(payload)
    }

    /** {id,url,method,headers} -> разрешает разобранное тело или __error. */
    private class FetchHandler(
        private val http: HttpService,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            http.fetchJson(
                req.optString("url"),
                req.optString("method", "GET").uppercase(),
                headersOf(req.optJSONObject("headers")),
            ) { result ->
                resolve(req.optString("id"), resolveJson(result))
            }
        }

        private fun headersOf(headers: JSONObject?): Map<String, String> {
            val map = HashMap<String, String>()
            headers?.keys()?.forEach { map[it] = headers.optString(it) }
            return map
        }

        /** Тело разбирается как JSON (референс: jsonDecode); сбой -> __error. */
        private fun resolveJson(result: HttpResult): String = when {
            result.error != null -> errorValue(result.error)
            result.bodyText == null -> errorValue("fetch: empty body")
            else -> try {
                JSONObject(result.bodyText); result.bodyText
            } catch (e: Exception) {
                try {
                    JSONArray(result.bodyText); result.bodyText
                } catch (e2: Exception) {
                    errorValue("fetch: invalid json")
                }
            }
        }
    }

    /** {id,cmd} -> {"exitCode","stdout","stderr"} или __error. */
    private class ExecHandler(
        private val runner: ProcessRunnerService,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            runner.exec(req.optString("cmd")) { result ->
                val value = "{\"exitCode\":${result.exitCode}," +
                    "\"stdout\":" + JsStrings.quote(result.stdout) + "," +
                    "\"stderr\":" + JsStrings.quote(result.stderr) + "}"
                resolve(req.optString("id"), value)
            }
        }
    }

    /** {id,url} -> true | __error; нет сервиса -> "not supported". */
    private class OpenUrlHandler(
        private val openUrl: OpenUrlService?,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            val id = req.optString("id")
            val url = req.optString("url")
            if (openUrl == null) return resolve(id, errorValue("openUrl is not supported by this host"))
            if (url.isEmpty()) return resolve(id, errorValue("openUrl: empty url"))
            resolve(id, if (openUrl.open(url)) "true" else errorValue("openUrl: launch failed"))
        }
    }

    /** {id,name,args} -> результат хоста ({} при null) или __error. */
    private class HostCallHandler(
        private val hostCall: HostCallService?,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            val service = hostCall
                ?: return resolve(req.optString("id"), errorValue("hostCall is not supported by this host"))
            val result = service.call(req.optString("name"), req.optJSONObject("args") ?: JSONObject())
            resolve(req.optString("id"), result ?: "{}")
        }
    }

    /** Lossy-канал "<name> <base64>" без промиса; не поддержан — дроп. */
    private class HostCallBinaryHandler : ChannelHandler {
        override fun handle(payload: String) {
            Log.w(TAG, "__jsr_host_call_binary is not supported by this host")
        }
    }

    /** {id,opts} -> {path,width,height} или __error. */
    private class CaptureHandler(
        private val capture: CaptureService?,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            val service = capture
                ?: return resolve(req.optString("id"), errorValue("capture is not supported by this host"))
            val result = service.capture()
            if (result == null) {
                resolve(req.optString("id"), errorValue("capture: not available"))
                return
            }
            val value = "{\"path\":" + JsStrings.quote(result.path) +
                ",\"width\":${result.width},\"height\":${result.height}}"
            resolve(req.optString("id"), value)
        }
    }

    /** {id,key} -> сохранённое значение или null. */
    private class StorageGetHandler(
        private val storage: StorageService,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            resolve(req.optString("id"), storage.get(req.optString("key")) ?: "null")
        }
    }

    /** {key,value} — без id, ответа нет; значение хранится как JSON-текст. */
    private class StorageSetHandler(private val storage: StorageService) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            storage.set(req.optString("key"), rawJson(req, "value"))
        }
    }

    /** {id,key} -> значение или null. */
    private class SecretsGetHandler(
        private val secrets: SecretsService,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            resolve(req.optString("id"), secrets.get(req.optString("key")) ?: "null")
        }
    }

    /** {id,key,value} -> true/false; value null = удаление. */
    private class SecretsSetHandler(
        private val secrets: SecretsService,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            val ok = secrets.set(req.optString("key"), rawJson(req, "value"))
            resolve(req.optString("id"), ok.toString())
        }
    }

    /** {id,path} -> текст ассета (строкой) или null; никогда не отклоняется. */
    private class LoadAssetHandler(
        private val assets: AssetLoaderService,
        private val resolve: (String, String) -> Unit,
    ) : ChannelHandler {
        override fun handle(payload: String) {
            val req = JSONObject(payload)
            val content = assets.load(req.optString("path"))
            resolve(req.optString("id"), content?.let { JsStrings.quote(it) } ?: "null")
        }
    }

    private class ExportStateHandler(private val sinks: HostSinks) : ChannelHandler {
        override fun handle(payload: String) = sinks.onExportState(JSONObject(payload))
    }

    /** Расширенный канал без хоста: дроп с журналом (fire-and-forget). */
    private class Scene3dCommandHandler : ChannelHandler {
        override fun handle(payload: String) {
            Log.w(TAG, "__jsr_scene3d_command is not supported by this host")
        }
    }

    /** Присылается только Worker-движками; VM-движок его не шлёт. */
    private class EventDoneHandler : ChannelHandler {
        override fun handle(payload: String) {
            Log.d(TAG, "__jsr_event_done: $payload")
        }
    }
}

/** Один обработчик канала: documented payload in, documented payload out. */
private fun interface ChannelHandler {
    fun handle(payload: String)
}
