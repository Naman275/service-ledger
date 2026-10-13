package dev.serviceledger.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.serviceledger.LedgerViewModel
import dev.serviceledger.R
import dev.serviceledger.data.LedgerRules
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.data.Payment
import dev.serviceledger.data.ServiceVisit
import java.time.LocalDate
import java.util.UUID

@Composable
internal fun PaymentForm(data: LedgerSnapshot, service: ServiceVisit, vm: LedgerViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val id = rememberSaveable(service.id) { UUID.randomUUID().toString() }
    var amount by rememberSaveable(service.id) { mutableStateOf("") }
    var day by rememberSaveable(service.id) { mutableStateOf(LocalDate.now().toEpochDay()) }
    var method by rememberSaveable(service.id) { mutableStateOf("CASH") }
    var reference by rememberSaveable(service.id) { mutableStateOf("") }
    var notes by rememberSaveable(service.id) { mutableStateOf("") }
    FormDialog(stringResource(R.string.record_payment), vm.busy, vm.error, onDismiss,
        stringResource(R.string.record_payment),
        confirmDiscard = amount.isNotBlank() || reference.isNotBlank() || notes.isNotBlank(),
        onSave = {
            try {
                val payment = Payment(id, service.id, LedgerRules.parseAmount(amount), day, method, reference.trim(), notes.trim())
                LedgerRules.validate(payment)
                vm.mutate(context.getString(R.string.payment_saved), onDismiss) { recordPayment(payment) }
            } catch (failure: IllegalArgumentException) {
                vm.report(failure.message ?: context.getString(R.string.check_amounts))
            }
        },
    ) {
        Text(data.customers.first { it.id == service.customerId }.name, style = MaterialTheme.typography.titleMedium)
        Text(service.work)
        PaymentSummary(service.amountPaise, data.received(service))
        Input(R.string.payment_amount, amount, KeyboardType.Decimal, enabled = !vm.busy) { amount = it }
        CalendarInput(R.string.payment_date, day, enabled = !vm.busy) { it?.let { selected -> day = selected } }
        Text(stringResource(R.string.payment_method), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerRules.paymentMethods.forEach { option ->
                FilterChip(method == option, { method = option }, { Text(paymentMethodLabel(option)) }, enabled = !vm.busy)
            }
        }
        Input(R.string.payment_reference, reference, enabled = !vm.busy) { reference = it }
        Input(R.string.payment_notes, notes, multiline = true, enabled = !vm.busy) { notes = it }
        Text(stringResource(R.string.payment_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun VoidPaymentForm(payment: Payment, vm: LedgerViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reason by rememberSaveable(payment.id) { mutableStateOf("") }
    FormDialog(stringResource(R.string.void_payment), vm.busy, vm.error, onDismiss,
        stringResource(R.string.void_payment), confirmDiscard = reason.isNotBlank(),
        onSave = { vm.mutate(context.getString(R.string.payment_voided), onDismiss) { voidPayment(payment.id, reason) } },
    ) {
        Text(formatMoney(payment.amountPaise), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.void_hint))
        Input(R.string.void_reason, reason, multiline = true, enabled = !vm.busy) { reason = it }
    }
}

@Composable
internal fun ReminderForm(data: LedgerSnapshot, service: ServiceVisit, vm: LedgerViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val customer = data.customers.first { it.id == service.customerId }
    var phone by rememberSaveable(service.id) { mutableStateOf(customer.phone) }
    val message = if (data.pending(service) > 0) PaymentReminders.message(context, data, customer, service) else ""
    FormDialog(stringResource(R.string.reminder_preview), vm.busy, vm.error, onDismiss,
        stringResource(R.string.open_whatsapp), onSave = {
            try {
                require(data.pending(service) > 0) { "This service has no pending payment." }
                val intent = Intent(Intent.ACTION_VIEW, PaymentReminders.uri(phone, message))
                try {
                    context.startActivity(intent.setPackage("com.whatsapp"))
                    onDismiss()
                } catch (_: ActivityNotFoundException) {
                    try {
                        context.startActivity(intent.setPackage("com.whatsapp.w4b"))
                        onDismiss()
                    } catch (_: ActivityNotFoundException) {
                        vm.report(context.getString(R.string.whatsapp_missing))
                    }
                }
            } catch (failure: IllegalArgumentException) {
                vm.report(requireNotNull(failure.message))
            } catch (_: SecurityException) {
                vm.report("Android did not allow WhatsApp to open. Check your device settings.")
            }
        },
    ) {
        Input(R.string.whatsapp_phone, phone, KeyboardType.Phone, enabled = !vm.busy) { phone = it }
        Text(stringResource(R.string.whatsapp_hint), style = MaterialTheme.typography.bodySmall)
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}
