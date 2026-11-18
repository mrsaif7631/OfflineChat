package com.offlinechat

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** AES-256-GCM. Output = [12-byte IV][ciphertext + 16-byte tag]. */
    fun aesEncrypt(key: SecretKeySpec, plain: ByteArray): ByteArray {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    /** Returns null if the key is wrong or the data was changed. */
    fun aesDecrypt(key: SecretKeySpec, data: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        c.doFinal(data, 12, data.size - 12)
    } catch (e: Exception) {
        null
    }
}

/**
 * Layer 1, the ROOM: the room code is your private "frequency".
 * Everything sent in the room is encrypted with a key made from the code.
 */
class RoomCrypto(roomCode: String) {
    val roomId: String
    private val key: SecretKeySpec

    init {
        val code = roomCode.trim()
        roomId = CryptoUtil.hex(CryptoUtil.sha256("room-id:$code".toByteArray()).copyOf(8))
        val spec = PBEKeySpec(code.toCharArray(), "OfflineChat-v2".toByteArray(), 60_000, 256)
        key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES"
        )
    }

    fun encrypt(plain: ByteArray) = CryptoUtil.aesEncrypt(key, plain)
    fun decrypt(data: ByteArray) = CryptoUtil.aesDecrypt(key, data)
}

/**
 * Layer 2, YOUR IDENTITY: each phone makes its own key pair once and keeps it.
 * Private 1-to-1 messages use ECDH, so only the two people chatting can read them.
 * Other phones that relay the message can't read it, even if they know the room code.
 */
class Identity private constructor(private val keyPair: KeyPair) {
    val publicKeyB64: String = CryptoUtil.b64(keyPair.public.encoded)
    val userId: String = fingerprint(publicKeyB64)
    private val sharedKeys = HashMap<String, SecretKeySpec>()

    fun sharedKey(peerPublicKeyB64: String): SecretKeySpec? {
        sharedKeys[peerPublicKeyB64]?.let { return it }
        return try {
            val peer = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(CryptoUtil.unb64(peerPublicKeyB64)))
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(keyPair.private)
            ka.doPhase(peer, true)
            val key = SecretKeySpec(
                CryptoUtil.sha256("offlinechat-dm-v1".toByteArray() + ka.generateSecret()), "AES"
            )
            sharedKeys[peerPublicKeyB64] = key
            key
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** A user's ID is derived from their public key, so nobody can fake someone else's ID. */
        fun fingerprint(publicKeyB64: String): String = try {
            CryptoUtil.hex(CryptoUtil.sha256(CryptoUtil.unb64(publicKeyB64)).copyOf(8))
        } catch (e: Exception) {
            ""
        }

        fun pretty(id: String) = id.chunked(4).joinToString(" ")

        fun load(ctx: Context): Identity {
            val prefs = ctx.getSharedPreferences("identity", Context.MODE_PRIVATE)
            val pub = prefs.getString("pub", null)
            val priv = prefs.getString("priv", null)
            if (pub != null && priv != null) {
                try {
                    val kf = KeyFactory.getInstance("EC")
                    val kp = KeyPair(
                        kf.generatePublic(X509EncodedKeySpec(CryptoUtil.unb64(pub))),
                        kf.generatePrivate(PKCS8EncodedKeySpec(CryptoUtil.unb64(priv))),
                    )
                    return Identity(kp)
                } catch (_: Exception) { /* fall through and make a new one */ }
            }
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            val kp = gen.generateKeyPair()
            prefs.edit()
                .putString("pub", CryptoUtil.b64(kp.public.encoded))
                .putString("priv", CryptoUtil.b64(kp.private.encoded))
                .apply()
            return Identity(kp)
        }
    }
}
