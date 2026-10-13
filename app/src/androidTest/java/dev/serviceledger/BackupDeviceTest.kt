package dev.serviceledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.serviceledger.backup.BackupCodec
import dev.serviceledger.backup.BackupException
import dev.serviceledger.data.Appliance
import dev.serviceledger.data.Customer
import dev.serviceledger.data.LedgerDatabase
import dev.serviceledger.data.LedgerRepository
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.data.ServiceVisit
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    @Test fun encryptedFileRestoresAcrossDatabaseInstancesAndRejectsWrongPassword() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<LedgerApplication>()
        val databaseName = "device-test-${UUID.randomUUID()}.db"
        val file = File(context.cacheDir, "test-${UUID.randomUUID()}.slbackup")
        var database = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName).build()
        try {
            val customer = Customer(UUID.randomUUID().toString(), "Device test", "9876543210", "Test address")
            val appliance = Appliance(UUID.randomUUID().toString(), customer.id, "RO", "Test model", "", null)
            val visit = ServiceVisit(UUID.randomUUID().toString(), appliance.id, 20_000, "Changed filter", "Filter", 45_050, 45_050, 20_100)
            val snapshot = LedgerSnapshot(listOf(customer), listOf(appliance), listOf(visit))
            val repository = LedgerRepository(database)
            repository.restore(snapshot)
            file.writeBytes(BackupCodec.encrypt(repository.snapshot(), "device test password".toCharArray()))
            database.close()
            database = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName).build()
            val reopened = LedgerRepository(database)
            assertEquals(snapshot, reopened.snapshot())
            reopened.restore(LedgerSnapshot())
            file.inputStream().use {
                reopened.restore(BackupCodec.decrypt(it, "device test password".toCharArray()).snapshot)
            }
            assertEquals(snapshot, reopened.snapshot())
            assertThrows(BackupException::class.java) {
                file.inputStream().use { BackupCodec.decrypt(it, "incorrect password".toCharArray()) }
            }
            assertEquals(snapshot, reopened.snapshot())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            check(!file.exists() || file.delete()) { "Could not remove device test backup." }
        }
    }
}
