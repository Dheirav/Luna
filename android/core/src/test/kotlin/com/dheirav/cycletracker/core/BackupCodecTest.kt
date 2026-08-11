package com.dheirav.cycletracker.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup is the one feature whose failure is unrecoverable — a corrupt or undecryptable export
 * is discovered exactly when the phone is already lost. So it gets tested properly rather than
 * exercised by hand once.
 */
class BackupCodecTest {

    private val snapshot = BackupSnapshot(
        exportedAt = "2026-08-11T01:45:00Z",
        days = listOf(
            BackupDay(
                date = "2024-03-11",
                isBleeding = true,
                flow = "MEDIUM",
                notes = "cramps, slept badly",
                source = "OBSERVED",
                symptoms = mapOf("pain" to 3, "energy" to 1),
                tags = listOf("illness"),
            ),
            BackupDay(date = "2026-07-29", isBleeding = true, source = "OBSERVED"),
        ),
        predictions = listOf(
            BackupPrediction(
                madeOn = "2026-07-20",
                cycleStart = "2024-02-12",
                predictedNextPeriod = "2024-03-11",
                expectedCycleLength = 28,
                variability = 1.5,
            ),
        ),
    )

    /**
     * The ledger has to survive a phone move. It is the one table that cannot be re-entered from
     * memory — a prediction is a statement made on a day from data that no longer exists.
     */
    @Test
    fun `predictions survive the round trip`() {
        val passphrase = "correct horse battery staple".toCharArray()
        val blob = BackupCodec.encrypt(BackupCodec.encode(snapshot), passphrase)
        val restored = BackupCodec.decode(BackupCodec.decrypt(blob, passphrase))

        assertEquals(snapshot.predictions, restored.predictions)
        assertEquals(1.5, restored.predictions.single().variability!!, 1e-9)
    }

    /** Backups written before the ledger existed must still restore, not fail on a missing key. */
    @Test
    fun `a backup with no predictions field decodes to an empty ledger`() {
        val legacy = """
            {"formatVersion":1,"exportedAt":"2026-08-01T00:00:00Z",
             "days":[{"date":"2024-03-11","isBleeding":true,"source":"OBSERVED"}]}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val restored = BackupCodec.decode(legacy)

        assertEquals(1, restored.days.size)
        assertTrue(restored.predictions.isEmpty())
    }

    /** A null variability means "fewer than three observed cycles", which is not a variability of
     *  zero. Collapsing the two in transit would manufacture certainty. */
    @Test
    fun `null variability stays null through the round trip`() {
        val unknown = snapshot.copy(
            predictions = listOf(snapshot.predictions.single().copy(variability = null)),
        )
        val passphrase = "pass".toCharArray()
        val restored = BackupCodec.decode(
            BackupCodec.decrypt(BackupCodec.encrypt(BackupCodec.encode(unknown), passphrase), passphrase),
        )

        assertNull(restored.predictions.single().variability)
    }

    @Test
    fun `round trips through encrypt and decrypt`() {
        val passphrase = "correct horse battery staple".toCharArray()
        val blob = BackupCodec.encrypt(BackupCodec.encode(snapshot), passphrase)
        val restored = BackupCodec.decode(BackupCodec.decrypt(blob, passphrase))

        assertEquals(snapshot, restored)
    }

    @Test
    fun `survives unicode and empty fields`() {
        val awkward = BackupSnapshot(
            exportedAt = "2026-08-11T01:45:00Z",
            days = listOf(BackupDay(date = "2026-01-01", notes = "μῆνις — 生理 🩸\nnewline\ttab")),
        )
        val pass = "pässwörd".toCharArray()
        val restored = BackupCodec.decode(
            BackupCodec.decrypt(BackupCodec.encrypt(BackupCodec.encode(awkward), pass), pass),
        )
        assertEquals(awkward, restored)
    }

    @Test
    fun `the wrong passphrase fails loudly rather than returning garbage`() {
        val blob = BackupCodec.encrypt(BackupCodec.encode(snapshot), "right".toCharArray())
        assertThrows(BackupException::class.java) {
            BackupCodec.decrypt(blob, "wrong".toCharArray())
        }
    }

    @Test
    fun `tampering is detected`() {
        val pass = "pass".toCharArray()
        val blob = BackupCodec.encrypt(BackupCodec.encode(snapshot), pass)
        // Flip a bit in the ciphertext body. GCM authenticates, so this must not decrypt.
        blob[blob.size - 5] = (blob[blob.size - 5].toInt() xor 0x01).toByte()

        assertThrows(BackupException::class.java) { BackupCodec.decrypt(blob, pass) }
    }

    @Test
    fun `a foreign file is rejected before any crypto runs`() {
        val notABackup = "just some text file contents here, definitely not a backup".toByteArray()
        val e = assertThrows(BackupException::class.java) {
            BackupCodec.decrypt(notABackup, "pass".toCharArray())
        }
        assertEquals("Not a cycle tracker backup", e.message)
    }

    @Test
    fun `two exports of identical data differ`() {
        val pass = "pass".toCharArray()
        val plaintext = BackupCodec.encode(snapshot)
        val a = BackupCodec.encrypt(plaintext, pass)
        val b = BackupCodec.encrypt(plaintext, pass)

        // Fresh salt and IV each time — no key-stream reuse, no fingerprinting identical exports.
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(plaintext, BackupCodec.decrypt(a, pass))
        assertArrayEquals(plaintext, BackupCodec.decrypt(b, pass))
    }

    @Test
    fun `plaintext never appears in the encrypted blob`() {
        val blob = BackupCodec.encrypt(BackupCodec.encode(snapshot), "pass".toCharArray())
        val haystack = blob.toString(Charsets.ISO_8859_1)
        assertFalse("notes leaked in cleartext", haystack.contains("cramps"))
        assertFalse("dates leaked in cleartext", haystack.contains("2024-03-11"))
    }

    @Test
    fun `an empty passphrase is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encrypt(BackupCodec.encode(snapshot), charArrayOf())
        }
    }
}
