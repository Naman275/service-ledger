package dev.serviceledger.ui

import android.content.Context
import android.net.Uri
import dev.serviceledger.R
import dev.serviceledger.data.Customer
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.data.ServiceVisit

internal object PaymentReminders {
    fun phoneNumber(input: String): String {
        val trimmed = input.trim()
        require(trimmed.startsWith("+") || trimmed.startsWith("00")) {
            "Include the country code, for example +91 98765 43210."
        }
        val national = if (trimmed.startsWith("+")) trimmed.drop(1) else trimmed.drop(2)
        require(national.all { it in '0'..'9' || it in " ()-." }) { "Enter a valid international phone number." }
        val digits = national.filter { it in '0'..'9' }
        require(digits.length in 7..15 && digits.first() != '0') { "Enter a valid international phone number." }
        return digits
    }

    fun uri(phone: String, message: String): Uri = Uri.Builder().scheme("https").authority("wa.me")
        .appendPath(phoneNumber(phone)).appendQueryParameter("text", message).build()

    fun message(context: Context, data: LedgerSnapshot, customer: Customer, service: ServiceVisit): String {
        require(data.pending(service) > 0) { "This service has no pending payment." }
        return context.getString(R.string.payment_reminder_message, customer.name, formatMoney(data.pending(service)),
            service.work, date(service.serviceDay), formatMoney(service.amountPaise), formatMoney(data.received(service)),
            service.paymentDueDay?.let { context.getString(R.string.reminder_due_suffix, date(it)) }.orEmpty())
    }
}
