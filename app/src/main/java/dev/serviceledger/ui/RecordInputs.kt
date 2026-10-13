package dev.serviceledger.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.serviceledger.LedgerApplication
import dev.serviceledger.R
import dev.serviceledger.data.LedgerRules
import java.io.IOException
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CalendarDates {
    fun toMillis(day: Long): Long = Math.multiplyExact(day, 86_400_000L)
    fun fromMillis(millis: Long): Long = Math.floorDiv(millis, 86_400_000L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarInput(
    label: Int, day: Long?, enabled: Boolean, optional: Boolean = false,
    minimumDay: Long? = null, onChange: (Long?) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CalendarMonth, null)
            Column(Modifier.padding(12.dp).weight(1f)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                Text(day?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd MMM yyyy")) }
                    ?: stringResource(R.string.select_date))
            }
        }
        if (optional && day != null) {
            TextButton(onClick = { onChange(null) }, enabled = enabled) { Text(stringResource(R.string.clear_date)) }
        }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day?.let(CalendarDates::toMillis),
            initialDisplayedMonthMillis = CalendarDates.toMillis(day ?: minimumDay ?: LocalDate.now().toEpochDay()),
            yearRange = 1900..2200,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    minimumDay == null || CalendarDates.fromMillis(utcTimeMillis) >= minimumDay
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = {
                    state.selectedDateMillis?.let { onChange(CalendarDates.fromMillis(it)) }
                    open = false
                }) { Text(stringResource(R.string.use_date)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
}

@Composable
internal fun PaymentSummary(total: Long, received: Long) {
    val pending = LedgerRules.pendingAmount(total, received)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.total_charge_value, formatMoney(total)))
        Text(stringResource(R.string.received_value, formatMoney(received)))
        Text(stringResource(R.string.pending_value, formatMoney(pending)),
            color = if (pending > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun PaymentInputSummary(total: String, received: String) {
    val amounts = try {
        val charge = LedgerRules.parseAmount(total)
        val paid = LedgerRules.parseAmount(received)
        LedgerRules.pendingAmount(charge, paid)
        charge to paid
    } catch (error: IllegalArgumentException) {
        Text(error.message ?: stringResource(R.string.check_amounts), color = MaterialTheme.colorScheme.error)
        null
    }
    amounts?.let { PaymentSummary(it.first, it.second) }
}

@Composable
internal fun PhotoPreview(id: String) {
    val app = LocalContext.current.applicationContext as LedgerApplication
    var image by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        try {
            image = withContext(Dispatchers.IO) {
                val bytes = app.photos.read(id)
                (BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("The saved photo cannot be displayed.")).asImageBitmap()
            }
        } catch (failure: IOException) {
            error = failure.message
        } catch (failure: IllegalArgumentException) {
            error = failure.message
        }
    }
    image?.let {
        Image(it, stringResource(R.string.attached_photo), Modifier.fillMaxWidth().heightIn(max = 220.dp),
            contentScale = ContentScale.Fit)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun PhotoInput(id: String?, enabled: Boolean, onChoose: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.optional_photo), style = MaterialTheme.typography.titleMedium)
        id?.let { PhotoPreview(it) }
        Row {
            OutlinedButton(onClick = onChoose, enabled = enabled) {
                Text(stringResource(if (id == null) R.string.add_photo else R.string.replace_photo))
            }
            if (id != null) TextButton(onClick = onRemove, enabled = enabled) { Text(stringResource(R.string.remove_photo)) }
        }
        Text(stringResource(R.string.photo_hint), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun formatMoney(paise: Long): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(BigDecimal.valueOf(paise, 2))
