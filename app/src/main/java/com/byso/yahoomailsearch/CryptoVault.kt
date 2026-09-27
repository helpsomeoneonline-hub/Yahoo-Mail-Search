package com.byso.yahoomailsearch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoVault {
    private val random = SecureRandom()

    fun randomSalt(size: Int = 16): ByteArray = ByteArray(size).also(random::nextBytes)

    fun deriveKey(
        passphrase: String,
        salt: ByteArray,
        iterations: Int = 210_000
    ): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)
        return ByteBuffer.allocate(2 + iv.size + encrypted.size)
            .put(1)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
    }

    fun decrypt(key: SecretKey, packed: ByteArray): ByteArray {
        require(packed.size > 14) { "Encrypted data is too short." }
        val buffer = ByteBuffer.wrap(packed)
        val version = buffer.get().toInt()
        require(version == 1) { "Unsupported encrypted archive version: $version" }
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..32) { "Invalid IV length." }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val encrypted = ByteArray(buffer.remaining())
        buffer.get(encrypted)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    fun stableId(text: String, length: Int = 16): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(length)
    }
}
