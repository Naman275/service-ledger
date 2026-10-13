package dev.serviceledger.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import dev.serviceledger.backup.BackupCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

class PhotoStore(private val context: Context) {
    private val directory = File(context.filesDir, "photos")

    fun import(uri: Uri): String {
        val source = context.contentResolver.openInputStream(uri)
            ?: throw IOException("The selected image could not be opened.")
        val bytes = source.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var size = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                size += read
                if (size > 20 * 1024 * 1024) throw IOException("Choose an image smaller than 20 MB.")
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "The selected file is not a readable image." }
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > PhotoRules.MAX_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("The selected image could not be decoded.")
        var bitmap = decoded
        try {
            val exif = ByteArrayInputStream(bytes).use { ExifInterface(it) }
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            if (!matrix.isIdentity) {
                bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (bitmap !== decoded) decoded.recycle()
            }
            val scale = PhotoRules.MAX_DIMENSION.toFloat() / max(bitmap.width, bitmap.height)
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()),
                    max(1, (bitmap.height * scale).roundToInt()), true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            var quality = 85
            while (true) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IOException("Photo compression failed.")
                val jpeg = output.toByteArray()
                if (jpeg.size <= PhotoRules.MAX_BYTES) {
                    val id = PhotoRules.id(jpeg)
                    install(mapOf(id to jpeg))
                    return id
                }
                if (quality > 45) quality -= 10
                else {
                    val scaled = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width / 2), max(1, bitmap.height / 2), true)
                    if (scaled === bitmap) throw IOException("This image cannot be compressed to the supported size.")
                    bitmap.recycle()
                    bitmap = scaled
                    quality = 85
                }
            }
        } finally {
            bitmap.recycle()
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    fun read(id: String): ByteArray {
        val file = file(id)
        if (!file.isFile || file.length() !in 1..PhotoRules.MAX_BYTES.toLong()) {
            throw IOException("A saved photo is missing or damaged. Replace or remove it before backing up.")
        }
        return file.readBytes().also { PhotoRules.validate(id, it) }
    }

    fun export(ids: Set<String>): Map<String, ByteArray> {
        var encodedSize = 0L
        return ids.associateWith { id ->
            val bytes = read(id)
            encodedSize += ((bytes.size + 2L) / 3) * 4
            require(encodedSize < BackupCodec.MAX_FILE_BYTES) {
                "Photos exceed the 8 MB backup limit. Remove unneeded attachments before backing up."
            }
            bytes
        }
    }

    fun install(photos: Map<String, ByteArray>) {
        photos.forEach { (id, bytes) ->
            PhotoRules.validate(id, bytes)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outMimeType == "image/jpeg" &&
                options.outWidth in 1..PhotoRules.MAX_DIMENSION && options.outHeight in 1..PhotoRules.MAX_DIMENSION) {
                "The backup contains an invalid or oversized photo."
            }
        }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create private photo storage.")
        photos.forEach { (id, bytes) ->
            val atomic = AtomicFile(file(id))
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: IOException) {
                atomic.failWrite(output)
                throw error
            }
        }
    }

    fun removeUnreferenced(keep: Set<String>) {
        if (!directory.exists()) return
        val files = directory.listFiles() ?: throw IOException("Cannot check unused photo files.")
        files.filter { it.name.endsWith(".jpg") && it.name !in keep }.forEach {
            if (!it.delete()) throw IOException("Records were saved, but an unused photo could not be removed.")
        }
    }

    private fun file(id: String): File {
        PhotoRules.validateId(id)
        return File(directory, id)
    }
}
