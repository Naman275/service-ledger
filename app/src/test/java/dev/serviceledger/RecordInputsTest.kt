package dev.serviceledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.serviceledger.ui.CalendarDates
import dev.serviceledger.ui.CalendarInput
import dev.serviceledger.ui.PaymentInputSummary
import dev.serviceledger.ui.formatMoney
import dev.serviceledger.ui.serviceSummary
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RecordInputsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `100 charge and 50 received displays 50 pending`() {
        compose.setContent { MaterialTheme { PaymentInputSummary("100", "50") } }
        compose.onNodeWithText("Total charge: ${formatMoney(10_000)}").assertIsDisplayed()
        compose.onNodeWithText("Received: ${formatMoney(5_000)}").assertIsDisplayed()
        compose.onNodeWithText("Pending: ${formatMoney(5_000)}").assertIsDisplayed()
    }

    @Test fun `overpayment displays validation rather than negative pending`() {
        compose.setContent { MaterialTheme { PaymentInputSummary("100", "150") } }
        compose.onNodeWithText("Amount received must be between zero and the total charge.").assertIsDisplayed()
    }

    @Test fun `shared summary contains received and pending amounts`() {
        val data = sample()
        val visit = data.visits.single().copy(amountPaise = 10_000)
        val text = serviceSummary(ApplicationProvider.getApplicationContext(), data.customers.single(), data.appliances.single(), visit, 5_000)
        assertTrue(text.contains("Received: ${formatMoney(5_000)}"))
        assertTrue(text.contains("Pending: ${formatMoney(5_000)}"))
    }

    @Test fun `calendar selects and clears optional date without typing`() {
        var selected: Long? = LocalDate.of(2026, 10, 13).toEpochDay()
        compose.setContent {
            var day by remember { mutableStateOf(selected) }
            MaterialTheme {
                CalendarInput(R.string.next_due, day, enabled = true, optional = true) { day = it; selected = it }
            }
        }
        compose.onNodeWithText("Next service (optional)").performClick()
        compose.onNodeWithText("Thursday, October 15, 2026", substring = true).performClick()
        compose.onNodeWithText("Use date").performClick()
        assertEquals(LocalDate.of(2026, 10, 15).toEpochDay(), selected)
        compose.onNodeWithText("Clear date").performClick()
        assertNull(selected)
        compose.onNodeWithText("Tap to select a date").assertIsDisplayed()
    }

    @Test fun `calendar timestamps are timezone independent including dates before epoch`() {
        val oldZone = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Kolkata", "America/Los_Angeles", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (date in listOf("1900-01-01", "2024-02-29", "2026-10-13", "2200-12-31")) {
                    val day = LocalDate.parse(date).toEpochDay()
                    assertEquals(day, CalendarDates.fromMillis(CalendarDates.toMillis(day)))
                }
            }
        } finally {
            TimeZone.setDefault(oldZone)
        }
    }
}
