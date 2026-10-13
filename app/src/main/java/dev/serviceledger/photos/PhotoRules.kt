package dev.serviceledger.photos

import java.security.MessageDigest

object PhotoRules {
    const val MAX_BYTES = 256 * 1024
    const val MAX_DIMENSION = 1280
    private val validId = Regex("[0-9a-f]{64}\\.jpg")

    fun validateId(id: String) {
        require(validId.matches(id)) { "Invalid photo reference." }
    }

    fun id(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg"

    fun validate(id: String, bytes: ByteArray) {
        validateId(id)
        require(bytes.size in 1..MAX_BYTES) { "A photo exceeds the supported size." }
        require(id(bytes) == id) { "Photo content does not match its reference." }
    }
}
