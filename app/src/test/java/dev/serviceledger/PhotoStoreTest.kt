package dev.serviceledger

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.serviceledger.photos.PhotoRules
import dev.serviceledger.photos.PhotoStore
import dev.serviceledger.backup.BackupCodec
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoStoreTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `import rotates scales strips metadata and keeps a private copy`() {
        val source = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(12.3, 45.6)
            saveAttributes()
        }
        val photos = PhotoStore(context)
        try {
            val id = photos.import(Uri.fromFile(source))
            assertTrue(source.delete())
            val bytes = photos.read(id)
            assertTrue(bytes.size <= PhotoRules.MAX_BYTES)
            assertEquals(id, PhotoRules.id(bytes))
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            assertEquals(640, options.outWidth)
            assertEquals(1280, options.outHeight)
            val metadata = ExifInterface(ByteArrayInputStream(bytes))
            assertNull(metadata.latLong)
            assertEquals(0, metadata.rotationDegrees)
            val data = sample().let {
                it.copy(appliances = listOf(it.appliances.single().copy(photoId = id)),
                    visits = listOf(it.visits.single().copy(photoId = id)),
                    payments = listOf(payment(it.visits.single())))
            }
            val encrypted = BackupCodec.encrypt(data, "photo test password".toCharArray(), photos.export(data.photoIds()))
            photos.removeUnreferenced(emptySet())
            val restored = BackupCodec.decrypt(ByteArrayInputStream(encrypted), "photo test password".toCharArray())
            photos.install(restored.photos)
            assertEquals(data, restored.snapshot)
            photos.removeUnreferenced(setOf(id))
            assertArrayEquals(bytes, photos.read(id))
            photos.removeUnreferenced(emptySet())
            assertThrows(IOException::class.java) { photos.read(id) }
        } finally {
            if (source.exists()) check(source.delete())
            photos.removeUnreferenced(emptySet())
        }
    }

    @Test fun `invalid image data is rejected before being installed`() {
        val photos = PhotoStore(context)
        val invalid = byteArrayOf(1, 2, 3, 4)
        val id = PhotoRules.id(invalid)
        assertThrows(IllegalArgumentException::class.java) { photos.install(mapOf(id to invalid)) }
        assertThrows(IOException::class.java) { photos.read(id) }
        assertThrows(IllegalArgumentException::class.java) { photos.read("../outside.jpg") }
    }

    @Test fun `photo byte limit is enforced exactly`() {
        val validSize = ByteArray(PhotoRules.MAX_BYTES) { 4 }
        PhotoRules.validate(PhotoRules.id(validSize), validSize)
        val tooLarge = ByteArray(PhotoRules.MAX_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { PhotoRules.validate(PhotoRules.id(tooLarge), tooLarge) }
    }
}
