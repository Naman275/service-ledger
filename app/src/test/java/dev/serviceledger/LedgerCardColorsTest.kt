package dev.serviceledger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.serviceledger.ui.LedgerCardKind
import dev.serviceledger.ui.ledgerCardColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCardColorsTest {
    @Test fun `card text and heading colors meet normal text contrast`() {
        for (dark in listOf(false, true)) {
            for (kind in LedgerCardKind.entries) {
                val colors = ledgerCardColors(kind, dark)
                for (foreground in listOf(colors.content, colors.accent)) {
                    val ratio = contrast(foreground, colors.container)
                    assertTrue("$kind (dark=$dark) contrast $ratio must be at least 4.5:1", ratio >= 4.5)
                }
            }
        }
    }

    @Test fun `shared secondary text and actions contrast with every card`() {
        for (dark in listOf(false, true)) {
            val secondaryText = if (dark) Color(0xFFBDCCC3) else Color(0xFF49454F)
            val action = if (dark) Color(0xFF83D5BD) else Color(0xFF126B60)
            for (kind in LedgerCardKind.entries) {
                val background = ledgerCardColors(kind, dark).container
                for (foreground in listOf(secondaryText, action)) {
                    assertTrue("$kind (dark=$dark) shared text contrast", contrast(foreground, background) >= 4.5)
                }
            }
        }
    }

    @Test fun `record types are distinct and voided receipts are neutral`() {
        for (dark in listOf(false, true)) {
            val distinct = listOf(LedgerCardKind.Neutral, LedgerCardKind.Service, LedgerCardKind.Balance, LedgerCardKind.Receipt)
                .map { ledgerCardColors(it, dark).container }.toSet()
            assertEquals(4, distinct.size)
            assertEquals(ledgerCardColors(LedgerCardKind.Neutral, dark), ledgerCardColors(LedgerCardKind.Voided, dark))
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
