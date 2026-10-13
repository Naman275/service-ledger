package dev.serviceledger

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.serviceledger.data.LedgerDatabase
import dev.serviceledger.data.LedgerRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LedgerRepositoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val name = "test-${UUID.randomUUID()}.db"

    @Before fun setUp() {
        database = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        repository = LedgerRepository(database)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun `records persist after closing and reopening database`() = runBlocking {
        val data = sample()
        repository.restore(data)
        database.close()
        database = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        repository = LedgerRepository(database)
        assertEquals(data, repository.snapshot())
    }

    @Test fun `customer edit preserves appliance and service children`() = runBlocking {
        val data = sample()
        repository.restore(data)
        repository.save(data.customers.single().copy(name = "Updated name"))
        assertEquals(data.appliances, repository.snapshot().appliances)
        assertEquals(data.visits, repository.snapshot().visits)
        assertEquals("Updated name", repository.snapshot().customers.single().name)
    }

    @Test fun `customer deletion cascades only to their records`() = runBlocking {
        val first = sample()
        val second = sample()
        repository.restore(first.copy(
            customers = first.customers + second.customers,
            appliances = first.appliances + second.appliances,
            visits = first.visits + second.visits,
        ))
        repository.deleteCustomer(first.customers.single().id)
        assertEquals(second, repository.snapshot())
    }

    @Test fun `appliance deletion preserves services as general services`() = runBlocking {
        val data = sample()
        repository.restore(data)
        repository.deleteAppliance(data.appliances.single().id)
        val after = repository.snapshot()
        assertEquals(data.customers, after.customers)
        assertTrue(after.appliances.isEmpty())
        assertEquals(data.visits.single().copy(applianceId = null), after.visits.single())
    }

    @Test fun `invalid restore leaves existing records untouched`() = runBlocking {
        val data = sample()
        repository.restore(data)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.restore(sample().copy(customers = emptyList())) }
        }
        assertEquals(data, repository.snapshot())
    }

    @Test fun `database failure during restore rolls entire transaction back`() = runBlocking {
        val data = sample()
        repository.restore(data)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_test_visit BEFORE INSERT ON visits WHEN NEW.work = 'reject restore' BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        val incoming = sample().let { it.copy(visits = listOf(it.visits.single().copy(work = "reject restore"))) }
        assertThrows(SQLiteException::class.java) { runBlocking { repository.restore(incoming) } }
        assertEquals(data, repository.snapshot())
    }

    @Test fun `restore replaces rather than merges`() = runBlocking {
        repository.restore(sample())
        val replacement = sample()
        repository.restore(replacement)
        assertEquals(replacement, repository.snapshot())
    }

    @Test fun `visit edit and deletion update due list`() = runBlocking {
        val data = sample()
        repository.restore(data)
        repository.save(data.visits.single().copy(nextDueDay = null))
        assertTrue(repository.snapshot().dueOnOrBefore(30_000).isEmpty())
        repository.deleteVisit(data.visits.single().id)
        assertEquals(1, repository.snapshot().dueOnOrBefore(30_000).size)
    }

    @Test fun `general service receipts survive reopening`() = runBlocking {
        val data = sample()
        val service = data.visits.single().copy(applianceId = null, amountPaise = 10_000)
        repository.restore(data.copy(appliances = emptyList(), visits = listOf(service)))
        repository.recordPayment(payment(service, 3_000))
        repository.recordPayment(payment(service, 2_000).copy(method = "UPI", reference = "transaction-1"))
        database.close()
        database = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        repository = LedgerRepository(database)
        val after = repository.snapshot()
        assertEquals(2, after.payments.size)
        assertEquals(5_000L, after.received(service))
        assertEquals(5_000L, after.pending(service))
        assertEquals(5_000L, after.balance(service.customerId).pending)
    }

    @Test fun `invalid receipts preserve balance`() = runBlocking {
        val data = sample()
        val service = data.visits.single().copy(amountPaise = 10_000)
        repository.restore(data.copy(visits = listOf(service)))
        val first = payment(service, 6_000)
        repository.recordPayment(first)
        for (amount in listOf(0L, -1L, 4_001L)) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.recordPayment(payment(service, amount)) } }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.recordPayment(first) } }
        repository.recordPayment(payment(service, 4_000))
        assertTrue(repository.snapshot().pendingVisits().isEmpty())
        assertEquals(10_000L, repository.snapshot().received(service))
    }

    @Test fun `duplicate payment ID is rejected`() = runBlocking {
        val data = sample()
        repository.restore(data)
        val payment = payment(data.visits.single())
        repository.recordPayment(payment)
        assertThrows(SQLiteException::class.java) { runBlocking { repository.recordPayment(payment) } }
        assertEquals(1, repository.snapshot().payments.size)
    }

    @Test fun `payment correction keeps history and reopens balance`() = runBlocking<Unit> {
        val data = sample()
        repository.restore(data)
        val service = data.visits.single()
        val payment = payment(service, service.amountPaise)
        repository.recordPayment(payment)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.voidPayment(payment.id, " ") } }
        repository.voidPayment(payment.id, "Entered twice")
        val after = repository.snapshot()
        assertEquals("Entered twice", after.payments.single().voidReason)
        assertNotNull(after.payments.single().voidedAtMillis)
        assertEquals(service.amountPaise, after.pending(service))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.voidPayment(payment.id, "Again") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.deleteVisit(service.id) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.deleteCustomer(service.customerId) } }
    }

    @Test fun `service edits protect receipts and ownership`() = runBlocking {
        val data = sample()
        val other = sample()
        repository.restore(data.copy(customers = data.customers + other.customers, appliances = data.appliances + other.appliances))
        val service = data.visits.single()
        repository.recordPayment(payment(service))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.save(service.copy(amountPaise = 4_999)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.save(service.copy(applianceId = other.appliances.single().id)) } }
        repository.save(service.copy(applianceId = null, amountPaise = 5_000))
        assertEquals(0L, repository.snapshot().balance(service.customerId).pending)
    }

    @Test fun `invalid payment restore preserves records`() = runBlocking {
        val data = sample()
        repository.restore(data)
        for (invalid in listOf(
            data.copy(payments = listOf(payment(data.visits.single()).copy(serviceId = UUID.randomUUID().toString()))),
            data.copy(payments = listOf(payment(data.visits.single(), data.visits.single().amountPaise + 1))),
        )) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.restore(invalid) } }
            assertEquals(data, repository.snapshot())
        }
    }
}
