package dev.serviceledger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.serviceledger.R

internal enum class LedgerCardKind { Neutral, Service, Balance, Receipt, Voided }

@Immutable
internal data class LedgerCardColors(val container: Color, val content: Color, val accent: Color, val border: Color)

internal fun ledgerCardColors(kind: LedgerCardKind, dark: Boolean): LedgerCardColors = when (kind) {
    LedgerCardKind.Service -> if (dark)
        LedgerCardColors(Color(0xFF172B40), Color(0xFFE1EDFA), Color(0xFFA1CAEF), Color(0xFF365777))
    else LedgerCardColors(Color(0xFFEBF3FD), Color(0xFF163954), Color(0xFF245A84), Color(0xFFB9CFE6))
    LedgerCardKind.Balance -> if (dark)
        LedgerCardColors(Color(0xFF342919), Color(0xFFF5E6CD), Color(0xFFE7C17D), Color(0xFF6F5732))
    else LedgerCardColors(Color(0xFFFFF3DC), Color(0xFF513B17), Color(0xFF79531B), Color(0xFFE4CCA0))
    LedgerCardKind.Receipt -> if (dark)
        LedgerCardColors(Color(0xFF142F29), Color(0xFFD5F3E6), Color(0xFF8ED7B8), Color(0xFF355E50))
    else LedgerCardColors(Color(0xFFE4F4EC), Color(0xFF194636), Color(0xFF21664D), Color(0xFFAFD3C0))
    LedgerCardKind.Neutral, LedgerCardKind.Voided -> if (dark)
        LedgerCardColors(Color(0xFF1D2421), Color(0xFFE4ECE7), Color(0xFFB7C8BD), Color(0xFF3A4941))
    else LedgerCardColors(Color.White, Color(0xFF202724), Color(0xFF52605A), Color(0xFFD8E0DB))
}

@Composable
internal fun LedgerCard(
    onClick: (() -> Unit)? = null,
    kind: LedgerCardKind = LedgerCardKind.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ledgerCardColors(kind, isSystemInDarkTheme())
    val heading = when (kind) {
        LedgerCardKind.Service -> R.string.service_card
        LedgerCardKind.Balance -> R.string.payment_summary_card
        LedgerCardKind.Receipt -> R.string.receipt_card
        LedgerCardKind.Voided -> R.string.voided_payment_card
        LedgerCardKind.Neutral -> null
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (heading != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(when (kind) {
                        LedgerCardKind.Service -> Icons.Default.Build
                        LedgerCardKind.Receipt -> Icons.Default.CheckCircle
                        LedgerCardKind.Voided -> Icons.Default.Block
                        else -> Icons.Default.AccountBalanceWallet
                    }, null, Modifier.size(18.dp), tint = colors.accent)
                    Text(stringResource(heading), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                    if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.accent)
                }
            }
            content()
        }
    }
}
