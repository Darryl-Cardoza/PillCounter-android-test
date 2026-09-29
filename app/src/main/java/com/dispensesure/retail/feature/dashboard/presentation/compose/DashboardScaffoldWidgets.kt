package com.dispensesure.retail.feature.dashboard.presentation.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.scanning.domain.model.BottleInfoJson
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.MenuButton
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.core.utils.compose.DrugCountRow
import com.dispensesure.retail.core.utils.compose.DrugCountRowData
import com.dispensesure.retail.feature.dashboard.domain.model.DashboardTab
import com.dispensesure.retail.feature.dashboard.domain.model.DashboardUiState
import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem
import com.dispensesure.retail.feature.dashboard.presentation.model.upNextDispense
import com.dispensesure.retail.feature.history.presentation.compose.BatchHistoryRow
import com.dispensesure.retail.ui.theme.AppTheme.extendedColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shared scaffold widgets for the new dashboard. DashboardLandscapeLayout and
// DashboardPortraitLayout consume these so the "same data, different placement"
// contract holds without per-layout duplication.

internal fun buildTerminalUserLine(
    uiState: DashboardUiState,
    includeTerminal: Boolean = true,
): String {
    val profile = uiState.userDetail?.profile
    // Ownership comes from DashboardUiState.selectedTerminalName (resolved via device_key),
    // not from a scan of the account-wide terminals list — see that field's docs.
    val activeTerminalName = uiState.selectedTerminalName?.takeIf { it.isNotBlank() }
    // Terminal is HL7/PMS-driven — omit it from the top bar when HL7 is disabled.
    val terminal = if (includeTerminal) activeTerminalName else null
    // The face user who verified into this session wins over the account name — same rule
    // HL7 uses for its operator. Falls back to the account only before the first emission.
    val user = uiState.operatorName?.ifBlank { null }
        ?: listOfNotNull(profile?.fName, profile?.lName).joinToString(" ").ifBlank { null }
    return listOfNotNull(terminal, user).joinToString(" | ").ifBlank { "—" }
}

// Status/nav bars plus the notch. The top bar takes the top edge, the content area the rest.
internal val DashboardSafeInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

@Composable
internal fun ScaffoldTopBar(
    pharmacyName: String?,
    terminalAndUserLine: String,
    showPmsDot: Boolean,
    isPmsConnected: Boolean,
    navController: NavController,
    compact: Boolean = false,
) {
    val horizontalPadding = if (compact) 12.dp else 16.dp
    // Phone portrait is too narrow for both lines side by side, so stack them.
    val stackNameAndUser = compact && !UserInterfaceUtils.isLandscape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(extendedColors.secondaryBackground)
            // Background paints behind the status bar / notch; only the content is inset.
            .windowInsetsPadding(
                DashboardSafeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stackNameAndUser) {
            Column(modifier = Modifier.weight(1f)) {
                PharmacyNameText(pharmacyName = pharmacyName)
                Spacer(modifier = Modifier.height(responsiveDp(2.dp)))
                TerminalAndUserText(terminalAndUserLine = terminalAndUserLine)
            }
        } else {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PharmacyNameText(pharmacyName = pharmacyName)
                Spacer(modifier = Modifier.width(if (compact) 8.dp else 14.dp))
                TerminalAndUserText(terminalAndUserLine = terminalAndUserLine)
            }
        }
        if (showPmsDot) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (isPmsConnected) MaterialTheme.colorScheme.secondary else Color.Gray,
                            shape = RoundedCornerShape(50),
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.pms),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
        }
        MenuButton(navController = navController)
    }
}

@Composable
private fun PharmacyNameText(pharmacyName: String?) {
    Text(
        text = pharmacyName ?: "—",
        fontSize = responsiveSp(12.sp, boostOnPhone = true),
        color = extendedColors.textColor,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TerminalAndUserText(terminalAndUserLine: String) {
    Text(
        text = terminalAndUserLine,
        fontSize = responsiveSp(8.sp),
        fontWeight = FontWeight.Light,
        color = extendedColors.textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun ScaffoldQuickActionCard(
    title: String,
    subtitle: String,
    @DrawableRes innerIconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val cardShape = RoundedCornerShape(DashboardBaseSizes.QuickActionCardCornerRadius)
    val innerPadding = responsiveDp(DashboardBaseSizes.CardInnerPadding)
    val iconToTextGap = responsiveDp(DashboardBaseSizes.IconToTextGap)
    val titleToSubtitleGap = responsiveDp(DashboardBaseSizes.TitleToSubtitleGap)
    val titleSize = responsiveSp(DashboardBaseSizes.QuickActionTitleText)
    val subtitleSize = responsiveSp(DashboardBaseSizes.QuickActionSubtitleText)
    Card(
        modifier = modifier
            .clickable { onClick() },
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = extendedColors.secondaryBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        if (centered) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                QuickActionRingIcon(
                    innerIconRes = innerIconRes,
                    contentDescription = title,
                )
                Spacer(modifier = Modifier.height(iconToTextGap))
                Text(
                    text = title,
                    fontSize = titleSize,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(titleToSubtitleGap))
                Text(
                    text = subtitle,
                    fontSize = subtitleSize,
                    color = extendedColors.textColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickActionRingIcon(
                    innerIconRes = innerIconRes,
                    contentDescription = title,
                )
                Spacer(modifier = Modifier.width(iconToTextGap))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = titleSize,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        fontSize = subtitleSize,
                        color = extendedColors.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScaffoldTabStrip(
    activeTab: DashboardTab,
    dispenseCount: Int,
    inventoryCount: Int,
    onSelect: (DashboardTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        ScaffoldTab(
            label = stringResource(R.string.tab_label_with_count, stringResource(R.string.dispense_queue), dispenseCount),
            isActive = activeTab == DashboardTab.DISPENSE_QUEUE,
            onClick = { onSelect(DashboardTab.DISPENSE_QUEUE) },
            modifier = Modifier.weight(1f),
        )
        ScaffoldTab(
            label = stringResource(R.string.tab_label_with_count, stringResource(R.string.inventory_queue), inventoryCount),
            isActive = activeTab == DashboardTab.INVENTORY_QUEUE,
            onClick = { onSelect(DashboardTab.INVENTORY_QUEUE) },
            modifier = Modifier.weight(1f),
        )
        ScaffoldTab(
            label = stringResource(R.string.recent_activity),
            isActive = activeTab == DashboardTab.RECENT_ACTIVITY,
            onClick = { onSelect(DashboardTab.RECENT_ACTIVITY) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScaffoldTab(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = responsiveSp(
                if (UserInterfaceUtils.isTablet()) 8.sp else 10.sp,
                boostOnPhone = false,
            ),
            color = if (isActive) MaterialTheme.colorScheme.secondary else extendedColors.textColor,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .fillMaxWidth()
                .background(if (isActive) MaterialTheme.colorScheme.secondary else Color.Transparent)
        )
    }
}

@Composable
internal fun ScaffoldQueueList(
    items: List<QueueItem>,
    onDispenseClick: ((Long) -> Unit)?,
    onInventoryClick: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
    showUpNextCard: Boolean = false,
    isLoading: Boolean = false,
) {
    // Still loading: the loading overlay is up, so don't claim the queue is empty yet.
    if (items.isEmpty() && isLoading) return
    if (items.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.complete),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.all_caught_up),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.no_pending_tasks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extendedColors.textColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    val upNext = if (showUpNextCard) upNextDispense(items) else null
    val remainingItems = if (upNext != null) items.drop(1) else items

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        if (upNext != null) {
            item(key = "up-next-${upNext.txn.txnId}") {
                DispenseUpNextCard(
                    item = upNext,
                    onClick = { onDispenseClick?.invoke(upNext.txn.txnId) },
                )
            }
        }
        items(remainingItems, key = { item ->
            when (item) {
                is QueueItem.Dispense -> "d-${item.txn.txnId}"
                is QueueItem.Inventory -> "i-${item.batch.batchId}"
            }
        }) { item ->
            when (item) {
                is QueueItem.Dispense -> DrugCountRow(
                    data = item.toDrugCountRowData(),
                    onClick = { onDispenseClick?.invoke(item.txn.txnId) },
                )

                is QueueItem.Inventory -> BatchHistoryRow(
                    title = item.batch.batchId.toString(),
                    dateTime = formatDate(item.batch.createdAt),
                    bucketId = item.batch.bucketId,
                    count = item.batch.uniqueNdcCount.toString(),
                    isPrescription = item.batch.requestIdFromPMS != null,
                    onBatchClick = { onInventoryClick?.invoke(item.batch.batchId) },
                )
            }
        }
    }
}

// One mapping from a queued dispense to the row data, shared by the list row and the UP NEXT card.
internal fun QueueItem.Dispense.toDrugCountRowData() = DrugCountRowData(
    barcodeImage = BottleInfoJson.decode(txn.bottleInfoListJson).firstOrNull()?.barcodeImagePath,
    ndc = txn.ndc,
    drugType = txn.drugType,
    drugName = txn.drugName ?: "—",
    date = formatDate(txn.createdAt),
    bucketId = txn.bucketId,
    pillCount = txn.totalPillCount,
    targetCount = txn.targetCount ?: 0,
    isDispense = txn.isDispense,
    strength = txn.strength,
    dosageForm = txn.dosageForm,
    drugImagePath = txn.drugImagePath,
    rxNo = txn.rxNo,
    refillNo = txn.refillNo,
)

@Composable
private fun QuickActionRingIcon(
    @DrawableRes innerIconRes: Int,
    contentDescription: String?,
) {
    Box(
        modifier = Modifier
            .size(responsiveDp(DashboardBaseSizes.QuickActionRingSize))
            .border(
                width = DashboardBaseSizes.RingBorderWidth,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = innerIconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(responsiveDp(DashboardBaseSizes.QuickActionIconSize)),
        )
    }
}

private val DATE_FMT = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())

internal fun formatDate(epochMillis: Long): String = DATE_FMT.format(Date(epochMillis))
