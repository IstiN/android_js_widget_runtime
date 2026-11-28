# android_js_widget_runtime

Native Android renderer for jsr widgets (the contract of
`flutter_js_widget_runtime`) without Flutter: **QuickJS via JNI + a JSON tree
to Android Views**.

Family: contract and test protocol — [js_widget_runtime](https://github.com/IstiN/js_widget_runtime) (`PROTOCOL.md`), engine core — [js_runtime](https://github.com/IstiN/js_runtime), reference — [flutter_js_widget_runtime](https://github.com/IstiN/flutter_js_widget_runtime).

Motivation: on a Tegra 2 (no NEON) the Flutter renderer saturates the CPU
pipeline in Skia — 117 ms in `LayerTree::Paint` alone, 7 fps. The native
renderer on the same device paints a frame in 8–12 ms (p50). This repository
proves that jsr widgets run natively on old hardware.

## Architecture

```
widget.js ──eval──▶ QuickJS (C, JNI) ──sendMessage('__jsr_render', tree)──▶
▶ Kotlin NativeRenderer: JSON → Android Views ──▶ screen
    ▲                                                  │
    └────── nativeCallEvent(actionId) ◀── onClick ─────┘
```

- `app/src/main/cpp/quickjs/` — vendored QuickJS 2024-01-13 (no quickjs-libc).
- `quickjs_jni.c` — bridge: `nativeCreate/nativeEval/nativeCallEvent`; the JS
  global `sendMessage(ch, json)` feeds `JsWidgetRuntime.onJsMessage`.
- `app/src/main/assets/jsr/bootstrap-*.js` — generated from the canonical
  halves of `js_widget_runtime` (`scripts/sync-bootstrap.sh`; CI checks drift).
- `ChannelHandlers.kt` — one handler per protocol channel, registered in a map;
  capability gate through app manifest meta-data (`dev.fa1.jsr.NETWORK`, ...).
- `HostServices.kt` / `AndroidHostServices.kt` — storage, secrets, HTTP,
  assets, process runner behind small interfaces (in-memory defaults for tests).
- `JsWidgetTree.kt` + `Reconciler.kt` — normalization and patch ops
  (`updateProps`, `insertChild`, `removeChild`, `moveChild`, `replaceSubtree`);
  views survive moves, `EditText` keeps text, `ScrollView` keeps offset.
- `NativeRenderer.kt` — applies the patch ops to the View tree; every root
  view carries `setTag(R.id.jsr_node_id, nodeId)`.
- `NodeMappers.kt` — per-category mapper registry (issue #4): 38 node types
  map to real Android views, 8 render a `HostContractView` container with a
  host slot (`webView`, `three`, `scene3d`, `voxel`, `map`, `video`,
  `audio`, `audio_player`), the rest render the reference fallback card with
  the node id tag. See `docs/node-support.json` and `docs/node-semantics.md`.
- `AuiDump.kt` / `InteractionDriver.kt` / `ConformanceRunner.kt` (androidTest)
  — the AUI dump, the tap/input/scroll/swipe/wait/back driver and the fixture
  runner that writes one `result.json` per fixture (plan 1.4 of the family).

## Quality gate (plan 1.7)

```sh
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport
./gradlew -p .conformance/crap4kotlin installDist
.conformance/crap4kotlin/build/install/crap4kotlin/bin/crap4kotlin \
  app/src/main/java --coverage app/build/reports/coverage/test/debug/report.xml \
  --threshold 8.0
```

Coverage comes from the JVM/Robolectric unit tests of the same job; a missing
or empty coverage file fails the gate. Current maximum CRAP: 8.0
(`Reconciler.valueEquals`, 100 % covered).

## Conformance fixtures

Fixtures are copied from the pinned `js_widget_runtime`
(`conformance.lock`, `scripts/fetch-conformance.sh`) into the androidTest
assets by the `conformanceFixtures` task. The `conformance` CI job runs the
three seed fixtures on an emulator; the `conformance-l0` jobs run the L0 set.

Local run (emulator must be booted separately; the CI image runs with TCG —
`ANDROID_I_WANT_MY_TCG=yes` — because GitHub runners have no KVM):

```sh
bash scripts/fetch-conformance.sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.fixtureFilter=hello,counter,scroll_list
adb pull /sdcard/Android/data/com.test.jsr/files/conformance/. \
  app/build/reports/conformance/
node .conformance/js_widget_runtime/harness/harness.mjs compare \
  --fixtures .conformance/js_widget_runtime/docs/conformance/fixtures \
  --results app/build/reports/conformance --out-report conformance-android.md
```

The runner writes `result.json` to the AGP additional-output path and to
`getExternalFilesDir`; on API 24 images the AGP pull is empty, hence the
direct `adb pull`.

## Minimum SDK

`minSdk = 17` — the family device contract (Tegra 2, Android 4.2.2 = API 17),
closed by the port (issue
[#6](https://github.com/IstiN/android_js_widget_runtime/issues/6)). Every
>API 17 API is replaced or `Build.VERSION.SDK_INT`-guarded; lint `NewApi`
reports zero errors at `minSdk 17`:

- Theme: `@style/JsrTheme` = `Theme.Holo.NoActionBar` on API 17-20,
  `Theme.Material.NoActionBar` via `values-v21/` overlay.
- Card shadow: `View.setElevation` (API 21) behind `SDK_INT >= 21` — no
  shadow below 21.
- Secrets: `androidx.security:security-crypto` removed (library `minSdk 21`);
  `PrefSecrets` (AndroidHostServices.kt): API 23+ keys live in
  AndroidKeystore (AES-256-GCM); API 17-22 use a random key in app-private
  prefs with AES/CBC + HMAC-SHA256 (encrypt-then-MAC) — no hardware
  keystore on these levels, root reads both values and key; `SecureRandom`
  is seeded from `/dev/urandom` (Android <= 4.3 PRNG advisory, 2013).
- exec: `Process.waitFor(long, TimeUnit)` / `destroyForcibly()` (API 26)
  replaced by `exitValue()` polling with a budget and `destroy()` (SIGTERM,
  1 s grace) — a SIGTERM-resistant child is not killed below API 26;
  `CompletableFuture` (API 24) replaced by a two-thread pool.
- Native: NDK r27 refuses `ANDROID_PLATFORM < 21` (CXX1110); the QuickJS JNI
  library uses no platform APIs, so the build sets the documented waiver
  `android.ndk.suppressMinSdkVersionError=21` (gradle.properties).

JVM unit tests run at the lowest SDK the toolchain supports: Robolectric
4.18.1 supports SDK 24+ (`DefaultSdkProvider`; `UnknownSdk` rejects 21).
Behavior below 24 (prefs secrets, Holo theme, no card shadow, SIGTERM exec)
is exercised by the instrumentation/conformance runs on the device.

## Measurements on GT P7510 (Tegra 2, no NEON, Android 4.2.2 API 17)

| Metric | Flutter (Skia) | Native jsr |
|---|---|---|
| Tap→JS→re-render cycle | — | **14–24 ms** |
| Frame while scrolling a list (p50) | ~143 ms per frame | **8–12 ms** |
| p90 / p99 | 166 / 249 ms | 28 / 40 ms |

## Build and run

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.test.jsr/.MainActivity
```

Requirements: NDK 27+, CMake 3.22.1, JDK 17+.
