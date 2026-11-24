# android_js_widget_runtime — POC

Нативный Android-рандер jsr-виджетов (контракт `flutter_js_widget_runtime`)
без Flutter: **QuickJS через JNI + JSON-дерево → Android Views**.

Семья: контракт и протокол тестирования — [js_widget_runtime](https://github.com/IstiN/js_widget_runtime) (PROTOCOL.md), ядро движка — [js_runtime](https://github.com/IstiN/js_runtime), эталон — [flutter_js_widget_runtime](https://github.com/IstiN/flutter_js_widget_runtime). Соседи: [ios](https://github.com/IstiN/ios_js_widget_runtime) / [macos](https://github.com/IstiN/macos_js_widget_runtime) / [react](https://github.com/IstiN/react_js_widget_runtime).

Мотивация: на Tegra 2 (без NEON) Flutter-рендер упирается в CPU-пайплайн
Skia — 117 мс только на LayerTree::Paint, 7 fps. Нативный рендер на том же
устройстве рисует кадр за 8–12 мс (p50). Этот POC доказывает, что jsr-виджеты
можно гонять на старом железе нативно.

## Архитектура

```
widget.js ──eval──▶ QuickJS (C, JNI) ──sendMessage('__jsr_render', tree)──▶
▶ Kotlin NativeRenderer: JSON → Android Views ──▶ экран
    ▲                                                  │
    └────── nativeCallEvent(actionId) ◀── onClick ─────┘
```

- `app/src/main/cpp/quickjs/` — вендоренный QuickJS 2024-01-13
  (из `~/git/quickjs_runtime/native/quickjs`, без quickjs-libc).
- `quickjs_jni.c` — мост: `nativeCreate/nativeEval/nativeCallEvent`,
  глобальная JS-функция `sendMessage(ch, json)` → `JsWidgetRuntime.onJsMessage`.
- `bootstrap` (в MainActivity, POC-версия jsr API): `render`, `setTitle`,
  `onEvent`, `console`, `setTimeout/setInterval`.
- `NativeRenderer.kt` — подмножество нод: `center, column, row, text,
  textButton, button, textField, sizedBox, padding, container, card,
  scroll/listView, divider, spacer`; стили: fontSize/color/fontWeight,
  background/borderRadius, margin/padding/width/height, spacing.

Тестовые виджеты: `assets/hello.js` (README-пример), `counter.js` (полный
цикл тап→JS→перерендер), `scroll_list.js` (30 айтемов, нативный скролл).

## Замеры на GT P7510 (Tegra 2, без NEON, Android 4.2.2 API 17)

| Метрика | Flutter (Skia) | Нативный jsr POC |
|---|---|---|
| Цикл тап→JS→перерендер | — | **14–24 мс** |
| Кадр при скролле списка (p50) | ~143 мс на кадр | **8–12 мс** |
| p90 / p99 | 166 / 249 мс | 28 / 40 мс |

## Ограничения POC (осознанные)

- Каждый `jsr.render` пересобирает дерево целиком (без диффинга) — для
  production нужен reconcile-слой (аналог renderdiff Dart-версии).
- `fetchJson/exec/secrets/rAF` — заглушки (мост ответов по id не подключён).
- Нужен recycle-слой для больших списков (RecyclerView + ViewHolder-маппинг
  на jsr-ноды).
- dp = px×1.5 захардкожено (POC на hdpi-планшете).

## Сборка и запуск

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.test.jsr/.MainActivity
```

Требования: NDK 27+, CMake 3.22.1, JDK 17+.
