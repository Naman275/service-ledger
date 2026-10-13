package dev.serviceledger.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.serviceledger.LedgerViewModel
import dev.serviceledger.R
import dev.serviceledger.data.Appliance
import dev.serviceledger.data.Customer
import dev.serviceledger.data.LedgerRules
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.data.ServiceVisit
import dev.serviceledger.notifications.DueReminders
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay

private val ledgerColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F3E9),
    secondaryContainer = Color(0xFFE8F0EB),
    background = Color(0xFFF7FAF7),
    surface = Color(0xFFF7FAF7),
)

private data class EditRequest(val kind: String, val id: String?, val parentId: String?)
private data class DeleteRequest(val label: String, val warning: Int, val action: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerApp(startOnDue: Boolean = false, vm: LedgerViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(if (startOnDue) 0 else 1) }
    var customerId by rememberSaveable { mutableStateOf<String?>(null) }
    var applianceId by rememberSaveable { mutableStateOf<String?>(null) }
    var editKind by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var editParent by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<DeleteRequest?>(null) }
    var backupDialog by rememberSaveable { mutableStateOf(false) }
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var today by remember { mutableStateOf(LocalDate.now().toEpochDay()) }
    var notificationsAllowed by remember { mutableStateOf(DueReminders.allowed(context)) }

    fun openEditor(kind: String, id: String? = null, parent: String? = null) {
        vm.clearError()
        editId = id
        editParent = parent
        editKind = kind
    }
    fun closeEditor() { editKind = null; vm.clearError() }
    fun share(text: String) {
        try {
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                context.getString(R.string.share_title),
            ))
        } catch (_: ActivityNotFoundException) {
            vm.report("No app is available to share this text.")
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            if (backupPassword.length < 12) {
                vm.report("The password was cleared while selecting a file. Try again; the destination may be empty.")
            } else {
                vm.backup(uri, backupPassword.toCharArray()) {
                    backupDialog = false
                    backupPassword = ""
                    confirmation = ""
                }
            }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.clearError(); restoreUri = uri.toString() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        notificationsAllowed = DueReminders.allowed(context)
        if (allowed && notificationsAllowed) vm.updateReminders(true)
        else vm.report("Notifications were not enabled. You can still use the follow-up list.")
    }

    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        while (true) { today = LocalDate.now().toEpochDay(); delay(60_000) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                today = LocalDate.now().toEpochDay()
                notificationsAllowed = DueReminders.allowed(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = customerId != null || applianceId != null) {
        if (applianceId != null) applianceId = null else customerId = null
    }

    val data = vm.snapshot
    val customer = data?.customers?.find { it.id == customerId }
    val appliance = data?.appliances?.find { it.id == applianceId }

    MaterialTheme(colorScheme = ledgerColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(appliance?.name ?: customer?.name ?: stringResource(R.string.app_name), maxLines = 1)
                            if (customer == null && appliance == null) {
                                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    navigationIcon = {
                        if (customerId != null || applianceId != null) {
                            IconButton(onClick = { if (applianceId != null) applianceId = null else customerId = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf(R.string.due, R.string.customers, R.string.backup).forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index; customerId = null; applianceId = null },
                            enabled = !vm.busy,
                            icon = {
                                Icon(
                                    when (index) { 0 -> Icons.Default.Schedule; 1 -> Icons.Default.Groups; else -> Icons.Default.CloudUpload },
                                    null,
                                )
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (data != null && !vm.loadError && tab == 1 && !vm.busy) {
                    FloatingActionButton(onClick = {
                        when {
                            appliance != null -> openEditor("visit", parent = appliance.id)
                            customer != null -> openEditor("appliance", parent = customer.id)
                            else -> openEditor("customer")
                        }
                    }) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null)
                            Text(stringResource(when {
                                appliance != null -> R.string.add_visit
                                customer != null -> R.string.add_appliance
                                else -> R.string.add_customer
                            }))
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (editKind == null && !backupDialog && restoreUri == null) {
                    vm.error?.let { ErrorBanner(it) }
                }
                when {
                    vm.loadError -> EmptyState(stringResource(R.string.load_failed), vm.error.orEmpty())
                    data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.loading), Modifier.padding(16.dp))
                        }
                    }
                    tab == 2 -> BackupScreen(vm, notificationsAllowed,
                        onBackup = { vm.clearError(); backupDialog = true },
                        onRestore = {
                            try { restore.launch(arrayOf("*/*")) }
                            catch (_: ActivityNotFoundException) { vm.report("No file picker is available on this device.") }
                        },
                        onReminders = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33 && !DueReminders.allowed(context)) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else vm.updateReminders(enabled)
                        },
                        onSettings = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            } catch (_: ActivityNotFoundException) {
                                vm.report("Open this app's notification permissions in Android settings.")
                            }
                        },
                    )
                    tab == 0 -> FollowUps(data, today,
                        onOpen = { target -> customerId = target.customerId; applianceId = target.id; tab = 1 },
                        onShare = { target ->
                            val owner = data.customers.first { it.id == target.customerId }
                            share(context.getString(R.string.reminder_message, owner.name, target.name, date(data.nextDue(target))))
                        },
                    )
                    appliance != null && customer != null -> ApplianceScreen(data, customer, appliance,
                        onEdit = { openEditor("appliance", appliance.id, customer.id) },
                        onDelete = {
                            deleting = DeleteRequest(appliance.name, R.string.delete_appliance_warning) {
                                vm.mutate("Appliance deleted.", { applianceId = null; deleting = null }) { deleteAppliance(appliance.id) }
                            }
                        },
                        onEditVisit = { openEditor("visit", it.id, appliance.id) },
                        onDeleteVisit = { visit ->
                            deleting = DeleteRequest(date(visit.serviceDay), R.string.delete_visit_warning) {
                                vm.mutate("Service deleted.", { deleting = null }) { deleteVisit(visit.id) }
                            }
                        },
                        onShare = { visit -> share(serviceSummary(context, customer, appliance, visit)) },
                    )
                    customer != null -> CustomerScreen(data, customer,
                        onOpen = { applianceId = it.id },
                        onEdit = { openEditor("customer", customer.id) },
                        onDelete = {
                            deleting = DeleteRequest(customer.name, R.string.delete_customer_warning) {
                                vm.mutate("Customer deleted.", { customerId = null; deleting = null }) { deleteCustomer(customer.id) }
                            }
                        },
                    )
                    else -> CustomerList(data) { customerId = it.id }
                }
            }
        }

        editKind?.let { kind ->
            key(kind, editId, editParent) {
                RecordForm(EditRequest(kind, editId, editParent), data ?: LedgerSnapshot(), vm, ::closeEditor)
            }
        }
        deleting?.let { request ->
            AlertDialog(
                onDismissRequest = { if (!vm.busy) deleting = null },
                title = { Text(stringResource(R.string.delete_title, request.label)) },
                text = {
                    Column {
                        Text(stringResource(request.warning))
                        vm.error?.let { ErrorBanner(it) }
                    }
                },
                confirmButton = { TextButton(onClick = request.action, enabled = !vm.busy) { Text(stringResource(R.string.delete)) } },
                dismissButton = { TextButton(onClick = { deleting = null; vm.clearError() }, enabled = !vm.busy) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (backupDialog) {
            FormDialog(stringResource(R.string.create_backup), vm.busy, vm.error,
                onDismiss = { backupDialog = false; backupPassword = ""; confirmation = ""; vm.clearError() },
                action = stringResource(R.string.choose_destination),
                onSave = {
                    when {
                        backupPassword.length < 12 -> vm.report("Use a backup password of at least 12 characters.")
                        backupPassword != confirmation -> vm.report("The passwords do not match.")
                        else -> {
                            vm.clearError()
                            try { export.launch("service-ledger-${LocalDate.now()}.slbackup") }
                            catch (_: ActivityNotFoundException) { vm.report("No file picker is available on this device.") }
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.password_warning))
                PasswordField(R.string.password, backupPassword) { backupPassword = it }
                PasswordField(R.string.confirm_password, confirmation) { confirmation = it }
            }
        }
        restoreUri?.let { uri ->
            var password by remember { mutableStateOf("") }
            var acknowledged by remember { mutableStateOf(false) }
            FormDialog(stringResource(R.string.restore_backup), vm.busy, vm.error,
                onDismiss = { restoreUri = null; password = ""; vm.clearError() },
                action = stringResource(R.string.restore_replace),
                onSave = {
                    if (!acknowledged) vm.report("Confirm that the current records may be replaced.")
                    else if (password.isEmpty()) vm.report("Enter the backup password.")
                    else vm.restore(Uri.parse(uri), password.toCharArray()) {
                        restoreUri = null
                        password = ""
                        customerId = null
                        applianceId = null
                    }
                },
            ) {
                Text(stringResource(R.string.restore_warning), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.password_restore_hint))
                PasswordField(R.string.password, password) { password = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, { acknowledged = it }, enabled = !vm.busy)
                    Text(stringResource(R.string.restore_acknowledgement))
                }
            }
        }
    }
}

@Composable
private fun CustomerList(data: LedgerSnapshot, onOpen: (Customer) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = data.customers.filter {
        query.isBlank() || listOf(it.name, it.phone, it.address).any { value -> value.contains(query.trim(), ignoreCase = true) }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_customers)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true)
        }
        if (data.customers.isEmpty()) item {
            EmptyState(stringResource(R.string.no_customers), stringResource(R.string.no_customers_hint))
        } else if (filtered.isEmpty()) item { EmptyState(stringResource(R.string.no_results), "") }
        items(filtered, key = { it.id }) { customer ->
            LedgerCard(onClick = { onOpen(customer) }) {
                Text(customer.name, style = MaterialTheme.typography.titleMedium)
                if (customer.phone.isNotBlank()) Text(customer.phone)
                if (customer.address.isNotBlank()) Text(customer.address, style = MaterialTheme.typography.bodySmall)
                Text("${data.appliances.count { it.customerId == customer.id }} ${stringResource(R.string.appliances).lowercase()}",
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun CustomerScreen(
    data: LedgerSnapshot, customer: Customer, onOpen: (Appliance) -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit,
) {
    val appliances = data.appliances.filter { it.customerId == customer.id }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard {
                Text(customer.name, style = MaterialTheme.typography.headlineSmall)
                if (customer.phone.isNotBlank()) Text(customer.phone)
                if (customer.address.isNotBlank()) Text(customer.address)
                EditDelete(onEdit, onDelete)
            }
        }
        item { SectionTitle(stringResource(R.string.appliances)) }
        if (appliances.isEmpty()) item { EmptyState(stringResource(R.string.no_appliances), "") }
        items(appliances, key = { it.id }) { appliance ->
            LedgerCard(onClick = { onOpen(appliance) }) {
                Text(appliance.name, style = MaterialTheme.typography.titleMedium)
                if (appliance.model.isNotBlank()) Text(appliance.model)
                DueLabel(data.nextDue(appliance))
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun ApplianceScreen(
    data: LedgerSnapshot, customer: Customer, appliance: Appliance,
    onEdit: () -> Unit, onDelete: () -> Unit,
    onEditVisit: (ServiceVisit) -> Unit, onDeleteVisit: (ServiceVisit) -> Unit,
    onShare: (ServiceVisit) -> Unit,
) {
    val visits = data.visits.filter { it.applianceId == appliance.id }
        .sortedWith(compareByDescending<ServiceVisit> { it.serviceDay }.thenByDescending { it.recordedAtMillis })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard {
                Text(customer.name, color = MaterialTheme.colorScheme.primary)
                Text(appliance.name, style = MaterialTheme.typography.headlineSmall)
                if (appliance.model.isNotBlank()) Text(appliance.model)
                if (appliance.serial.isNotBlank()) Text("${stringResource(R.string.serial)}: ${appliance.serial}")
                appliance.photoId?.let { PhotoPreview(it) }
                DueLabel(data.nextDue(appliance))
                EditDelete(onEdit, onDelete)
            }
        }
        item { SectionTitle(stringResource(R.string.service_history)) }
        if (visits.isEmpty()) item { EmptyState(stringResource(R.string.no_visits), "") }
        items(visits, key = { it.id }) { visit ->
            LedgerCard {
                Text(date(visit.serviceDay), style = MaterialTheme.typography.titleMedium)
                Text(visit.work)
                if (visit.parts.isNotBlank()) Text("${stringResource(R.string.parts)}: ${visit.parts}")
                PaymentSummary(visit.amountPaise, visit.receivedPaise)
                visit.photoId?.let { PhotoPreview(it) }
                DueLabel(visit.nextDueDay)
                TextButton(onClick = { onShare(visit) }) { Text(stringResource(R.string.share_summary)) }
                EditDelete({ onEditVisit(visit) }, { onDeleteVisit(visit) })
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun FollowUps(data: LedgerSnapshot, today: Long, onOpen: (Appliance) -> Unit, onShare: (Appliance) -> Unit) {
    val due = data.dueOnOrBefore(today)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard {
                Text(stringResource(R.string.local_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.follow_up_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.follow_up_hint))
                Text(stringResource(R.string.stats, data.customers.size, data.appliances.size, due.size),
                    style = MaterialTheme.typography.labelLarge)
            }
        }
        if (due.isEmpty()) item { EmptyState(stringResource(R.string.all_caught_up), stringResource(R.string.all_caught_up_hint)) }
        items(due, key = { it.id }) { appliance ->
            val owner = data.customers.first { it.id == appliance.customerId }
            LedgerCard(onClick = { onOpen(appliance) }) {
                Text(owner.name, style = MaterialTheme.typography.titleMedium)
                Text(appliance.name)
                DueLabel(data.nextDue(appliance))
                OutlinedButton(onClick = { onShare(appliance) }) { Text(stringResource(R.string.share_reminder)) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BackupScreen(
    vm: LedgerViewModel, notificationsAllowed: Boolean, onBackup: () -> Unit, onRestore: () -> Unit,
    onReminders: (Boolean) -> Unit, onSettings: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard {
                Icon(Icons.Default.CloudUpload, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.backup_description))
                Text(if (vm.lastBackup == 0L) stringResource(R.string.never_backed_up)
                    else stringResource(R.string.last_backup, Instant.ofEpochMilli(vm.lastBackup).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))),
                    fontWeight = FontWeight.SemiBold)
                if (vm.lastBackup == 0L || System.currentTimeMillis() - vm.lastBackup > 7 * 86_400_000L) {
                    Text(stringResource(R.string.backup_overdue), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onBackup, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.create_backup)) }
                OutlinedButton(onClick = onRestore, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.restore_backup)) }
            }
        }
        item {
            LedgerCard {
                Text(stringResource(R.string.reminders_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.reminders_description))
                Switch(checked = vm.reminders, onCheckedChange = onReminders, enabled = !vm.busy)
                if (!notificationsAllowed) {
                    Text(stringResource(R.string.notification_blocked))
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.open_notification_settings)) }
                }
            }
        }
        item {
            LedgerCard {
                Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.privacy_description))
                HorizontalDivider()
                Text(stringResource(R.string.pilot_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecordForm(request: EditRequest, data: LedgerSnapshot, vm: LedgerViewModel, onDismiss: () -> Unit) {
    val customer = data.customers.find { it.id == request.id }
    val appliance = data.appliances.find { it.id == request.id }
    val visit = data.visits.find { it.id == request.id }
    val id = rememberSaveable { request.id ?: UUID.randomUUID().toString() }
    val recordedAt = rememberSaveable { visit?.recordedAtMillis ?: System.currentTimeMillis() }
    var name by rememberSaveable { mutableStateOf(customer?.name ?: appliance?.name ?: "") }
    var phone by rememberSaveable { mutableStateOf(customer?.phone ?: "") }
    var address by rememberSaveable { mutableStateOf(customer?.address ?: "") }
    var model by rememberSaveable { mutableStateOf(appliance?.model ?: "") }
    var serial by rememberSaveable { mutableStateOf(appliance?.serial ?: "") }
    var serviceDay by rememberSaveable { mutableStateOf(visit?.serviceDay ?: LocalDate.now().toEpochDay()) }
    var due by rememberSaveable { mutableStateOf(if (request.kind == "appliance") appliance?.initialDueDay else visit?.nextDueDay) }
    var work by rememberSaveable { mutableStateOf(visit?.work ?: "") }
    var parts by rememberSaveable { mutableStateOf(visit?.parts ?: "") }
    var amount by rememberSaveable { mutableStateOf(visit?.amountPaise?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "0.00") }
    var received by rememberSaveable { mutableStateOf(visit?.receivedPaise?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "0.00") }
    var photoId by rememberSaveable { mutableStateOf(appliance?.photoId ?: visit?.photoId) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri == null) vm.report("The contact picker did not return a contact.")
            else vm.importContact(uri) { selected -> name = selected.name; phone = selected.phone }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importPhoto(uri) { photoId = it }
    }
    fun choosePhoto() {
        try { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: ActivityNotFoundException) { vm.report("No photo picker is available on this device.") }
    }
    val title = when (request.kind) {
        "customer" -> if (request.id == null) R.string.add_customer else R.string.edit_customer
        "appliance" -> if (request.id == null) R.string.add_appliance else R.string.edit_appliance
        else -> if (request.id == null) R.string.add_visit else R.string.edit_visit
    }
    FormDialog(stringResource(title), vm.busy, vm.error, onDismiss, stringResource(R.string.save), onSave = {
        try {
            when (request.kind) {
                "customer" -> {
                    val record = Customer(id, name.trim(), phone.trim(), address.trim())
                    LedgerRules.validate(record)
                    vm.mutate("Customer saved.", onDismiss) { save(record) }
                }
                "appliance" -> {
                    val record = Appliance(id, requireNotNull(request.parentId), name.trim(), model.trim(), serial.trim(), due, photoId)
                    LedgerRules.validate(record)
                    vm.mutate("Appliance saved.", onDismiss) { save(record) }
                }
                "visit" -> {
                    val record = ServiceVisit(id, requireNotNull(request.parentId), serviceDay,
                        work.trim(), parts.trim(), LedgerRules.parseAmount(amount), LedgerRules.parseAmount(received), due, recordedAt, photoId)
                    LedgerRules.validate(record)
                    vm.mutate("Service saved.", onDismiss) { save(record) }
                }
            }
        } catch (failure: IllegalArgumentException) {
            vm.report(failure.message ?: "Check the entered values.")
        }
    }) {
        when (request.kind) {
            "customer" -> {
                OutlinedButton(enabled = !vm.busy, onClick = {
                    try { contactPicker.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
                    catch (_: ActivityNotFoundException) { vm.report("No contacts picker is available. Enter the customer manually.") }
                }) { Text(stringResource(R.string.import_contact)) }
                Text(stringResource(R.string.import_contact_hint), style = MaterialTheme.typography.bodySmall)
                Input(R.string.name, name, enabled = !vm.busy) { name = it }
                Input(R.string.phone, phone, KeyboardType.Phone, enabled = !vm.busy) { phone = it }
                Input(R.string.address, address, multiline = true, enabled = !vm.busy) { address = it }
            }
            "appliance" -> {
                Input(R.string.appliance_name, name, enabled = !vm.busy) { name = it }
                Input(R.string.model, model, enabled = !vm.busy) { model = it }
                Input(R.string.serial, serial, enabled = !vm.busy) { serial = it }
                CalendarInput(R.string.initial_due, due, enabled = !vm.busy, optional = true) { due = it }
                Text(stringResource(R.string.initial_due_hint), style = MaterialTheme.typography.bodySmall)
                PhotoInput(photoId, !vm.busy, ::choosePhoto, { photoId = null })
            }
            else -> {
                CalendarInput(R.string.service_date, serviceDay, enabled = !vm.busy) { it?.let { selected -> serviceDay = selected } }
                Input(R.string.work, work, multiline = true, enabled = !vm.busy) { work = it }
                Input(R.string.parts, parts, multiline = true, enabled = !vm.busy) { parts = it }
                Input(R.string.amount, amount, KeyboardType.Decimal, enabled = !vm.busy) { amount = it }
                Input(R.string.received_amount, received, KeyboardType.Decimal, enabled = !vm.busy) { received = it }
                Text(stringResource(R.string.received_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { received = amount }, enabled = !vm.busy) { Text(stringResource(R.string.mark_paid)) }
                PaymentInputSummary(amount, received)
                CalendarInput(R.string.next_due, due, enabled = !vm.busy, optional = true, minimumDay = serviceDay) { due = it }
                Text(stringResource(R.string.next_due_hint), style = MaterialTheme.typography.bodySmall)
                PhotoInput(photoId, !vm.busy, ::choosePhoto, { photoId = null })
            }
        }
    }
}

@Composable
private fun FormDialog(
    title: String, busy: Boolean, error: String?, onDismiss: () -> Unit,
    action: String, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                error?.let { ErrorBanner(it) }
                if (busy) Text(stringResource(R.string.working))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = onSave, enabled = !busy) { Text(action) }
                }
            }
        }
    }
}

@Composable
private fun Input(
    label: Int, value: String, keyboard: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false, placeholder: String = "", enabled: Boolean = true, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        singleLine = !multiline, minLines = if (multiline) 2 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), enabled = enabled,
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
    )
}

@Composable
private fun PasswordField(label: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}

@Composable
private fun LedgerCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun EditDelete(onEdit: () -> Unit, onDelete: () -> Unit) {
    Row {
        TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DueLabel(day: Long?) {
    Text(if (day == null) stringResource(R.string.no_due_date) else stringResource(R.string.due_date, date(day)),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.AutoMirrored.Filled.ListAlt, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (description.isNotEmpty()) Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text(message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

private fun date(day: Long?): String = day?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd MMM yyyy")) } ?: "-"
internal fun serviceSummary(context: Context, customer: Customer, appliance: Appliance, visit: ServiceVisit): String =
    context.getString(R.string.summary_message, customer.name, appliance.name, date(visit.serviceDay), visit.work,
        visit.parts.ifBlank { "-" }, formatMoney(visit.amountPaise),
        formatMoney(visit.receivedPaise), formatMoney(LedgerRules.pendingAmount(visit.amountPaise, visit.receivedPaise)),
        visit.nextDueDay?.let(::date) ?: context.getString(R.string.no_due_date))
