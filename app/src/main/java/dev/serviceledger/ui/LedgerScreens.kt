package dev.serviceledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.serviceledger.R
import dev.serviceledger.data.Customer
import dev.serviceledger.data.LedgerBalance
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.data.Payment
import dev.serviceledger.data.ServiceVisit

@Composable
internal fun HomeScreen(
    data: LedgerSnapshot, today: Long, onCustomers: () -> Unit, onPayments: () -> Unit,
    onDue: () -> Unit, onService: (ServiceVisit) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(date(today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.welcome_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            LedgerCard(kind = LedgerCardKind.Balance) {
                BalanceOverview(data.balance())
                Button(onClick = onPayments, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.view_payments), Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }
            }
        }
        item {
            LedgerCard {
                Text(stringResource(R.string.quick_start), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.choose_customer_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onCustomers, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.view_customers)) }
                TextButton(onClick = onDue) {
                    Text(stringResource(R.string.follow_up_count, data.dueOnOrBefore(today).size + data.directFollowUps(today).size))
                }
            }
        }
        if (data.visits.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.recent_services)) }
            items(data.visits.take(5), key = { it.id }) { service ->
                ServiceRow(data, service, today, true) { onService(service) }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
internal fun BalanceOverview(balance: LedgerBalance) {
    Text(stringResource(R.string.outstanding), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(formatMoney(balance.pending), style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(stringResource(R.string.total_business), style = MaterialTheme.typography.labelMedium)
            Text(formatMoney(balance.charged), style = MaterialTheme.typography.titleMedium)
        }
        Column {
            Text(stringResource(R.string.collected), style = MaterialTheme.typography.labelMedium)
            Text(formatMoney(balance.received), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun ServiceRow(data: LedgerSnapshot, service: ServiceVisit, today: Long, showCustomer: Boolean = false, onOpen: () -> Unit) {
    LedgerCard(onClick = onOpen, kind = LedgerCardKind.Service) {
        if (showCustomer) Text(data.customers.first { it.id == service.customerId }.name,
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(service.work, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.service_on, date(service.serviceDay)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.outstanding), style = MaterialTheme.typography.labelMedium)
                Text(formatMoney(data.pending(service)), style = MaterialTheme.typography.titleLarge)
            }
            PaymentStatus(data, service, today)
        }
    }
}

@Composable
private fun PaymentStatus(data: LedgerSnapshot, service: ServiceVisit, today: Long) {
    val pending = data.pending(service)
    val label = when {
        pending == 0L -> R.string.settled
        service.paymentDueDay?.let { it < today } == true -> R.string.overdue
        service.paymentDueDay == today -> R.string.due_today
        data.received(service) > 0 -> R.string.part_paid
        else -> R.string.unpaid
    }
    val colors = ledgerCardColors(when (label) {
        R.string.settled -> LedgerCardKind.Receipt
        R.string.part_paid, R.string.due_today -> LedgerCardKind.Balance
        else -> LedgerCardKind.Neutral
    }, androidx.compose.foundation.isSystemInDarkTheme())
    Surface(shape = MaterialTheme.shapes.small,
        color = if (label == R.string.overdue) MaterialTheme.colorScheme.errorContainer else colors.container,
        contentColor = if (label == R.string.overdue) MaterialTheme.colorScheme.onErrorContainer else colors.content) {
        Text(stringResource(label), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun PaymentsScreen(data: LedgerSnapshot, today: Long, onService: (ServiceVisit) -> Unit) {
    var history by rememberSaveable { mutableStateOf(false) }
    var overdueOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val customers = remember(data.customers) { data.customers.associateBy { it.id } }
    val services = remember(data.visits) { data.visits.associateBy { it.id } }
    fun matches(service: ServiceVisit): Boolean = query.isBlank() ||
        service.work.contains(query.trim(), ignoreCase = true) ||
        customers.getValue(service.customerId).name.contains(query.trim(), ignoreCase = true)
    val pending = data.pendingVisits().filter { matches(it) && (!overdueOnly || it.paymentDueDay?.let { day -> day < today } == true) }
    val payments = data.payments.filter { matches(services.getValue(it.serviceId)) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard(kind = LedgerCardKind.Balance) {
                Text(stringResource(R.string.payments), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.payments_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                BalanceOverview(data.balance())
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.search_payments)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!history, { history = false }, { Text(stringResource(R.string.pending_tab)) })
                    FilterChip(history, { history = true }, { Text(stringResource(R.string.history_tab)) })
                    if (!history) FilterChip(overdueOnly, { overdueOnly = !overdueOnly }, { Text(stringResource(R.string.overdue_only)) })
                }
            }
        }
        if (history) {
            if (payments.isEmpty()) item { EmptyState(stringResource(if (query.isBlank()) R.string.no_payments else R.string.no_results), "") }
            items(payments, key = { it.id }) { payment ->
                val service = services.getValue(payment.serviceId)
                LedgerCard(onClick = { onService(service) },
                    kind = if (payment.voidedAtMillis == null) LedgerCardKind.Receipt else LedgerCardKind.Voided) {
                    Text(customers.getValue(service.customerId).name, style = MaterialTheme.typography.titleMedium)
                    Text(service.work, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    PaymentEntry(payment)
                }
            }
        } else {
            if (pending.isEmpty()) item {
                EmptyState(stringResource(when {
                    query.isNotBlank() -> R.string.no_results
                    overdueOnly -> R.string.no_overdue
                    else -> R.string.no_pending
                }), stringResource(if (overdueOnly) R.string.no_overdue_hint else R.string.no_pending_hint))
            }
            items(pending, key = { it.id }) { ServiceRow(data, it, today, true) { onService(it) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun ServiceScreen(
    data: LedgerSnapshot, customer: Customer, service: ServiceVisit, today: Long,
    onEdit: () -> Unit, onDelete: () -> Unit, onPay: () -> Unit, onShare: () -> Unit,
    onRemind: () -> Unit, onVoid: (Payment) -> Unit,
) {
    val payments = data.payments.filter { it.serviceId == service.id }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            LedgerCard(kind = LedgerCardKind.Service) {
                Text(customer.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(service.work, style = MaterialTheme.typography.headlineSmall)
                Text(date(service.serviceDay), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(data.appliances.find { it.id == service.applianceId }?.name ?: stringResource(R.string.general_service))
                PaymentStatus(data, service, today)
                if (service.parts.isNotBlank()) Text("${stringResource(R.string.parts)}: ${service.parts}")
                service.photoId?.let { PhotoPreview(it) }
                DueLabel(service.nextDueDay)
                TextButton(onClick = onShare) { Text(stringResource(R.string.share_summary)) }
                EditDelete(onEdit, onDelete)
            }
        }
        item {
            LedgerCard(kind = LedgerCardKind.Balance) {
                PaymentSummary(service.amountPaise, data.received(service),
                    overdue = service.paymentDueDay?.let { it < today } == true)
                Text(service.paymentDueDay?.let { stringResource(R.string.payment_due_value, date(it)) }
                    ?: stringResource(R.string.no_payment_due), style = MaterialTheme.typography.bodySmall)
                if (data.pending(service) > 0) {
                    Button(onClick = onPay, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.record_payment)) }
                    OutlinedButton(onClick = onRemind, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.whatsapp_reminder)) }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.payment_history)) }
        if (payments.isEmpty()) item { EmptyState(stringResource(R.string.no_payments), stringResource(R.string.no_payments_hint)) }
        items(payments, key = { it.id }) { payment ->
            LedgerCard(kind = if (payment.voidedAtMillis == null) LedgerCardKind.Receipt else LedgerCardKind.Voided) {
                PaymentEntry(payment)
                if (payment.voidedAtMillis == null) TextButton(onClick = { onVoid(payment) }) { Text(stringResource(R.string.void_payment)) }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
private fun PaymentEntry(payment: Payment) {
    Text(formatMoney(payment.amountPaise), style = MaterialTheme.typography.titleLarge,
        color = LocalContentColor.current)
    Text(paymentMethodLabel(payment.method), style = MaterialTheme.typography.labelLarge)
    payment.paymentDay?.let { Text(date(it)) }
    if (payment.method == "OPENING") Text(stringResource(R.string.opening_hint), style = MaterialTheme.typography.bodySmall)
    if (payment.reference.isNotBlank()) Text(payment.reference)
    if (payment.notes.isNotBlank()) Text(payment.notes)
    if (payment.voidedAtMillis != null) {
        Text(stringResource(R.string.voided), fontWeight = FontWeight.Bold)
        Text(payment.voidReason)
    }
}

@Composable
internal fun paymentMethodLabel(method: String): String = stringResource(when (method) {
    "CASH" -> R.string.method_cash
    "UPI" -> R.string.method_upi
    "BANK" -> R.string.method_bank
    "CARD" -> R.string.method_card
    "OPENING" -> R.string.method_opening
    else -> R.string.method_other
})
