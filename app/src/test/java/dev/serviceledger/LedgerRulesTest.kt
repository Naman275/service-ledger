package dev.serviceledger

import dev.serviceledger.data.Appliance
import dev.serviceledger.data.Customer
import dev.serviceledger.data.LedgerRules
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.data.ServiceVisit
import dev.serviceledger.data.Payment
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

internal fun customer() = Customer(UUID.randomUUID().toString(), "Asha Rao", "+91 98765 43210", "12 Main Road")
internal fun appliance(customer: Customer) = Appliance(UUID.randomUUID().toString(), customer.id, "Kitchen RO", "Model A", "RO-123", 20_000)
internal fun visit(appliance: Appliance) = ServiceVisit(UUID.randomUUID().toString(), appliance.customerId, appliance.id, 19_900, "Replaced filter", "Sediment filter", 45_050, 20_050, 1_700_000_000_000)
internal fun payment(service: ServiceVisit, amount: Long = 5_000) =
    Payment(UUID.randomUUID().toString(), service.id, amount, 19_900, "CASH", recordedAtMillis = 1_700_000_000_000)
internal fun sample(): LedgerSnapshot {
    val customer = customer()
    val appliance = appliance(customer)
    return LedgerSnapshot(listOf(customer), listOf(appliance), listOf(visit(appliance)))
}

class LedgerRulesTest {
    @Test fun `partial payment leaves exact pending balance`() {
        assertEquals(5_000L, LedgerRules.pendingAmount(LedgerRules.parseAmount("100"), LedgerRules.parseAmount("50")))
        assertEquals(1L, LedgerRules.pendingAmount(10_000, 9_999))
        assertEquals(0L, LedgerRules.pendingAmount(10_000, 10_000))
        assertEquals(10_000L, LedgerRules.pendingAmount(10_000, 0))
        assertEquals(0L, LedgerRules.pendingAmount(0, 0))
    }

    @Test fun `negative received and overpayments are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { LedgerRules.pendingAmount(10_000, -1) }
        assertThrows(IllegalArgumentException::class.java) { LedgerRules.pendingAmount(10_000, 10_001) }
        val data = sample()
        val visit = data.visits.single()
        assertThrows(IllegalArgumentException::class.java) { data.copy(payments = listOf(payment(visit, visit.amountPaise + 1))).validate() }
    }

    @Test fun `amounts use exact minor units`() {
        assertEquals(10L, LedgerRules.parseAmount("0.10"))
        assertEquals(45_050L, LedgerRules.parseAmount("450.50"))
        assertEquals(0L, LedgerRules.parseAmount("0"))
        assertEquals(LedgerRules.MAX_AMOUNT_PAISE, LedgerRules.parseAmount("999999999.99"))
    }

    @Test fun `invalid amounts are rejected rather than rounded`() {
        listOf("", "-1", "1.999", "NaN", "1e4", "1,000", "1000000000", ".").forEach {
            assertThrows(IllegalArgumentException::class.java) { LedgerRules.parseAmount(it) }
        }
    }

    @Test fun `dates reject invalid calendars and accept optional empty values`() {
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), LedgerRules.parseDate("2024-02-29"))
        assertNull(LedgerRules.parseDate("", optional = true))
        listOf("2025-02-29", "2026-13-01", "13/10/2026", "", "1800-01-01").forEach {
            assertThrows(IllegalArgumentException::class.java) { LedgerRules.parseDate(it) }
        }
    }

    @Test fun `latest service overrides initial appliance due date`() {
        val data = sample()
        assertEquals(20_050L, data.nextDue(data.appliances.single()))
        assertTrue(data.dueOnOrBefore(20_049).isEmpty())
        assertEquals(1, data.dueOnOrBefore(20_050).size)
    }

    @Test fun `blank next due on latest service clears older due date`() {
        val data = sample()
        val old = data.visits.single()
        val newer = old.copy(id = UUID.randomUUID().toString(), serviceDay = 20_001, nextDueDay = null)
        val updated = data.copy(visits = listOf(newer, old))
        assertNull(updated.nextDue(updated.appliances.single()))
        assertTrue(updated.dueOnOrBefore(30_000).isEmpty())
    }

    @Test fun `backdated entries do not override newer service`() {
        val data = sample()
        val older = data.visits.single().copy(id = UUID.randomUUID().toString(), serviceDay = 19_000, nextDueDay = 19_500,
            recordedAtMillis = 1_800_000_000_000)
        assertEquals(20_050L, data.copy(visits = data.visits + older).nextDue(data.appliances.single()))
    }

    @Test fun `same day entries use recording order`() {
        val data = sample()
        val newer = data.visits.single().copy(id = UUID.randomUUID().toString(), nextDueDay = 21_000,
            recordedAtMillis = data.visits.single().recordedAtMillis + 1)
        assertEquals(21_000L, data.copy(visits = listOf(newer) + data.visits).nextDue(data.appliances.single()))
    }

    @Test fun `removing last visit restores initial due date`() {
        val data = sample().copy(visits = emptyList())
        assertEquals(20_000L, data.nextDue(data.appliances.single()))
    }

    @Test fun `due list excludes future and unscheduled appliances and sorts oldest first`() {
        val data = sample().copy(visits = emptyList())
        val base = data.appliances.single()
        val early = base.copy(id = UUID.randomUUID().toString(), initialDueDay = 19_000)
        val none = base.copy(id = UUID.randomUUID().toString(), initialDueDay = null)
        val future = base.copy(id = UUID.randomUUID().toString(), initialDueDay = 20_001)
        assertEquals(listOf(early.id, base.id),
            data.copy(appliances = listOf(future, none, base, early)).dueOnOrBefore(20_000).map { it.id })
    }

    @Test fun `invalid customer data is rejected`() {
        val original = customer()
        listOf(original.copy(name = " "), original.copy(phone = "123"), original.copy(name = "a".repeat(101))).forEach {
            assertThrows(IllegalArgumentException::class.java) { LedgerRules.validate(it) }
        }
        LedgerRules.validate(original.copy(phone = ""))
    }

    @Test fun `service cannot schedule followup before visit`() {
        val visit = sample().visits.single()
        assertThrows(IllegalArgumentException::class.java) { LedgerRules.validate(visit.copy(nextDueDay = visit.serviceDay - 1)) }
    }

    @Test fun `snapshot rejects duplicate and orphan records`() {
        val data = sample()
        assertThrows(IllegalArgumentException::class.java) { data.copy(customers = data.customers + data.customers).validate() }
        assertThrows(IllegalArgumentException::class.java) { data.copy(customers = emptyList()).validate() }
        assertThrows(IllegalArgumentException::class.java) { data.copy(appliances = emptyList()).validate() }
    }

    @Test fun `payment due ordering is independent of service followups`() {
        val original = sample()
        val first = original.visits.single().copy(applianceId = null, nextDueDay = null, paymentDueDay = 20_000)
        val second = first.copy(id = UUID.randomUUID().toString(), paymentDueDay = null, nextDueDay = 20_001)
        val paid = first.copy(id = UUID.randomUUID().toString(), paymentDueDay = 19_999)
        val data = original.copy(visits = listOf(second, paid, first), payments = listOf(payment(paid, paid.amountPaise)))
        data.validate()
        assertEquals(listOf(first.id, second.id), data.pendingVisits().map { it.id })
        assertEquals(listOf(second.id), data.directFollowUps(20_001).map { it.id })
        assertEquals(first.amountPaise + second.amountPaise, data.balance(first.customerId).pending)
    }

    @Test fun `invalid payment dates methods and corrections are rejected`() {
        val valid = payment(sample().visits.single())
        for (invalid in listOf(valid.copy(paymentDay = null), valid.copy(method = "invalid"),
            valid.copy(voidedAtMillis = valid.recordedAtMillis + 1), valid.copy(voidReason = "Not voided"))) {
            assertThrows(IllegalArgumentException::class.java) { LedgerRules.validate(invalid) }
        }
    }
}
