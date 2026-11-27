package com.test.jsr

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Android-сервисы: SharedPreferences-хранилище, ассеты, снимок view. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidServicesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `shared preferences storage round-trips and removes on null`() {
        val storage = AndroidHostServices.storage(context)

        storage.set("theme", "\"dark\"")
        assertEquals("\"dark\"", storage.get("theme"))

        storage.set("theme", null)
        assertEquals(null, storage.get("theme"))
    }

    @Test
    fun `asset loader reads app assets and nulls missing`() {
        val loader = AndroidHostServices.assets(context)

        val text = loader.load("jsr/bootstrap-widget.js")
        assertNotNull(text)
        assertTrue(text!!.contains("Object.assign(jsr"))
        assertEquals(null, loader.load("no/such/asset.js"))
    }

    @Test
    fun `capture writes a png of the view into the dir`() {
        val view = View(context)
        view.layout(0, 0, 40, 20)
        val dir = File(context.cacheDir, "captures")

        val result = captureView(view, dir)

        assertNotNull(result)
        assertEquals(40, result!!.width)
        assertEquals(20, result.height)
        assertTrue(File(result.path).length() > 0)
    }
}

/** fetchViaConnection с подменным HttpURLConnection — чистый JVM-тест. */
class HttpViaConnectionTest {
    private class FakeConnection(
        private val body: String?,
        private val throwOnInput: Boolean = false,
    ) : HttpURLConnection(URL("http://x/v1")) {
        override fun getInputStream(): InputStream {
            if (throwOnInput) throw IOException("http 500")
            return body!!.byteInputStream()
        }

        override fun getErrorStream(): InputStream? = if (throwOnInput) body?.byteInputStream() else null
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    @Test
    fun `parses json body and uppercases the method`() {
        var connection: FakeConnection? = null
        val result = fetchViaConnection("http://x/v1", "get", mapOf("Accept" to "application/json"), open = {
            FakeConnection("""{"temp":12}""").also { connection = it }
        })

        assertEquals("GET", connection!!.requestMethod)
        assertEquals(HttpResult(bodyText = """{"temp":12}"""), result)
    }

    @Test
    fun `error stream body is used when input stream throws`() {
        val result = fetchViaConnection("http://x/v1", "GET", emptyMap(), open = {
            FakeConnection("""{"err":1}""", throwOnInput = true)
        })

        assertEquals(HttpResult(bodyText = """{"err":1}"""), result)
    }

    @Test
    fun `non-json body resolves an error`() {
        val result = fetchViaConnection("http://x/v1", "GET", emptyMap(), open = {
            FakeConnection("<html>")
        })

        assertTrue(result.error != null && result.bodyText == null)
    }

    @Test
    fun `connection failure is an error`() {
        val result = fetchViaConnection("http://x/v1", "GET", emptyMap(), open = {
            throw java.net.ConnectException("refused")
        })

        assertTrue(result.error!!.contains("refused"))
    }

    @Test
    fun `empty body resolves an error`() {
        val result = fetchViaConnection("http://x/v1", "GET", emptyMap(), open = {
            FakeConnection("")
        })

        assertTrue(result.error != null && result.bodyText == null)
    }

    @Test
    fun `defaults install the connect and read timeouts`() {
        var connection: FakeConnection? = null
        fetchViaConnection("http://x/v1", "GET", emptyMap(), open = {
            FakeConnection("""{}""").also { connection = it }
        })

        assertEquals(10_000, connection!!.connectTimeout)
        assertEquals(30_000, connection!!.readTimeout)
    }

    @Test
    fun `a server that accepts and never answers times out into an error`() {
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
        try {
            val port = server.localPort
            Thread {
                // Принимает TCP и никогда не отвечает (embedded-цель).
                server.accept().use { Thread.sleep(3_000) }
            }.apply { isDaemon = true }.start()
            Thread.sleep(200) // accept успевает

            val result = fetchViaConnection(
                "http://127.0.0.1:$port/x", "GET", emptyMap(),
                connectTimeoutMs = 500, readTimeoutMs = 300,
                open = {
                    URL("http://127.0.0.1:$port/x").openConnection() as HttpURLConnection
                },
            )

            assertTrue(result.error != null && result.bodyText == null)
            assertTrue(result.error!!.contains("timed out")) // SocketTimeoutException, не вечное ожидание
        } finally {
            server.close()
        }
    }
}

/** ProcessBuilderRunner на хостовой JVM. */
class ProcessRunnerTest {
    @Test
    fun `runCommand returns exit code and stdout`() {
        val result = runCommand("echo jsr-ok")

        assertEquals(0, result.exitCode)
        assertEquals("jsr-ok\n", result.stdout)
    }

    @Test
    fun `missing command reports failure without throwing`() {
        val result = runCommand("no-such-binary-xyz")

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.isNotEmpty())
    }

    @Test
    fun `a hung process is destroyed after the bounded wait`() {
        val start = System.currentTimeMillis()

        val result = runCommand("sleep 30", timeoutSeconds = 1)
        val elapsed = System.currentTimeMillis() - start

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("timed out"))
        assertTrue(elapsed < 15_000) // exec-очередь не заклинило
    }
}
