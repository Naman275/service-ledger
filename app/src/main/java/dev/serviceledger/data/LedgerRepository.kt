package dev.serviceledger.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LedgerRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()

    val snapshots: Flow<LedgerSnapshot> =
        database.invalidationTracker.createFlow("customers", "appliances", "visits", "payments")
            .map { snapshot() }

    suspend fun snapshot(): LedgerSnapshot = database.withTransaction {
        LedgerSnapshot(dao.customers(), dao.appliances(), dao.visits(), dao.payments())
    }

    suspend fun save(customer: Customer) = database.withTransaction {
        LedgerRules.validate(customer)
        enforceRecordLimit(dao.hasCustomer(customer.id))
        dao.saveCustomer(customer)
    }

    suspend fun save(appliance: Appliance) = database.withTransaction {
        LedgerRules.validate(appliance)
        val existing = dao.appliance(appliance.id)
        require(existing == null || existing.customerId == appliance.customerId) { "An appliance cannot be moved to another customer." }
        enforceRecordLimit(existing != null)
        dao.saveAppliance(appliance)
    }

    suspend fun save(visit: ServiceVisit) = database.withTransaction {
        LedgerRules.validate(visit)
        val existing = dao.visit(visit.id)
        require(existing == null || existing.customerId == visit.customerId) { "A service cannot be moved to another customer." }
        require(visit.applianceId == null || dao.appliance(visit.applianceId)?.customerId == visit.customerId) {
            "Choose an appliance belonging to this customer."
        }
        LedgerRules.pendingAmount(visit.amountPaise, dao.received(visit.id))
        enforceRecordLimit(existing != null)
        dao.saveVisit(visit)
    }

    suspend fun recordPayment(payment: Payment) = database.withTransaction {
        LedgerRules.validate(payment)
        require(payment.method != "OPENING" && payment.voidedAtMillis == null) { "Record a new payment with its actual method." }
        val service = requireNotNull(dao.visit(payment.serviceId)) { "The service no longer exists." }
        require(payment.amountPaise <= service.amountPaise - dao.received(service.id)) { "Payment exceeds the remaining balance." }
        enforceRecordLimit(false)
        dao.insertPayment(payment)
    }

    suspend fun voidPayment(id: String, reason: String) = database.withTransaction {
        val payment = requireNotNull(dao.payment(id)) { "The payment no longer exists." }
        require(payment.voidedAtMillis == null) { "This payment has already been voided." }
        val corrected = payment.copy(voidedAtMillis = System.currentTimeMillis(), voidReason = reason.trim())
        LedgerRules.validate(corrected)
        dao.voidPayment(id, requireNotNull(corrected.voidedAtMillis), corrected.voidReason)
    }

    suspend fun deleteCustomer(id: String) = database.withTransaction {
        require(dao.visits().filter { it.customerId == id }.none { dao.hasPayments(it.id) }) {
            "This customer has payment history and cannot be deleted."
        }
        dao.deleteCustomer(id)
    }
    suspend fun deleteAppliance(id: String) = dao.deleteAppliance(id)
    suspend fun deleteVisit(id: String) = database.withTransaction {
        require(!dao.hasPayments(id)) { "This service has payment history and cannot be deleted. Void incorrect payments with a reason instead." }
        dao.deleteVisit(id)
    }

    suspend fun restore(snapshot: LedgerSnapshot) {
        snapshot.validate()
        database.withTransaction {
            dao.clearPayments()
            dao.clearVisits()
            dao.clearAppliances()
            dao.clearCustomers()
            dao.insertCustomers(snapshot.customers)
            dao.insertAppliances(snapshot.appliances)
            dao.insertVisits(snapshot.visits)
            dao.insertPayments(snapshot.payments)
        }
    }

    private suspend fun enforceRecordLimit(existing: Boolean) {
        if (!existing) {
            require(dao.recordCount() < LedgerSnapshot.MAX_RECORDS) { "The current record limit has been reached." }
        }
    }
}
