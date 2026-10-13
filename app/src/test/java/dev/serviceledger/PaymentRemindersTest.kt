package dev.serviceledger

import androidx.test.core.app.ApplicationProvider
import dev.serviceledger.ui.PaymentReminders
import dev.serviceledger.ui.formatMoney
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaymentRemindersTest {
    @Test fun `international phone normalization requires an explicit country code`() {
        assertEquals("919876543210", PaymentReminders.phoneNumber("+91 (98765) 43210"))
        assertEquals("442079460123", PaymentReminders.phoneNumber("0044 20 7946 0123"))
        for (invalid in listOf("", "9876543210", "+", "+0123456789", "+91abc9876543210", "+91+9876543210", "+1234567890123456")) {
            assertThrows(IllegalArgumentException::class.java) { PaymentReminders.phoneNumber(invalid) }
        }
    }

    @Test fun `reminder uses unpaid balance and URI safely encodes the message`() {
        val original = sample()
        val service = original.visits.single().copy(amountPaise = 10_000, work = "Repair & inspection #1", paymentDueDay = 20_000)
        val data = original.copy(visits = listOf(service), payments = listOf(payment(service)))
        val message = PaymentReminders.message(ApplicationProvider.getApplicationContext(), data, data.customers.single(), service)
        assertTrue(message.contains("${formatMoney(5_000)} is pending"))
        assertTrue(message.contains("Received: ${formatMoney(5_000)}"))
        assertTrue(message.contains("Payment due:"))
        val uri = PaymentReminders.uri("+91 9876543210", message)
        assertEquals("https", uri.scheme)
        assertEquals("wa.me", uri.host)
        assertEquals("/919876543210", uri.path)
        assertEquals(message, uri.getQueryParameter("text"))
        assertNull(uri.fragment)
    }

    @Test fun `settled services cannot generate collection reminders`() {
        val original = sample()
        val service = original.visits.single()
        val data = original.copy(payments = listOf(payment(service, service.amountPaise)))
        assertThrows(IllegalArgumentException::class.java) {
            PaymentReminders.message(ApplicationProvider.getApplicationContext(), data, data.customers.single(), service)
        }
    }
}
