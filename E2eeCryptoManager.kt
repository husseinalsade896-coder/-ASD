package com.example.core.crypto

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val ciphertextBase64: String,
    val ivBase64: String,
)

class E2eeCryptoManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun getOrCreateKeyPair(uid: String): KeyPair {
        val pubKeyB64 = prefs.getString("pub_$uid", null)
        val privKeyB64 = prefs.getString("priv_$uid", null)
        if (!pubKeyB64.isNullOrBlank() && !privKeyB64.isNullOrBlank()) {
            return runCatching {
                val kf = KeyFactory.getInstance("EC")
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubKeyB64, Base64.NO_WRAP)))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privKeyB64, Base64.NO_WRAP)))
                KeyPair(pub, priv)
            }.getOrElse { generateAndPersistKeyPair(uid) }
        }
        return generateAndPersistKeyPair(uid)
    }

    private fun generateAndPersistKeyPair(uid: String): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val kp = kpg.generateKeyPair()
        val pubB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        prefs.edit()
            .putString("pub_$uid", pubB64)
            .putString("priv_$uid", privB64)
            .apply()
        return kp
    }

    fun getPublicKeyBase64(uid: String): String {
        val kp = getOrCreateKeyPair(uid)
        return Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
    }

    fun getFingerprint(publicKeyBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBase64.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString(":") { "%02X".format(it) }
    }

    fun deriveDirectKey(myUid: String, peerPublicKeyBase64: String, chatId: String): SecretKey {
        return runCatching {
            val myPriv: PrivateKey = getOrCreateKeyPair(myUid).private
            val peerPubBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
            val peerPub: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPubBytes))
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(myPriv)
            ka.doPhase(peerPub, true)
            val sharedSecret = ka.generateSecret()
            val combined = sharedSecret + chatId.toByteArray(Charsets.UTF_8)
            val aesBytes = MessageDigest.getInstance("SHA-256").digest(combined)
            SecretKeySpec(aesBytes, "AES")
        }.getOrElse {
            deriveRoomFallbackKey(chatId)
        }
    }

    fun deriveGroupKey(chatId: String, memberPublicKeys: List<String>): SecretKey {
        val material = (listOf(chatId) + memberPublicKeys.sorted()).joinToString("|")
        val aesBytes = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(aesBytes, "AES")
    }

    private fun deriveRoomFallbackKey(chatId: String): SecretKey {
        val aesBytes = MessageDigest.getInstance("SHA-256").digest("wchat_e2ee_$chatId".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(aesBytes, "AES")
    }

    fun encrypt(plaintext: String, key: SecretKey): EncryptedPayload {
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertextBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
        )
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String, key: SecretKey): String {
        if (ivBase64.isBlank()) return ciphertextBase64
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(Base64.decode(ciphertextBase64, Base64.NO_WRAP))
            String(plainBytes, Charsets.UTF_8)
        }.getOrElse {
            // Fallback for cross-session display if key rotated
            "🔒 [Encrypted message]"
        }
    }

    fun hashTwoStepPin(pin: String, uid: String): String {
        val salted = "wchat_2fa_${uid}_${pin.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(salted.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS_NAME = "wchat_e2ee_keystore"
    }
}
