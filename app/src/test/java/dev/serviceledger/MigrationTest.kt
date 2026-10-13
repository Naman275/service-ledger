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
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            createSchema(old, 1)
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
            .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3).build()
        try {
            val repository = LedgerRepository(database)
            val after = repository.snapshot()
            assertEquals(data.customers, after.customers)
            assertEquals(data.appliances, after.appliances)
            assertEquals(original.amountPaise, after.received(after.visits.first { it.id == original.id }))
            assertEquals(0L, after.received(after.visits.first { it.id == unpaidId }))
            assertEquals(1, after.payments.size)
            assertNull(after.payments.single().paymentDay)
            assertEquals("OPENING", after.payments.single().method)
            assertTrue(after.visits.all { it.photoId == null })
            repository.recordPayment(payment(after.visits.first { it.id == unpaidId }))
            val updated = repository.snapshot()
            assertEquals(5_000L, updated.received(updated.visits.first { it.id == unpaidId }))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.deleteCustomer(data.customers.single().id) } }
            repository.deleteAppliance(data.appliances.single().id)
            assertEquals(2, repository.snapshot().visits.size)
            assertEquals(2, repository.snapshot().payments.size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `v2 preserves receipts photos and ownership`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-v2-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        val data = sample()
        val customer = data.customers.single()
        val appliance = data.appliances.single()
        val photoId = dev.serviceledger.photos.PhotoRules.id(byteArrayOf(1, 2, 3))
        val services = listOf(0L, 12_345L, 45_050L).associate { received ->
            data.visits.single().copy(id = UUID.randomUUID().toString(), photoId = photoId) to received
        }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            createSchema(old, 2)
            old.execSQL("INSERT INTO customers VALUES (?, ?, ?, ?)", arrayOf(customer.id, customer.name, customer.phone, customer.address))
            old.execSQL("INSERT INTO appliances VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(appliance.id, appliance.customerId, appliance.name, appliance.model, appliance.serial, appliance.initialDueDay, null))
            services.forEach { (service, received) ->
                old.execSQL("INSERT INTO visits VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(service.id, service.applianceId, service.serviceDay, service.work, service.parts,
                        service.amountPaise, received, service.nextDueDay, service.recordedAtMillis, photoId))
            }
            old.version = 2
        }
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .addMigrations(LedgerDatabase.MIGRATION_2_3).build()
        try {
            val after = LedgerRepository(database).snapshot()
            after.validate()
            assertEquals(2, after.payments.size)
            services.forEach { (service, received) ->
                assertEquals(service, after.visits.first { it.id == service.id })
                assertEquals(received, after.received(service))
            }
            assertTrue(after.payments.all { it.method == "OPENING" && it.paymentDay == null })
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun createSchema(database: SQLiteDatabase, version: Int) {
        val schema = javaClass.getResourceAsStream("/dev.serviceledger.data.LedgerDatabase/$version.json")
            ?: error("Version $version Room schema is missing from test resources.")
        val entities = schema.bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("database").getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (i in 0 until indices.length()) {
                database.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
    }
}
