package dev.serviceledger.data

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import dev.serviceledger.photos.PhotoRules

data class LedgerSnapshot(
    val customers: List<Customer> = emptyList(),
    val appliances: List<Appliance> = emptyList(),
    val visits: List<ServiceVisit> = emptyList(),
    val payments: List<Payment> = emptyList(),
) {
    fun photoIds(): Set<String> = (appliances.mapNotNull { it.photoId } + visits.mapNotNull { it.photoId }).toSet()

    private val latestVisits by lazy {
        visits.filter { it.applianceId != null }.groupBy { it.applianceId }.mapValues { (_, entries) ->
            entries.maxWith(compareBy<ServiceVisit> { it.serviceDay }
                .thenBy { it.recordedAtMillis }.thenBy { it.id })
        }
    }

    private val receipts by lazy {
        payments.filter { it.voidedAtMillis == null }.groupBy { it.serviceId }
            .mapValues { (_, entries) -> entries.sumOf { it.amountPaise } }
    }
    fun received(visit: ServiceVisit): Long = receipts[visit.id] ?: 0L
    fun pending(visit: ServiceVisit): Long = LedgerRules.pendingAmount(visit.amountPaise, received(visit))
    fun pendingVisits(): List<ServiceVisit> = visits.filter { pending(it) > 0 }
        .sortedWith(compareBy<ServiceVisit> { it.paymentDueDay ?: Long.MAX_VALUE }.thenBy { it.serviceDay }.thenBy { it.id })
    fun balance(customerId: String? = null): LedgerBalance {
        val services = if (customerId == null) visits else visits.filter { it.customerId == customerId }
        return LedgerBalance(services.sumOf { it.amountPaise }, services.sumOf(::received))
    }
    fun directFollowUps(day: Long): List<ServiceVisit> =
        visits.filter { it.applianceId == null && it.nextDueDay?.let { due -> due <= day } == true }
            .sortedBy { it.nextDueDay }

    fun nextDue(appliance: Appliance): Long? {
        val latest = latestVisits[appliance.id]
        return if (latest == null) appliance.initialDueDay else latest.nextDueDay
    }

    fun dueOnOrBefore(day: Long): List<Appliance> =
        appliances.filter { nextDue(it)?.let { due -> due <= day } == true }
            .sortedWith(compareBy<Appliance> { nextDue(it) }.thenBy { it.name })

    fun validate() {
        require(customers.size.toLong() + appliances.size + visits.size + payments.size <= MAX_RECORDS) {
            "Too many records. The current limit is $MAX_RECORDS."
        }
        require(customers.map { it.id }.toSet().size == customers.size) { "Duplicate customer IDs." }
        require(appliances.map { it.id }.toSet().size == appliances.size) { "Duplicate appliance IDs." }
        require(visits.map { it.id }.toSet().size == visits.size) { "Duplicate service IDs." }
        require(payments.map { it.id }.toSet().size == payments.size) { "Duplicate payment IDs." }
        customers.forEach(LedgerRules::validate)
        appliances.forEach(LedgerRules::validate)
        visits.forEach(LedgerRules::validate)
        payments.forEach(LedgerRules::validate)
        val customerIds = customers.map { it.id }.toSet()
        val appliancesById = appliances.associateBy { it.id }
        require(appliances.all { it.customerId in customerIds }) { "An appliance has no customer." }
        require(visits.all { it.customerId in customerIds }) { "A service has no customer." }
        require(visits.all { it.applianceId == null || appliancesById[it.applianceId]?.customerId == it.customerId }) {
            "A service appliance must belong to its customer."
        }
        val visitIds = visits.map { it.id }.toSet()
        require(payments.all { it.serviceId in visitIds }) { "A payment has no service." }
        visits.forEach { pending(it) }
    }

    companion object {
        const val MAX_RECORDS = 40_000
    }
}

data class LedgerBalance(val charged: Long, val received: Long) {
    val pending: Long get() = charged - received
}

object LedgerRules {
    val paymentMethods = listOf("CASH", "UPI", "BANK", "CARD", "OTHER")
    private val firstDay = LocalDate.of(1900, 1, 1).toEpochDay()
    private val lastDay = LocalDate.of(2200, 12, 31).toEpochDay()
    const val MAX_AMOUNT_PAISE = 99_999_999_999L

    fun parseDate(text: String, optional: Boolean = false): Long? {
        if (text.isBlank() && optional) return null
        val day = try {
            LocalDate.parse(text.trim()).toEpochDay()
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Enter a valid date as YYYY-MM-DD.", error)
        }
        validateDay(day)
        return day
    }

    fun parseAmount(text: String): Long {
        require(Regex("\\d{1,9}(\\.\\d{1,2})?").matches(text.trim())) {
            "Enter an INR amount with up to two decimal places, for example 450.00."
        }
        val amount = BigDecimal(text.trim()).movePointRight(2).longValueExact()
        require(amount in 0..MAX_AMOUNT_PAISE) { "The amount is outside the supported range." }
        return amount
    }

    fun validate(customer: Customer) {
        id(customer.id)
        text(customer.name, "Customer name", 100, required = true)
        text(customer.phone, "Phone", 30)
        require(customer.phone.isBlank() ||
            (customer.phone.count(Char::isDigit) in 7..15 &&
                customer.phone.all { it.isDigit() || it in "+() -." })) {
            "Enter a phone number with 7 to 15 digits, or leave it empty."
        }
        text(customer.address, "Address", 500)
    }

    fun validate(appliance: Appliance) {
        id(appliance.id)
        id(appliance.customerId)
        text(appliance.name, "Appliance name", 100, required = true)
        text(appliance.model, "Model", 100)
        text(appliance.serial, "Serial number", 100)
        appliance.initialDueDay?.let(::validateDay)
        appliance.photoId?.let(PhotoRules::validateId)
    }

    fun validate(visit: ServiceVisit) {
        id(visit.id)
        id(visit.customerId)
        visit.applianceId?.let(::id)
        validateDay(visit.serviceDay)
        text(visit.work, "Work performed", 2_000, required = true)
        text(visit.parts, "Parts", 1_000)
        require(visit.amountPaise in 0..MAX_AMOUNT_PAISE) { "Invalid service amount." }
        visit.photoId?.let(PhotoRules::validateId)
        require(visit.recordedAtMillis in 0..7_289_654_400_000L) { "Invalid record timestamp." }
        visit.nextDueDay?.let {
            validateDay(it)
            require(it >= visit.serviceDay) { "Next service cannot be before this service." }
        }
        visit.paymentDueDay?.let {
            validateDay(it)
            require(it >= visit.serviceDay) { "Payment due date cannot be before the service." }
        }
    }

    fun validate(payment: Payment) {
        id(payment.id)
        id(payment.serviceId)
        require(payment.amountPaise in 1..MAX_AMOUNT_PAISE) { "Payment must be greater than zero." }
        require(payment.method in paymentMethods || payment.method == "OPENING") { "Choose a valid payment method." }
        require(payment.paymentDay != null || payment.method == "OPENING") { "Payment date is required." }
        payment.paymentDay?.let(::validateDay)
        text(payment.reference, "Payment reference", 100)
        text(payment.notes, "Payment notes", 500)
        require(payment.recordedAtMillis in 0..7_289_654_400_000L) { "Invalid payment timestamp." }
        payment.voidedAtMillis?.let {
            require(it in payment.recordedAtMillis..7_289_654_400_000L) { "Invalid void timestamp." }
        }
        text(payment.voidReason, "Correction reason", 500, required = payment.voidedAtMillis != null)
        require(payment.voidedAtMillis != null || payment.voidReason.isEmpty()) { "An active payment cannot have a correction reason." }
    }

    fun pendingAmount(totalPaise: Long, receivedPaise: Long): Long {
        require(totalPaise in 0..MAX_AMOUNT_PAISE) { "Invalid service amount." }
        require(receivedPaise in 0..totalPaise) { "Amount received must be between zero and the total charge." }
        return totalPaise - receivedPaise
    }

    private fun validateDay(day: Long) {
        require(day in firstDay..lastDay) { "Dates must be between 1900 and 2200." }
    }

    private fun id(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "Invalid record ID."
        }
    }

    private fun text(value: String, label: String, limit: Int, required: Boolean = false) {
        require(!required || value.isNotBlank()) { "$label is required." }
        require(value.length <= limit) { "$label must be $limit characters or fewer." }
    }
}
