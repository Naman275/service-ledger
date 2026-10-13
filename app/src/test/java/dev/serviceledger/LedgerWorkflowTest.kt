package dev.serviceledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Intent
import java.io.File
import androidx.lifecycle.ViewModelProvider
import dev.serviceledger.data.LedgerSnapshot
import dev.serviceledger.data.Customer
import dev.serviceledger.data.ServiceVisit
import dev.serviceledger.data.Payment
import java.time.LocalDate
import java.util.UUID
import dev.serviceledger.ui.formatMoney
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LedgerWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun clearRecords() {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[LedgerViewModel::class.java] }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Add customer").fetchSemanticsNodes().isNotEmpty() && vm.snapshot != null && !vm.busy
        }
        compose.runOnIdle { vm.mutate("Ledger reset.") { restore(LedgerSnapshot()) } }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Add customer").fetchSemanticsNodes().isNotEmpty() &&
                !vm.busy && vm.snapshot?.customers?.isEmpty() == true
        }
        compose.mainClock.advanceTimeBy(5_000)
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun capture(name: String) {
        val destination = System.getenv("LEDGER_SCREENSHOTS") ?: return
        val directory = File(destination)
        check(directory.isDirectory || directory.mkdirs())
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(image))
                File(directory, "$name.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                image.recycle()
            }
        }
    }

    @Test fun `customer payments and corrections`() = exercisePayments("")

    @Test fun `privacy policy opens published page`() {
        button("More").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Read privacy policy"))
        button("Read privacy policy").performClick()
        compose.runOnIdle {
            val intent = shadowOf(compose.activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://naman275.github.io/service-ledger/", intent.dataString)
            assertEquals("dev.serviceledger.app.debug", compose.activity.packageName)
        }
    }

    private fun loadStoreDemo() {
        val today = LocalDate.now().toEpochDay()
        val customers = listOf("Asha Mehta", "Dev Patel", "Neha Rao").map {
            Customer(UUID.randomUUID().toString(), it, "", "")
        }
        val services = listOf(
            ServiceVisit(UUID.randomUUID().toString(), customers[0].id, null, today,
                "AC maintenance", "", 180_000, today + 90, paymentDueDay = today + 2),
            ServiceVisit(UUID.randomUUID().toString(), customers[1].id, null, today - 3,
                "Water purifier service", "", 150_000, today + 60, paymentDueDay = today - 1),
            ServiceVisit(UUID.randomUUID().toString(), customers[2].id, null, today - 5,
                "Washing machine repair", "", 90_000, null),
        )
        val payments = listOf(
            Payment(UUID.randomUUID().toString(), services[0].id, 100_000, today, "UPI",
                notes = "Advance received", recordedAtMillis = today * 86_400_000),
            Payment(UUID.randomUUID().toString(), services[2].id, 90_000, today - 5, "CASH",
                notes = "Paid in full", recordedAtMillis = (today - 5) * 86_400_000),
        )
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[LedgerViewModel::class.java] }
        compose.runOnIdle {
            vm.mutate("Demo loaded.") { restore(LedgerSnapshot(customers = customers, visits = services, payments = payments)) }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(formatMoney(230_000)).fetchSemanticsNodes().isNotEmpty() && !vm.busy
        }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle {
            assertEquals(1512, compose.activity.window.decorView.width)
            assertEquals(2688, compose.activity.window.decorView.height)
        }
    }

    @Test
    @Config(qualifiers = "w504dp-h896dp-xxhdpi")
    fun `store listing screenshots`() {
        loadStoreDemo()
        capture("01-home")
        button("Customers").performClick()
        compose.onNodeWithText("Asha Mehta").assertIsDisplayed()
        capture("02-customers")
        compose.onNodeWithText("Asha Mehta").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("AC maintenance"))
        compose.onNodeWithText("AC maintenance").performClick()
        compose.onNodeWithText("Part paid").assertIsDisplayed()
        capture("03-service")
        button("Payments").performClick()
        compose.onNodeWithText("Water purifier service").assertIsDisplayed()
        capture("04-payments")
        button("History").performClick()
        compose.onNodeWithText("Advance received").performScrollTo().assertIsDisplayed()
        capture("05-receipts")
    }

    @Test
    @Config(qualifiers = "w504dp-h896dp-night-xxhdpi")
    fun `store listing dark screenshot`() {
        loadStoreDemo()
        capture("06-home-dark")
    }

    private fun exercisePayments(suffix: String) {
        capture("home$suffix")
        button("Customers").performClick()
        button("Add customer").performClick()
        compose.onNodeWithText("Customer name *").performTextInput("Friendly Customer")
        compose.onNodeWithText("Phone (optional)").performTextInput("+91 9876543210")
        button("Save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Customer name *").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Friendly Customer").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Friendly Customer").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Record service").fetchSemanticsNodes().isNotEmpty() }
        button("Record service").performClick()
        compose.onNodeWithText("Work performed *").performTextInput("Website maintenance")
        compose.onNodeWithText("Charge in INR *").performScrollTo().performTextReplacement("100.00")
        button("Save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Work performed *").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Website maintenance").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Website maintenance").performScrollTo().performClick()
        compose.onNodeWithText("General service").assertIsDisplayed()
        compose.onNodeWithText("Service", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Payment summary").performScrollTo().assertIsDisplayed()
        button("Record payment").performScrollTo().performClick()
        compose.onNodeWithText("Payment amount in INR *").performTextInput("50.00")
        compose.onNode(hasText("Record payment") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Payment amount in INR *").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Pending: ${formatMoney(5_000)}").performScrollTo().assertIsDisplayed()
        compose.mainClock.advanceTimeBy(5_000)
        capture("service$suffix")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Payment received"))
        compose.onNodeWithText("Payment received").assertIsDisplayed()
        capture("receipt$suffix")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Remind on WhatsApp"))
        button("Remind on WhatsApp").performScrollTo().performClick()
        compose.onNodeWithText("Hello Friendly Customer,", substring = true).assertIsDisplayed()
        button("Cancel").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Void payment"))
        button("Void payment").performScrollTo().performClick()
        compose.onNodeWithText("Reason for correction *").performTextInput("Incorrect entry")
        compose.onNode(hasText("Void payment") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reason for correction *").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Pending: ${formatMoney(10_000)}").performScrollTo().assertIsDisplayed()
        button("Payments").performClick()
        compose.onNodeWithText("Website maintenance").performScrollTo().assertIsDisplayed()
        capture("payments$suffix")
        button("History").performClick()
        compose.onNodeWithText("Voided").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Voided payment").performScrollTo().assertIsDisplayed()
        capture("voided-history$suffix")
    }

    @Test fun `unsaved customer edits require discard confirmation`() {
        button("Add customer").performClick()
        compose.onNodeWithText("Customer name *").performTextInput("Unsaved")
        button("Cancel").performClick()
        compose.onNodeWithText("Discard these changes?").assertIsDisplayed()
        button("Keep editing").performClick()
        compose.onNodeWithText("Unsaved").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night")
    fun `home supports dark mode`() {
        compose.onNodeWithText("To collect").assertIsDisplayed()
        button("View pending payments").assertIsDisplayed()
        capture("home-dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night")
    fun `payment card labels remain clear in dark mode`() {
        exercisePayments("-dark")
    }
}
