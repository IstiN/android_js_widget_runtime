// quickjs_jni.c — тонкий JNI-мост между Kotlin и QuickJS.
//
// Контракт (зеркало flutter_js_widget_runtime):
//   JS:  sendMessage(channel, payloadJson)  ->  Kotlin onJsMessage(ch, json)
//   Kotlin: eval(js) / callEvent(actionId, payloadJson)
//
// Один runtime = один jlong-хэндл (указатель на JsrRuntime). Потоковая
// модель POC: все вызовы идут из UI-потока Android, QuickJS без локов.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "quickjs.h"

#define TAG "jsr_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
    JavaVM *vm;
    jobject callback; // глобальная ссылка на JsWidgetRuntime (для onJsMessage)
    jmethodID sendMid;
} JsrRuntime;

static JavaVM *g_vm = NULL;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// ---- JS-функция sendMessage(channel, payload): прокидывает в Kotlin ----
static JSValue js_sendMessage(JSContext *ctx, JSValueConst this_val,
                              int argc, JSValueConst *argv) {
    (void)this_val;
    JsrRuntime *jr = (JsrRuntime *)JS_GetContextOpaque(ctx);
    LOGI("sendMessage called, argc=%d opaque=%p", argc, (void*)jr);
    if (!jr || argc < 2) return JS_UNDEFINED;
    if (!jr->sendMid) { LOGE("sendMid is NULL!"); return JS_EXCEPTION; }

    const char *channel = JS_ToCString(ctx, argv[0]);
    const char *payload = JS_ToCString(ctx, argv[1]);
    if (!channel || !payload) {
        if (channel) JS_FreeCString(ctx, channel);
        if (payload) JS_FreeCString(ctx, payload);
        return JS_EXCEPTION;
    }

    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK) attached = 1;
    }
    if (env) {
        jstring jch = (*env)->NewStringUTF(env, channel);
        jstring jpl = (*env)->NewStringUTF(env, payload);
        (*env)->CallVoidMethod(env, jr->callback, jr->sendMid, jch, jpl);
        (*env)->DeleteLocalRef(env, jch);
        (*env)->DeleteLocalRef(env, jpl);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);

    JS_FreeCString(ctx, channel);
    JS_FreeCString(ctx, payload);
    return JS_UNDEFINED;
}

// ---- lifecycle ----

JNIEXPORT jlong JNICALL
Java_com_test_jsr_JsWidgetRuntime_nativeCreate(JNIEnv *env, jobject thiz) {
    (void)thiz;
    JsrRuntime *jr = calloc(1, sizeof(JsrRuntime));
    if (!jr) return 0;

    jr->rt = JS_NewRuntime();
    if (!jr->rt) { free(jr); return 0; }
    // Небольшой лимит памяти — POC-санитария против убегающих виджетов.
    JS_SetMemoryLimit(jr->rt, 64 * 1024 * 1024);
    JS_SetMaxStackSize(jr->rt, 512 * 1024);

    jr->ctx = JS_NewContext(jr->rt);
    if (!jr->ctx) {
        JS_FreeRuntime(jr->rt);
        free(jr);
        return 0;
    }
    jr->vm = g_vm;
    jr->callback = (*env)->NewGlobalRef(env, thiz);
    jclass cls = (*env)->GetObjectClass(env, thiz);
    jr->sendMid = (*env)->GetMethodID(env, cls, "onJsMessage",
                                      "(Ljava/lang/String;Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);

    JS_SetContextOpaque(jr->ctx, jr);

    // Глобальный sendMessage(ch, payload) — единственная точка выхода в хост.
    JSValue global = JS_GetGlobalObject(jr->ctx);
    JSValue fn = JS_NewCFunction(jr->ctx, js_sendMessage, "sendMessage", 2);
    JS_SetPropertyStr(jr->ctx, global, "sendMessage", fn);
    JS_FreeValue(jr->ctx, global);

    LOGI("QuickJS created");
    return (jlong)(intptr_t)jr;
}

JNIEXPORT void JNICALL
Java_com_test_jsr_JsWidgetRuntime_nativeDispose(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    JsrRuntime *jr = (JsrRuntime *)(intptr_t)handle;
    if (!jr) return;
    if (jr->ctx) JS_FreeContext(jr->ctx);
    if (jr->rt) JS_FreeRuntime(jr->rt);
    if (jr->callback) (*env)->DeleteGlobalRef(env, jr->callback);
    free(jr);
    LOGI("QuickJS disposed");
}

// Оценивает JS. Возвращает строку ошибки или NULL при успехе.
// Вызывающая сторона (Kotlin) логирует и показывает ошибку на экране.
JNIEXPORT jstring JNICALL
Java_com_test_jsr_JsWidgetRuntime_nativeEval(JNIEnv *env, jobject thiz,
                                             jlong handle, jstring code) {
    (void)thiz;
    JsrRuntime *jr = (JsrRuntime *)(intptr_t)handle;
    if (!jr || !code) return NULL;

    const char *src = (*env)->GetStringUTFChars(env, code, NULL);
    if (!src) return NULL;

    LOGI("nativeEval len=%d", (int)strlen(src));
    JSValue result = JS_Eval(jr->ctx, src, strlen(src), "<widget>",
                             JS_EVAL_TYPE_GLOBAL);
    LOGI("nativeEval done, isException=%d", JS_IsException(result));
    (*env)->ReleaseStringUTFChars(env, code, src);

    if (JS_IsException(result)) {
        JSValue exc = JS_GetException(jr->ctx);
        const char *msg = JS_ToCString(jr->ctx, exc);
        jstring jerr = (*env)->NewStringUTF(env, msg ? msg : "unknown exception");
        if (msg) JS_FreeCString(jr->ctx, msg);
        JS_FreeValue(jr->ctx, exc);
        JS_FreeValue(jr->ctx, result);
        return jerr;
    }
    JS_FreeValue(jr->ctx, result);
    // Прокрутить микротаски (промисы fetch/exec в POC резолвятся сразу).
    { JSContext *pctx = NULL; JS_ExecutePendingJob(jr->rt, &pctx); (void)pctx; }
    return NULL;
}

// Доставка события: вызывает jsr._handler(actionId, payloadJson) —
// точь-в-точь контракт flutter_js_widget_runtime (callEvent).
JNIEXPORT jstring JNICALL
Java_com_test_jsr_JsWidgetRuntime_nativeCallEvent(JNIEnv *env, jobject thiz,
                                                  jlong handle,
                                                  jstring actionId,
                                                  jstring payloadJson) {
    (void)thiz;
    JsrRuntime *jr = (JsrRuntime *)(intptr_t)handle;
    if (!jr) return NULL;

    const char *act = actionId ? (*env)->GetStringUTFChars(env, actionId, NULL) : "\"\"";
    const char *pl = payloadJson ? (*env)->GetStringUTFChars(env, payloadJson, NULL) : "{}";
    if (!act || !pl) return NULL;

    char buf[512];
    snprintf(buf, sizeof(buf),
             "(function(){"
             "var __h=jsr._handler||(typeof handleEvent===\"function\"?handleEvent:null);"
             "if(!__h){return;}"
             "try{"
             "var __r=__h(\"%s\",%s);"
             "if(__r&&typeof __r.then===\"function\"){__r.then(function(){},function(e){});}"
             "}catch(e){}"
             "})();",
             act, pl);

    JSValue result = JS_Eval(jr->ctx, buf, strlen(buf), "<event>",
                             JS_EVAL_TYPE_GLOBAL);
    LOGI("callEvent(%s): exception=%d", act, JS_IsException(result) ? 1 : 0);
    jstring jerr = NULL;
    if (JS_IsException(result)) {
        JSValue exc = JS_GetException(jr->ctx);
        const char *msg = JS_ToCString(jr->ctx, exc);
        jerr = (*env)->NewStringUTF(env, msg ? msg : "event exception");
        if (msg) JS_FreeCString(jr->ctx, msg);
        JS_FreeValue(jr->ctx, exc);
    }
    JS_FreeValue(jr->ctx, result);
    { JSContext *pctx = NULL; JS_ExecutePendingJob(jr->rt, &pctx); (void)pctx; }

    if (actionId) (*env)->ReleaseStringUTFChars(env, actionId, act);
    if (payloadJson) (*env)->ReleaseStringUTFChars(env, payloadJson, pl);
    return jerr;
}
