package dev.serviceledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun clearData() {
        val application = compose.activity.application as LedgerApplication
        application.database.clearAllTables()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Add customer").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Customers").performClick()
    }

    @Test fun customerApplianceServiceAndFollowUpFlow() {
        compose.onNodeWithText("Add customer").performClick()
        compose.onNodeWithText("Customer name *").performTextInput("Pilot Customer")
        compose.onNodeWithText("Phone (optional)").performTextInput("9876543210")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Customer name *").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Pilot Customer").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Pilot Customer").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Pilot Customer").performClick()
        compose.onNodeWithText("Add appliance").performScrollTo().performClick()
        compose.onNodeWithText("Appliance name, e.g. Kitchen RO *").performTextInput("Kitchen RO")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Appliance name, e.g. Kitchen RO *").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Kitchen RO").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Kitchen RO").performClick()
        compose.onNodeWithText("Record service").performClick()
        compose.onNodeWithText("Service date *").performClick()
        compose.onNodeWithText("Use date").performClick()
        compose.onNodeWithText("Work performed *").performTextInput("Changed filter")
        compose.onNodeWithText("Charge in INR *").performScrollTo().performTextReplacement("450.50")
        compose.onNodeWithText("Next service (optional)").performScrollTo().performClick()
        compose.onNodeWithText(
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)), substring = true,
        ).performClick()
        compose.onNodeWithText("Use date").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Work performed *").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Changed filter").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Changed filter").performClick()
        compose.onNodeWithText("Record payment").performScrollTo().performClick()
        compose.onNodeWithText("Payment amount in INR *").performTextInput("200.00")
        compose.onNode(hasText("Record payment") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Payment amount in INR *").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Follow-ups").performClick()
        compose.onNodeWithText("Share reminder").assertIsDisplayed()
        compose.onNodeWithText("Pilot Customer").assertIsDisplayed()
    }

    @Test fun blankCustomerShowsValidationWithoutSaving() {
        compose.onNodeWithText("Add customer").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Customer name is required.").assertIsDisplayed()
    }
}
