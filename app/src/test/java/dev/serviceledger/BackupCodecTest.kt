package dev.serviceledger

import dev.serviceledger.backup.BackupCodec
import dev.serviceledger.backup.BackupException
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.photos.PhotoRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val password get() = "a long test password".toCharArray()

    @Test fun `encrypted roundtrip preserves every field and unicode`() {
        val original = sample()
        val snapshot = original.copy(customers = listOf(original.customers.single().copy(name = "\u0906\u0936\u093e Rao")),
            payments = listOf(payment(original.visits.single()).copy(reference = "UPI-123", notes = "Deposit")))
        val bytes = BackupCodec.encrypt(snapshot, password)
        assertEquals(snapshot, BackupCodec.decrypt(ByteArrayInputStream(bytes), password).snapshot)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Main Road"))
    }

    @Test fun `empty backup is valid`() {
        val empty = LedgerSnapshot()
        assertEquals(empty, BackupCodec.decrypt(ByteArrayInputStream(BackupCodec.encrypt(empty, password)), password).snapshot)
    }

    @Test fun `random salt and IV make each backup different`() {
        val data = sample()
        assertFalse(BackupCodec.encrypt(data, password).contentEquals(BackupCodec.encrypt(data, password)))
    }

    @Test fun `wrong password is an explicit failure`() {
        val bytes = BackupCodec.encrypt(sample(), password)
        assertThrows(BackupException::class.java) {
            BackupCodec.decrypt(ByteArrayInputStream(bytes), "not the right password".toCharArray())
        }
    }

    @Test fun `modified ciphertext fails authentication`() {
        val bytes = BackupCodec.encrypt(sample(), password)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(BackupException::class.java) { BackupCodec.decrypt(ByteArrayInputStream(bytes), password) }
    }

    @Test fun `truncated and trailing data are rejected`() {
        val bytes = BackupCodec.encrypt(sample(), password)
        listOf(bytes.copyOf(10), bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0)).forEach {
            assertThrows(BackupException::class.java) { BackupCodec.decrypt(ByteArrayInputStream(it), password) }
        }
    }

    @Test fun `unsupported header is rejected`() {
        val bytes = BackupCodec.encrypt(sample(), password)
        bytes[7] = '9'.code.toByte()
        assertThrows(BackupException::class.java) { BackupCodec.decrypt(ByteArrayInputStream(bytes), password) }
    }

    @Test fun `oversized file is rejected before decryption`() {
        assertThrows(BackupException::class.java) {
            BackupCodec.decrypt(ByteArrayInputStream(ByteArray(BackupCodec.MAX_FILE_BYTES + 1)), password)
        }
    }

    @Test fun `short encryption password is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encrypt(sample(), "short".toCharArray()) }
    }

    @Test fun `schema and integer types are validated`() {
        val data = sample()
        val unsupported = JSONObject(BackupCodec.encode(data)).put("schemaVersion", 4).toString()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(unsupported) }
        val fractional = JSONObject(BackupCodec.encode(data))
        fractional.getJSONArray("visits").getJSONObject(0).put("amountPaise", 1.5)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(fractional.toString()) }
    }

    @Test fun `missing nullable field is not silently interpreted as null`() {
        val json = JSONObject(BackupCodec.encode(sample()))
        json.getJSONArray("appliances").getJSONObject(0).remove("initialDueDay")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.toString()) }
    }

    @Test fun `invalid snapshots cannot be exported`() {
        val invalid = sample().copy(customers = emptyList())
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encrypt(invalid, password) }
    }

    @Test fun `partial payment and photo bytes survive encryption`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val id = PhotoRules.id(bytes)
        val original = sample()
        val data = original.copy(
            appliances = listOf(original.appliances.single().copy(photoId = id)),
            visits = listOf(original.visits.single().copy(photoId = id)),
            payments = listOf(payment(original.visits.single())),
        )
        val restored = BackupCodec.decrypt(ByteArrayInputStream(BackupCodec.encrypt(data, password, mapOf(id to bytes))), password)
        assertEquals(data, restored.snapshot)
        assertArrayEquals(bytes, restored.photos.getValue(id))
        assertEquals(1, restored.photos.size)
    }

    @Test fun `missing unreferenced and mismatched photos are rejected`() {
        val data = sample()
        val bytes = byteArrayOf(1, 2, 3)
        val id = PhotoRules.id(bytes)
        val withPhoto = data.copy(appliances = listOf(data.appliances.single().copy(photoId = id)))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encrypt(withPhoto, password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encrypt(data, password, mapOf(id to bytes)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encrypt(withPhoto, password, mapOf(id to byteArrayOf(4))) }
    }

    @Test fun `legacy encrypted backup migrates paid and unpaid values`() {
        for (paid in listOf(true, false)) {
            val data = sample()
            val root = JSONObject(BackupCodec.encode(data)).put("schemaVersion", 1)
            root.remove("photos")
            root.getJSONArray("appliances").getJSONObject(0).remove("photoId")
            root.getJSONArray("visits").getJSONObject(0).apply {
                remove("receivedPaise")
                remove("photoId")
                put("paid", paid)
            }
            val restored = BackupCodec.decrypt(ByteArrayInputStream(legacyEncrypt(root.toString())), password)
            assertEquals(if (paid) data.visits.single().amountPaise else 0L, restored.snapshot.received(restored.snapshot.visits.single()))
            assertTrue(restored.snapshot.payments.all { it.method == "OPENING" && it.paymentDay == null })
            assertTrue(restored.photos.isEmpty())
            assertEquals(data.customers, restored.snapshot.customers)
        }
    }

    @Test fun `version two backup becomes opening receipt and preserves balances`() {
        val data = sample()
        val root = JSONObject(BackupCodec.encode(data)).put("schemaVersion", 2)
        root.remove("payments")
        root.getJSONArray("visits").getJSONObject(0).apply {
            remove("customerId")
            remove("paymentDueDay")
            put("receivedPaise", 12_345L)
        }
        val restored = BackupCodec.decrypt(ByteArrayInputStream(legacyEncrypt(root.toString(), "SLBACK02")), password).snapshot
        assertEquals(12_345L, restored.received(restored.visits.single()))
        assertEquals(data.visits.single().customerId, restored.visits.single().customerId)
        assertEquals("OPENING", restored.payments.single().method)
        assertNull(restored.payments.single().paymentDay)
    }

    @Test fun `general service and voided payment survive backup`() {
        val original = sample()
        val service = original.visits.single().copy(applianceId = null, paymentDueDay = 20_000)
        val data = original.copy(visits = listOf(service), payments = listOf(payment(service).copy(
            voidedAtMillis = 1_700_000_000_001, voidReason = "Duplicate")))
        assertEquals(data, BackupCodec.decrypt(ByteArrayInputStream(BackupCodec.encrypt(data, password)), password).snapshot)
    }

    private fun legacyEncrypt(json: String, magic: String = "SLBACK01"): ByteArray {
        val header = magic.toByteArray()
        val salt = ByteArray(16) { 1 }
        val iv = ByteArray(12) { 2 }
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        spec.clearPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        val payload = cipher.doFinal(json.toByteArray())
        return ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { write(header); write(salt); write(iv); writeInt(payload.size); write(payload) }
        }.toByteArray()
    }
}
