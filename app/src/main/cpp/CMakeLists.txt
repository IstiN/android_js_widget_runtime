cmake_minimum_required(VERSION 3.22.1)
project(jsr LANGUAGES C)

# Vendored QuickJS 2024-01-13 (из ~/git/quickjs_runtime/native/quickjs).
# quickjs-libc НЕ собираем: он тянет stdio/сеть, а наш bootstrap определяет
# свой console/timers. libbf нужен для bignum-части quickjs.c.
set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs)

add_library(quickjs STATIC
    ${QJS_DIR}/quickjs.c
    ${QJS_DIR}/libregexp.c
    ${QJS_DIR}/libunicode.c
    ${QJS_DIR}/cutils.c
    ${QJS_DIR}/libbf.c
)

target_include_directories(quickjs PRIVATE ${QJS_DIR})
add_compile_definitions(CONFIG_VERSION="2024-01-13")
target_compile_options(quickjs PRIVATE
    -O2
    -D_GNU_SOURCE
    -Wno-implicit-const-int-float-conversion
    -Wno-array-bounds
)

# Тонкий JNI-мост: eval + глобальный sendMessage(ch, json) -> Kotlin
add_library(jsr_bridge SHARED quickjs_jni.c)

target_include_directories(jsr_bridge PRIVATE
    ${QJS_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(jsr_bridge quickjs log android)
