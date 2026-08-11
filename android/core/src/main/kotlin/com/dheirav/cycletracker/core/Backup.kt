package com.dheirav.cycletracker.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted export and import.
 *
 * Offline storage means the data is one lost phone away from gone, so backup is not a nicety —
 * and an unencrypted health export sitting in Downloads would undo the entire reason this app
 * exists. Hence a passphrase, not a plain file.
 *
 * Lives in `:core` rather than `:app` so the round trip is unit-testable on the JVM. Cryptography
 * that has only ever been exercised by hand on a device is cryptography you do not trust.
 */

@Serializable
data class BackupDay(
    val date: String,
    val isBleeding: Boolean = false,
    val flow: String? = null,
    val notes: String = "",
    val source: String = "OBSERVED",
    val symptoms: Map<String, Int> = emptyMap(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class BackupSnapshot(
    val formatVersion: Int = BackupCodec.FORMAT_VERSION,
    val exportedAt: String,
    val days: List<BackupDay>,
)

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupCodec {

    const val FORMAT_VERSION = 1

    /** Identifies our files and lets a future format change fail loudly instead of garbling. */
    private val MAGIC = "CYCBK".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    /** OWASP's floor for PBKDF2-HMAC-SHA256. Costs a few hundred ms once, on a manual action. */
    private const val ITERATIONS = 210_000

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun encode(snapshot: BackupSnapshot): ByteArray =
        json.encodeToString(BackupSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)

    fun decode(plaintext: ByteArray): BackupSnapshot =
        try {
            json.decodeFromString(BackupSnapshot.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("Backup contents are not readable", e)
        }

    /**
     * Layout: MAGIC ‖ version ‖ salt ‖ iv ‖ ciphertext+tag.
     *
     * Salt and IV are fresh per export, so exporting the same data twice never produces the same
     * bytes and never reuses a key stream.
     */
    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val body = cipher.doFinal(plaintext)

        return MAGIC + byteArrayOf(FORMAT_VERSION.toByte()) + salt + iv + body
    }

    /**
     * Throws [BackupException] on a wrong passphrase or a tampered file — GCM authenticates, so a
     * modified backup fails rather than silently decrypting to plausible garbage.
     */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        val headerSize = MAGIC.size + 1 + SALT_BYTES + IV_BYTES
        if (blob.size <= headerSize) throw BackupException("File is too short to be a backup")
        if (!blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("Not a cycle tracker backup")
        }

        val version = blob[MAGIC.size].toInt()
        if (version != FORMAT_VERSION) {
            throw BackupException("Backup format v$version is not supported by this version")
        }

        var offset = MAGIC.size + 1
        val salt = blob.copyOfRange(offset, offset + SALT_BYTES).also { offset += SALT_BYTES }
        val iv = blob.copyOfRange(offset, offset + IV_BYTES).also { offset += IV_BYTES }
        val body = blob.copyOfRange(offset, blob.size)

        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
                doFinal(body)
            }
        } catch (e: Exception) {
            throw BackupException("Wrong passphrase, or the file is damaged", e)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }
}
