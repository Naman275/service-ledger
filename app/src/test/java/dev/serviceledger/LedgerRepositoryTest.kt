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

    @Test fun `appliance deletion preserves owner but deletes visits`() = runBlocking {
        val data = sample()
        repository.restore(data)
        repository.deleteAppliance(data.appliances.single().id)
        val after = repository.snapshot()
        assertEquals(data.customers, after.customers)
        assertTrue(after.appliances.isEmpty())
        assertTrue(after.visits.isEmpty())
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
}
