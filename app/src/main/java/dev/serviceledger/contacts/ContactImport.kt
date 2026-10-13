package dev.serviceledger.contacts

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.io.IOException

data class ImportedContact(val name: String, val phone: String)

object ContactImport {
    fun read(resolver: ContentResolver, uri: Uri): ImportedContact {
        // ACTION_PICK on Phone.CONTENT_URI grants access to only the selected phone row.
        val cursor = resolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)
            ?: throw IOException("The selected contact could not be opened.")
        return cursor.use {
            if (!it.moveToFirst()) throw IOException("The selected contact is no longer available.")
            val name = it.getString(it.getColumnIndexOrThrow(Phone.DISPLAY_NAME)).orEmpty().trim()
            val phone = it.getString(it.getColumnIndexOrThrow(Phone.NUMBER)).orEmpty().trim()
            if (name.isEmpty() && phone.isEmpty()) throw IOException("This contact has no name or phone number.")
            ImportedContact(name, phone)
        }
    }
}
