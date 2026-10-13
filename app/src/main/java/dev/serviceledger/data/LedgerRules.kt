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
) {
    fun photoIds(): Set<String> = (appliances.mapNotNull { it.photoId } + visits.mapNotNull { it.photoId }).toSet()

    private val latestVisits by lazy {
        visits.groupBy { it.applianceId }.mapValues { (_, entries) ->
            entries.maxWith(compareBy<ServiceVisit> { it.serviceDay }
                .thenBy { it.recordedAtMillis }.thenBy { it.id })
        }
    }

    fun nextDue(appliance: Appliance): Long? {
        val latest = latestVisits[appliance.id]
        return if (latest == null) appliance.initialDueDay else latest.nextDueDay
    }

    fun dueOnOrBefore(day: Long): List<Appliance> =
        appliances.filter { nextDue(it)?.let { due -> due <= day } == true }
            .sortedWith(compareBy<Appliance> { nextDue(it) }.thenBy { it.name })

    fun validate() {
        require(customers.size + appliances.size + visits.size <= MAX_RECORDS) {
            "Too many records. The current limit is $MAX_RECORDS."
        }
        require(customers.map { it.id }.toSet().size == customers.size) { "Duplicate customer IDs." }
        require(appliances.map { it.id }.toSet().size == appliances.size) { "Duplicate appliance IDs." }
        require(visits.map { it.id }.toSet().size == visits.size) { "Duplicate service IDs." }
        customers.forEach(LedgerRules::validate)
        appliances.forEach(LedgerRules::validate)
        visits.forEach(LedgerRules::validate)
        val customerIds = customers.map { it.id }.toSet()
        val applianceIds = appliances.map { it.id }.toSet()
        require(appliances.all { it.customerId in customerIds }) { "An appliance has no customer." }
        require(visits.all { it.applianceId in applianceIds }) { "A service has no appliance." }
    }

    companion object {
        const val MAX_RECORDS = 20_000
    }
}

object LedgerRules {
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
        id(visit.applianceId)
        validateDay(visit.serviceDay)
        text(visit.work, "Work performed", 2_000, required = true)
        text(visit.parts, "Parts", 1_000)
        require(visit.amountPaise in 0..MAX_AMOUNT_PAISE) { "Invalid service amount." }
        pendingAmount(visit.amountPaise, visit.receivedPaise)
        visit.photoId?.let(PhotoRules::validateId)
        require(visit.recordedAtMillis in 0..7_289_654_400_000L) { "Invalid record timestamp." }
        visit.nextDueDay?.let {
            validateDay(it)
            require(it >= visit.serviceDay) { "Next service cannot be before this service." }
        }
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
