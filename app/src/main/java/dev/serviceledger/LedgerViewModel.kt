package dev.serviceledger

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.serviceledger.backup.BackupCodec
import dev.serviceledger.data.LedgerRepository
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.notifications.DueReminders
import dev.serviceledger.contacts.ContactImport
import dev.serviceledger.contacts.ImportedContact
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LedgerApplication
    private val repository = app.repository
    private val preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var snapshot by mutableStateOf<LedgerSnapshot?>(null)
        private set
    var loadError by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var lastBackup by mutableStateOf(preferences.getLong("last_backup", 0))
        private set
    var reminders by mutableStateOf(preferences.getBoolean("reminders", false))
        private set
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.snapshots.catch { failure ->
                if (failure is CancellationException) throw failure
                Log.e("ServiceLedger", "Unable to load records.", failure)
                loadError = true
                error = "Unable to load records. Close and reopen the app. Your database has not been reset."
            }.collect { snapshot = it }
        }
        if (reminders) DueReminders.enable(app)
    }

    fun clearError() { error = null }

    fun report(message: String) {
        error = message
    }

    fun mutate(success: String, onSuccess: () -> Unit = {}, operation: suspend LedgerRepository.() -> Unit) {
        execute(success, onSuccess) {
            repository.operation()
            try {
                app.photos.removeUnreferenced(repository.snapshot().photoIds())
            } catch (failure: IOException) {
                Log.e("ServiceLedger", "Records saved; photo cleanup failed.", failure)
                messages.send("Records saved, but unused photos could not be removed. Try again later.")
            }
        }
    }

    fun importContact(uri: Uri, onImported: (ImportedContact) -> Unit) {
        execute("Contact copied. Review the details, then save.") {
            val contact = ContactImport.read(app.contentResolver, uri)
            withContext(Dispatchers.Main) { onImported(contact) }
        }
    }

    fun importPhoto(uri: Uri, onImported: (String) -> Unit) {
        execute("Photo attached. Save the record to keep it.") {
            val id = app.photos.import(uri)
            withContext(Dispatchers.Main) { onImported(id) }
        }
    }

    fun backup(uri: Uri, password: CharArray, onSuccess: () -> Unit) {
        execute("Encrypted backup saved. Keep it somewhere other than this phone.", onSuccess, cleanup = { password.fill('\u0000') }) {
            val snapshot = repository.snapshot()
            val encrypted = BackupCodec.encrypt(snapshot, password, app.photos.export(snapshot.photoIds()))
            val stream = app.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("The selected destination could not be opened.")
            stream.use { it.write(encrypted); it.flush() }
            val timestamp = System.currentTimeMillis()
            if (!preferences.edit().putLong("last_backup", timestamp).commit()) {
                throw IOException("Backup was written, but its status could not be saved. Check the destination.")
            }
            withContext(Dispatchers.Main) { lastBackup = timestamp }
        }
    }

    fun restore(uri: Uri, password: CharArray, onSuccess: () -> Unit) {
        execute("Backup restored successfully.", onSuccess, cleanup = { password.fill('\u0000') }) {
            val stream = app.contentResolver.openInputStream(uri)
                ?: throw IOException("The selected backup could not be opened.")
            val restored = stream.use { BackupCodec.decrypt(it, password) }
            // Install immutable, hash-checked files first; a failed DB transaction leaves old references usable.
            app.photos.install(restored.photos)
            repository.restore(restored.snapshot)
            app.photos.removeUnreferenced(restored.snapshot.photoIds())
            app.getSystemService(android.app.NotificationManager::class.java).cancel(DueReminders.NOTIFICATION_ID)
            if (!preferences.edit().remove("last_backup").commit()) {
                throw IOException("Records were restored, but backup status could not be reset. Create a new backup.")
            }
            withContext(Dispatchers.Main) { lastBackup = 0 }
        }
    }

    fun updateReminders(enabled: Boolean) {
        execute(if (enabled) "Daily reminders enabled. Delivery time may vary." else "Daily reminders disabled.") {
            if (enabled && !DueReminders.allowed(app)) {
                throw IllegalArgumentException("Allow notifications in Android settings before enabling reminders.")
            }
            if (!preferences.edit().putBoolean("reminders", enabled).commit()) {
                throw IOException("Unable to save reminder settings.")
            }
            if (enabled) DueReminders.enable(app) else DueReminders.disable(app)
            withContext(Dispatchers.Main) { reminders = enabled }
        }
    }

    private fun execute(
        success: String,
        onSuccess: () -> Unit = {},
        cleanup: () -> Unit = {},
        action: suspend () -> Unit,
    ) {
        if (busy) {
            cleanup()
            report("An operation is already running. Please wait.")
            return
        }
        busy = true
        error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                onSuccess()
                messages.send(success)
            } catch (failure: IOException) {
                fail(failure, "File operation failed. Check storage access and try again.")
            } catch (failure: SQLiteException) {
                fail(failure, "Database operation failed. Your changes were not saved.")
            } catch (failure: IllegalArgumentException) {
                fail(failure, "Please check the entered values.")
            } catch (failure: SecurityException) {
                fail(failure, "Storage or notification permission was denied.")
            } catch (failure: GeneralSecurityException) {
                fail(failure, "The device could not complete backup encryption.")
            } finally {
                cleanup()
                busy = false
            }
        }
    }

    private fun fail(failure: Exception, fallback: String) {
        Log.e("ServiceLedger", fallback, failure)
        error = failure.localizedMessage ?: fallback
    }
}
