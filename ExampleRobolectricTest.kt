package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.E2eeCryptoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string and database id from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("W Chat", context.getString(R.string.app_name))
        assertTrue(context.getString(R.string.firestore_database_id).isNotBlank())
    }

    @Test
    fun `e2ee ecdh key exchange encrypts and decrypts across two users`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = E2eeCryptoManager(context)
        val alicePub = crypto.getPublicKeyBase64("alice_ye")
        val bobPub = crypto.getPublicKeyBase64("bob_sa")

        val aliceKey = crypto.deriveDirectKey("alice_ye", bobPub, "chat_1")
        val bobKey = crypto.deriveDirectKey("bob_sa", alicePub, "chat_1")

        val message = "مرحباً من اليمن إلى السعودية 🔒"
        val encrypted = crypto.encrypt(message, aliceKey)
        assertNotEquals(message, encrypted.ciphertextBase64)

        val decrypted = crypto.decrypt(encrypted.ciphertextBase64, encrypted.ivBase64, bobKey)
        assertEquals(message, decrypted)
    }
}
