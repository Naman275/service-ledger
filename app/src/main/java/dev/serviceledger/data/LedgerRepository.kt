package dev.serviceledger.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LedgerRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()

    val snapshots: Flow<LedgerSnapshot> =
        database.invalidationTracker.createFlow("customers", "appliances", "visits")
            .map { snapshot() }

    suspend fun snapshot(): LedgerSnapshot = database.withTransaction {
        LedgerSnapshot(dao.customers(), dao.appliances(), dao.visits())
    }

    suspend fun save(customer: Customer) = database.withTransaction {
        LedgerRules.validate(customer)
        enforceRecordLimit(customer.id, dao.customers().map { it.id })
        dao.saveCustomer(customer)
    }

    suspend fun save(appliance: Appliance) = database.withTransaction {
        LedgerRules.validate(appliance)
        enforceRecordLimit(appliance.id, dao.appliances().map { it.id })
        dao.saveAppliance(appliance)
    }

    suspend fun save(visit: ServiceVisit) = database.withTransaction {
        LedgerRules.validate(visit)
        enforceRecordLimit(visit.id, dao.visits().map { it.id })
        dao.saveVisit(visit)
    }

    suspend fun deleteCustomer(id: String) = dao.deleteCustomer(id)
    suspend fun deleteAppliance(id: String) = dao.deleteAppliance(id)
    suspend fun deleteVisit(id: String) = dao.deleteVisit(id)

    suspend fun restore(snapshot: LedgerSnapshot) {
        snapshot.validate()
        database.withTransaction {
            dao.clearVisits()
            dao.clearAppliances()
            dao.clearCustomers()
            dao.insertCustomers(snapshot.customers)
            dao.insertAppliances(snapshot.appliances)
            dao.insertVisits(snapshot.visits)
        }
    }

    private suspend fun enforceRecordLimit(id: String, existingIds: List<String>) {
        if (id !in existingIds) {
            val total = dao.customers().size + dao.appliances().size + dao.visits().size
            require(total < LedgerSnapshot.MAX_RECORDS) { "The current record limit has been reached." }
        }
    }
}
