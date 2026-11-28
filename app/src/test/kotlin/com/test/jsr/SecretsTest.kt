package com.test.jsr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Секреты на minSdk 17 (issue #6): ветка prefs-ключа (API 17-22) — чистый
 * javax.crypto + android.util.Base64 (настоящая реализация в Robolectric).
 * Robolectric 4.18.1 поддерживает SDK 24+ (DefaultSdkProvider; UnknownSdk
 * отвергает 21), поэтому ветка <23 собирается вручную — это тот класс,
 * который исполняется на устройстве API 17. API-ветку на 24+ выбирает
 * PrefSecrets.of -> KeystoreSecretCipher (см. KeystoreSecretsDegradationTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class SecretsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun stored() = context.getSharedPreferences("jsr_secrets", Context.MODE_PRIVATE)

    private fun ofPrefs(): PrefSecrets = PrefSecrets(
        stored(),
        PrefsSecretCipher(context.getSharedPreferences("jsr_secrets_key", Context.MODE_PRIVATE)))

    @Test
    fun `round-trips and removes on null`() {
        val secrets = ofPrefs()

        assertTrue(secrets.set("token", "\"s3cret\""))
        assertEquals("\"s3cret\"", secrets.get("token"))

        assertTrue(secrets.set("token", null))
        assertNull(secrets.get("token"))
    }

    @Test
    fun `ciphertext persists across instances, keys are reused`() {
        val first = ofPrefs()
        first.set("api", "\"v1\"")

        // Новый экземпляр читает тот же ciphertext: ключи не перегенерируются.
        assertEquals("\"v1\"", ofPrefs().get("api"))

        // Значение в prefs не лежит открытым текстом.
        val raw = stored().getString("api", null)
        assertTrue(raw != null && raw != "\"v1\"" && raw.contains(":"))
    }

    @Test
    fun `tampered MAC reads as absent`() {
        val secrets = ofPrefs()
        secrets.set("k", "\"v\"")
        val parts = requireNotNull(stored().getString("k", null)).split(":")
        val mac = parts[2]
        val flipped = (if (mac[0] == 'A') "B" else "A") + mac.substring(1)
        stored().edit().putString("k", parts[0] + ":" + parts[1] + ":" + flipped).commit()

        assertNull(secrets.get("k"))
    }

    @Test
    fun `malformed stored value reads as absent`() {
        stored().edit().putString("k", "zz:zz:zz").commit()

        assertNull(ofPrefs().get("k"))
    }

    @Test
    fun `prefs cipher survives tampered ciphertext of wrong length`() {
        val secrets = ofPrefs()
        secrets.set("k", "\"v\"")
        val parts = requireNotNull(stored().getString("k", null)).split(":")

        // Обрезанный ciphertext (4 символа base64 — реальные байты данных):
        // MAC и расшифровка не должны уронить get.
        stored().edit().putString("k", "${parts[0]}:${parts[1].dropLast(4)}:${parts[2]}").commit()
        assertNull(secrets.get("k"))
    }
}

/** API 23+ ветка: AndroidKeyStore на JVM нет — сервис деградирует, не падает. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreSecretsDegradationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `without AndroidKeyStore provider set fails and get reads nothing`() {
        val secrets = PrefSecrets.of(context)

        assertFalse(secrets.set("token", "\"s3cret\""))
        assertNull(secrets.get("token"))
    }
}
