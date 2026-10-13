package dev.serviceledger

import android.app.Application
import androidx.room.Room
import dev.serviceledger.data.LedgerDatabase
import dev.serviceledger.data.LedgerRepository
import dev.serviceledger.notifications.DueReminders
import dev.serviceledger.photos.PhotoStore

class LedgerApplication : Application() {
    val database: LedgerDatabase by lazy {
        Room.databaseBuilder(this, LedgerDatabase::class.java, "service-ledger.db")
            .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3).build()
    }
    val repository: LedgerRepository by lazy { LedgerRepository(database) }
    val photos: PhotoStore by lazy { PhotoStore(this) }

    override fun onCreate() {
        super.onCreate()
        DueReminders.createChannel(this)
    }
}
