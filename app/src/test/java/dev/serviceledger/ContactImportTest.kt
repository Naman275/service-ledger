package dev.serviceledger

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import dev.serviceledger.contacts.ContactImport
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class ContactImportTest {
    @Test fun `reads only selected phone row without requiring whole address book`() {
        val provider = SelectedPhoneProvider()
        ShadowContentResolver.registerProviderInternal("test.contacts", provider)
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        val selected = Uri.parse("content://test.contacts/phones/7")
        val imported = ContactImport.read(resolver, selected)
        assertEquals(selected, provider.lastUri)
        assertArrayEquals(arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), provider.lastProjection)
        assertEquals("Pilot Customer", imported.name)
        assertEquals("+91 98765 43210", imported.phone)
    }

    @Test fun `missing selected contact reports an error`() {
        val provider = SelectedPhoneProvider().apply { empty = true }
        ShadowContentResolver.registerProviderInternal("test.contacts", provider)
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        assertThrows(IOException::class.java) {
            ContactImport.read(resolver, Uri.parse("content://test.contacts/phones/missing"))
        }
    }

    private class SelectedPhoneProvider : ContentProvider() {
        var empty = false
        var lastUri: Uri? = null
        var lastProjection: Array<out String>? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            lastUri = uri
            lastProjection = projection
            return MatrixCursor(arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER)).apply {
                if (!empty) addRow(arrayOf(" Pilot Customer ", "+91 98765 43210"))
            }
        }
        override fun getType(uri: Uri) = Phone.CONTENT_ITEM_TYPE
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    }
}
