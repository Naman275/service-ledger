package dev.serviceledger.backup

import dev.serviceledger.data.Appliance
import dev.serviceledger.data.Customer
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.data.ServiceVisit
import dev.serviceledger.data.Payment
import dev.serviceledger.data.LedgerRules
import dev.serviceledger.photos.PhotoRules
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class LedgerBackup(val snapshot: LedgerSnapshot, val photos: Map<String, ByteArray> = emptyMap()) {
    fun validate() {
        snapshot.validate()
        require(snapshot.photoIds() == photos.keys) { "Backup photos do not match the records." }
        photos.forEach { (id, bytes) -> PhotoRules.validate(id, bytes) }
    }
}

object BackupCodec {
    private val magic = "SLBACK03".toByteArray(Charsets.US_ASCII)
    private val version2Magic = "SLBACK02".toByteArray(Charsets.US_ASCII)
    private val legacyMagic = "SLBACK01".toByteArray(Charsets.US_ASCII)
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val ITERATIONS = 210_000
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val HEADER_LENGTH = 8 + SALT_LENGTH + IV_LENGTH + 4

    fun encrypt(snapshot: LedgerSnapshot, password: CharArray, photos: Map<String, ByteArray> = emptyMap()): ByteArray {
        require(password.size >= 12) { "Use a backup password of at least 12 characters." }
        LedgerBackup(snapshot, photos).validate()
        require(photos.values.sumOf { ((it.size + 2L) / 3) * 4 } < MAX_FILE_BYTES) { "Photos exceed the 8 MB backup limit." }
        val plain = encode(snapshot, photos).toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_FILE_BYTES - HEADER_LENGTH - 16) { "Backup exceeds the 8 MB limit." }
        try {
            val random = SecureRandom()
            val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
            val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(magic)
            val encrypted = cipher.doFinal(plain)
            return ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.write(magic)
                    output.write(salt)
                    output.write(iv)
                    output.writeInt(encrypted.size)
                    output.write(encrypted)
                }
                buffer.toByteArray()
            }
        } finally {
            plain.fill(0)
        }
    }

    fun decrypt(input: InputStream, password: CharArray): LedgerBackup {
        val bytes = readBounded(input)
        if (bytes.size < HEADER_LENGTH + 16) throw BackupException("The backup is incomplete.")
        val stream = DataInputStream(ByteArrayInputStream(bytes))
        val header = ByteArray(magic.size).also(stream::readFully)
        if (!header.contentEquals(magic) && !header.contentEquals(version2Magic) && !header.contentEquals(legacyMagic)) {
            throw BackupException("This is not a supported Service Ledger backup.")
        }
        val salt = ByteArray(SALT_LENGTH).also(stream::readFully)
        val iv = ByteArray(IV_LENGTH).also(stream::readFully)
        val length = stream.readInt()
        if (length < 16 || length != bytes.size - HEADER_LENGTH) {
            throw BackupException("The backup length is invalid.")
        }
        val encrypted = ByteArray(length).also(stream::readFully)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            cipher.doFinal(encrypted)
        } catch (error: GeneralSecurityException) {
            throw BackupException("Incorrect password or damaged backup. No records were changed.", error)
        }
        return try {
            decode(plain.toString(Charsets.UTF_8)).also { it.validate() }
        } catch (error: JSONException) {
            throw BackupException("The backup contains invalid data. No records were changed.", error)
        } catch (error: IllegalArgumentException) {
            throw BackupException("The backup contains invalid records. No records were changed.", error)
        } finally {
            plain.fill(0)
        }
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                SecretKeySpec(key, "AES")
            } finally {
                key.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            if (total > MAX_FILE_BYTES) throw BackupException("Backup exceeds the 8 MB limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun encode(snapshot: LedgerSnapshot, photos: Map<String, ByteArray> = emptyMap()): String = JSONObject().apply {
        put("schemaVersion", 3)
        put("photos", JSONObject().apply {
            photos.forEach { (id, bytes) -> put(id, Base64.getEncoder().encodeToString(bytes)) }
        })
        put("customers", JSONArray().apply {
            snapshot.customers.forEach { customer ->
                put(JSONObject().apply {
                    put("id", customer.id)
                    put("name", customer.name)
                    put("phone", customer.phone)
                    put("address", customer.address)
                })
            }
        })
        put("appliances", JSONArray().apply {
            snapshot.appliances.forEach { appliance ->
                put(JSONObject().apply {
                    put("id", appliance.id)
                    put("customerId", appliance.customerId)
                    put("name", appliance.name)
                    put("model", appliance.model)
                    put("serial", appliance.serial)
                    put("initialDueDay", appliance.initialDueDay ?: JSONObject.NULL)
                    put("photoId", appliance.photoId ?: JSONObject.NULL)
                })
            }
        })
        put("visits", JSONArray().apply {
            snapshot.visits.forEach { visit ->
                put(JSONObject().apply {
                    put("id", visit.id)
                    put("customerId", visit.customerId)
                    put("applianceId", visit.applianceId ?: JSONObject.NULL)
                    put("serviceDay", visit.serviceDay)
                    put("work", visit.work)
                    put("parts", visit.parts)
                    put("amountPaise", visit.amountPaise)
                    put("paymentDueDay", visit.paymentDueDay ?: JSONObject.NULL)
                    put("nextDueDay", visit.nextDueDay ?: JSONObject.NULL)
                    put("recordedAtMillis", visit.recordedAtMillis)
                    put("photoId", visit.photoId ?: JSONObject.NULL)
                })
            }
        })
        put("payments", JSONArray().apply {
            snapshot.payments.forEach { payment ->
                put(JSONObject().apply {
                    put("id", payment.id)
                    put("serviceId", payment.serviceId)
                    put("amountPaise", payment.amountPaise)
                    put("paymentDay", payment.paymentDay ?: JSONObject.NULL)
                    put("method", payment.method)
                    put("reference", payment.reference)
                    put("notes", payment.notes)
                    put("recordedAtMillis", payment.recordedAtMillis)
                    put("voidedAtMillis", payment.voidedAtMillis ?: JSONObject.NULL)
                    put("voidReason", payment.voidReason)
                })
            }
        })
    }.toString()

    internal fun decode(json: String): LedgerBackup {
        val root = JSONObject(json)
        val version = root.long("schemaVersion")
        require(version in 1L..3L) { "Unsupported backup schema." }
        val customers = root.getJSONArray("customers")
        val appliances = root.getJSONArray("appliances")
        val visits = root.getJSONArray("visits")
        require(customers.length().toLong() + appliances.length() + visits.length() <= LedgerSnapshot.MAX_RECORDS)
        val decodedAppliances = appliances.objects {
                Appliance(it.string("id"), it.string("customerId"), it.string("name"),
                    it.string("model"), it.string("serial"), it.nullableLong("initialDueDay"),
                    if (version == 1L) null else it.nullableString("photoId"))
        }
        val owners = decodedAppliances.associate { it.id to it.customerId }
        val payments = if (version == 3L) {
            val entries = root.getJSONArray("payments")
            require(customers.length().toLong() + appliances.length() + visits.length() + entries.length() <= LedgerSnapshot.MAX_RECORDS)
            entries.objects {
                Payment(it.string("id"), it.string("serviceId"), it.long("amountPaise"), it.nullableLong("paymentDay"),
                    it.string("method"), it.string("reference"), it.string("notes"), it.long("recordedAtMillis"),
                    it.nullableLong("voidedAtMillis"), it.string("voidReason"))
            }
        } else {
            visits.objects {
                val received = if (version == 1L) {
                    val paid = it.get("paid")
                    require(paid is Boolean) { "Invalid paid value." }
                    if (paid) it.long("amountPaise") else 0L
                } else it.long("receivedPaise")
                LedgerRules.pendingAmount(it.long("amountPaise"), received)
                Payment(it.string("id"), it.string("id"), received, null, "OPENING",
                    recordedAtMillis = it.long("recordedAtMillis"))
            }.filter { it.amountPaise > 0 }
        }
        val snapshot = LedgerSnapshot(
            customers.objects { Customer(it.string("id"), it.string("name"), it.string("phone"), it.string("address")) },
            decodedAppliances,
            visits.objects {
                ServiceVisit(it.string("id"),
                    if (version == 3L) it.string("customerId") else requireNotNull(owners[it.string("applianceId")]) { "A service has no appliance." },
                    if (version == 3L) it.nullableString("applianceId") else it.string("applianceId"), it.long("serviceDay"),
                    it.string("work"), it.string("parts"), it.long("amountPaise"),
                    it.nullableLong("nextDueDay"), it.long("recordedAtMillis"),
                    if (version == 1L) null else it.nullableString("photoId"),
                    if (version == 3L) it.nullableLong("paymentDueDay") else null)
            },
            payments,
        )
        val photos = if (version == 1L) emptyMap() else {
            val values = root.getJSONObject("photos")
            require(values.length() <= LedgerSnapshot.MAX_RECORDS) { "Too many photos." }
            values.keys().asSequence().associateWith { id ->
                PhotoRules.validateId(id)
                val encoded = values.string(id)
                require(encoded.length <= ((PhotoRules.MAX_BYTES + 2) / 3) * 4) { "Photo exceeds the supported size." }
                Base64.getDecoder().decode(encoded)
            }
        }
        return LedgerBackup(snapshot, photos).also { it.validate() }
    }

    private fun JSONObject.string(key: String): String {
        val value = get(key)
        require(value is String) { "Invalid text field." }
        return value
    }

    private fun JSONObject.long(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "Invalid integer field." }
        return (value as Number).toLong()
    }

    private fun JSONObject.nullableLong(key: String): Long? {
        require(has(key)) { "Missing field." }
        return if (isNull(key)) null else long(key)
    }

    private fun JSONObject.nullableString(key: String): String? {
        require(has(key)) { "Missing field." }
        return if (isNull(key)) null else string(key)
    }

    private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }
}
