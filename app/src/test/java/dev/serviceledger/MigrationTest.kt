package dev.serviceledger

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.serviceledger.data.LedgerDatabase
import dev.serviceledger.data.LedgerRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class MigrationTest {
    @Test fun `upgrade preserves v1 records and maps paid status to received amount`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        val data = sample()
        val original = data.visits.single()
        val unpaidId = UUID.randomUUID().toString()
        val schema = javaClass.getResourceAsStream("/dev.serviceledger.data.LedgerDatabase/1.json")
            ?: error("Version 1 Room schema is missing from test resources.")
        val entities = schema.bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                if (entity.has("indices")) {
                    val indices = entity.getJSONArray("indices")
                    for (i in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
            }
            val customer = data.customers.single()
            old.execSQL("INSERT INTO customers VALUES (?, ?, ?, ?)", arrayOf(customer.id, customer.name, customer.phone, customer.address))
            val appliance = data.appliances.single()
            old.execSQL("INSERT INTO appliances VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(appliance.id, appliance.customerId, appliance.name, appliance.model, appliance.serial, appliance.initialDueDay))
            for ((id, paid) in listOf(original.id to 1, unpaidId to 0)) {
                old.execSQL("INSERT INTO visits VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(id, original.applianceId, original.serviceDay, original.work, original.parts,
                        original.amountPaise, paid, original.nextDueDay, original.recordedAtMillis))
            }
            old.version = 1
        }
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .addMigrations(LedgerDatabase.MIGRATION_1_2).build()
        try {
            val repository = LedgerRepository(database)
            val after = repository.snapshot()
            assertEquals(data.customers, after.customers)
            assertEquals(data.appliances, after.appliances)
            assertEquals(original.amountPaise, after.visits.first { it.id == original.id }.receivedPaise)
            assertEquals(0L, after.visits.first { it.id == unpaidId }.receivedPaise)
            assertTrue(after.visits.all { it.photoId == null })
            repository.save(after.visits.first { it.id == unpaidId }.copy(receivedPaise = 5_000))
            assertEquals(5_000L, repository.snapshot().visits.first { it.id == unpaidId }.receivedPaise)
            repository.deleteCustomer(data.customers.single().id)
            assertTrue(repository.snapshot().visits.isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
