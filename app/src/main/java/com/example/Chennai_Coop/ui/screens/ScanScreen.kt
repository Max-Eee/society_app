package com.example.Chennai_Coop.ui.screens

import android.app.Activity
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.Chennai_Coop.data.models.BulkGroup
import com.example.Chennai_Coop.data.models.BulkScanBatch
import com.example.Chennai_Coop.ui.components.QRCodeScannerView
import com.example.Chennai_Coop.ui.viewmodel.ScanStatus
import com.example.Chennai_Coop.ui.viewmodel.ScanViewModel
import com.example.Chennai_Coop.utils.ThermalPrinterManager
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import java.text.SimpleDateFormat
import java.util.Locale

// --- HELPER: Format Date ---
private fun formatDisplayDate(dateString: String?): String {
    if (dateString.isNullOrEmpty()) return "N/A"
    val possibleInputFormats = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss.S",
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"
    )
    for (format in possibleInputFormats) {
        try {
            val inputFormatter = SimpleDateFormat(format, Locale.getDefault())
            inputFormatter.isLenient = false
            val date = inputFormatter.parse(dateString)
            if (date != null) return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(date)
        } catch (_: Exception) { }
    }
    return dateString
}

@Composable
fun ScanScreen(
    thermalPrinterManager: ThermalPrinterManager,
    viewModel: ScanViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    var hasScanned by remember { mutableStateOf(false) }

    // --- Phone Number Logic (Unchanged) ---
    val phoneNumberHintLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val phoneNumber = result.data?.getStringExtra("phone_number_hint_result")
                ?: result.data?.getStringExtra("EXTRA_PHONE_NUMBER")
                ?: result.data?.extras?.getString("phone_number_hint_result")
                ?: result.data?.extras?.getString("EXTRA_PHONE_NUMBER")
            if (!phoneNumber.isNullOrEmpty()) viewModel.retryScanWithPhoneNumber(phoneNumber, context)
            else viewModel.forceShowError("Phone number required.")
        } else viewModel.forceShowError("Selection cancelled.")
    }

    LaunchedEffect(Unit) { viewModel.detectPhoneNumber(context) }

    // Auto-launch picker on specific error
    LaunchedEffect(viewModel.scanStatus) {
        val status = viewModel.scanStatus
        if (status is ScanStatus.Error && status.message.contains("Unable to detect scanner phone number")) {
            try {
                val request = GetPhoneNumberHintIntentRequest.builder().build()
                val client = Identity.getSignInClient(context as Activity)
                client.getPhoneNumberHintIntent(request)
                    .addOnSuccessListener { phoneNumberHintLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            } catch (e: Exception) { viewModel.forceShowError("Launcher failed.") }
        }
    }

    val isBulkHistoryOpen = viewModel.scanStatus is ScanStatus.BulkBatchHistoryLoading ||
            viewModel.scanStatus is ScanStatus.BulkBatchHistory ||
            viewModel.scanStatus is ScanStatus.BulkBatchDetails
    BackHandler(enabled = isBulkHistoryOpen) {
        viewModel.navigateBackInBulkHistory()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The ViewModel survives tab switches while this local flag does not. Keep the
        // camera hidden whenever a scan result (including a bulk group) is still open.
        if (!hasScanned && viewModel.scanStatus is ScanStatus.Idle) {
            QRCodeScannerView(
                onQRCodeScanned = { qrCode ->
                    if (!hasScanned) {
                        hasScanned = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) // Feedback
                        viewModel.handleQrCodeScan(qrCode, context)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Overlay Content
        AnimatedVisibility(
            visible = hasScanned || viewModel.scanStatus !is ScanStatus.Idle,
            enter = slideInVertically { it } + fadeIn(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            when (val status = viewModel.scanStatus) {
                is ScanStatus.Idle -> { /* Do nothing */ }
                is ScanStatus.Scanning -> {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Verifying...", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                is ScanStatus.Verified -> {
                    ResultSheet(
                        icon = Icons.Rounded.CheckCircle,
                        iconColor = Color(0xFF4CAF50), // Green
                        title = "Sweet Scanned",
                        member = status.member,
                        statusMessage = "Verified Successfully",
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
                    )
                }
                is ScanStatus.AlreadyScanned -> {
                    ResultSheet(
                        icon = Icons.Rounded.Warning,
                        iconColor = MaterialTheme.colorScheme.error,
                        title = "Do Not Issue Again",
                        member = status.member,
                        statusMessage = "Already scanned on ${formatDisplayDate(status.member.scannerDate)}",
                        warningStyle = true,
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
                    )
                }
                is ScanStatus.BulkGroupReady -> {
                    BulkGroupSheet(
                        group = status.group,
                        selectedMemberNumbers = viewModel.selectedBulkMemberNumbers,
                        onToggle = viewModel::toggleBulkMember,
                        onToggleAll = viewModel::toggleAllBulkMembers,
                        onScan = { viewModel.scanSelectedGroupMembers(context, thermalPrinterManager) },
                        onReprint = viewModel::openBulkBatchHistory,
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
                    )
                }
                is ScanStatus.BulkScanning -> {
                    BulkProgressSheet(status.groupId, status.selectedCount)
                }
                is ScanStatus.BulkScanned -> {
                    val batch = BulkScanBatch(
                        id = status.batchId,
                        groupId = status.groupId,
                        scannedAt = status.scannedAt,
                        scannerNumber = status.scannerNumber,
                        members = status.scannedMembers
                    )
                    BulkScannedSheet(
                        status = status,
                        printMessage = viewModel.bulkPrintMessage,
                        isPrinting = viewModel.reprintingBatchId == status.batchId,
                        onReprint = { viewModel.reprintBulkBatch(batch, thermalPrinterManager) },
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
                    )
                }
                is ScanStatus.BulkAllScanned -> {
                    BulkAllScannedSheet(
                        groupId = status.group.groupId,
                        memberCount = status.group.members.size,
                        onReprint = viewModel::openBulkBatchHistory,
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
                    )
                }
                is ScanStatus.BulkBatchHistoryLoading -> {
                    BulkBatchHistoryLoadingScreen(
                        groupId = status.group.groupId,
                        onBack = viewModel::navigateBackInBulkHistory
                    )
                }
                is ScanStatus.BulkBatchHistory -> {
                    BulkBatchHistoryScreen(
                        groupId = status.group.groupId,
                        batches = status.batches,
                        errorMessage = status.errorMessage,
                        onBatchSelected = viewModel::showBulkBatch,
                        onRetry = viewModel::openBulkBatchHistory,
                        onBack = viewModel::navigateBackInBulkHistory
                    )
                }
                is ScanStatus.BulkBatchDetails -> {
                    BulkBatchDetailsScreen(
                        batch = status.batch,
                        isPrinting = viewModel.reprintingBatchId == status.batch.id,
                        printMessage = viewModel.bulkPrintMessage,
                        onReprint = { viewModel.reprintBulkBatch(status.batch, thermalPrinterManager) },
                        onBack = viewModel::navigateBackInBulkHistory
                    )
                }
                is ScanStatus.Invalid -> {
                    ErrorSheet(
                        icon = Icons.Rounded.Cancel,
                        title = "Invalid Code",
                        message = "This QR code does not exist in the database.",
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
                    )
                }
                is ScanStatus.Error -> {
                    if (!status.message.contains("Unable to detect scanner phone number")) {
                        ErrorSheet(
                            icon = Icons.Rounded.Error,
                            title = "System Error",
                            message = status.message,
                            onDismiss = { hasScanned = false; viewModel.resetScan() }
                        )
                    }
                }
            }
        }
    }
}

// --- Modern UI Components ---

@Composable
fun ResultSheet(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    member: com.example.Chennai_Coop.data.models.Member,
    statusMessage: String,
    warningStyle: Boolean = false,
    onDismiss: () -> Unit
) {
    val containerColor = if (warningStyle) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (warningStyle) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = if (warningStyle) {
        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 8.dp,
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(iconColor.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = iconColor
            )
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium, color = supportingColor)

            Spacer(modifier = Modifier.height(24.dp))
            Divider(
                color = if (warningStyle) {
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Member Details Grid
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DataColumn("Name", member.name ?: "Unknown", labelColor = supportingColor)
                DataColumn("Member No", member.memberNumber ?: "N/A", alignment = Alignment.End, labelColor = supportingColor)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DataColumn("Employee No", member.employeeNumber ?: "N/A", labelColor = supportingColor)
                DataColumn("Scanned", formatDisplayDate(member.scannerDate), alignment = Alignment.End, labelColor = supportingColor)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = iconColor)
            ) {
                Text("Scan Next", fontSize = MaterialTheme.typography.bodyLarge.fontSize)
            }
        }
    }
}

@Composable
fun ErrorSheet(
    icon: ImageVector,
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Try Again", color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}

@Composable
fun DataColumn(
    label: String,
    value: String,
    alignment: Alignment.Horizontal = Alignment.Start,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(horizontalAlignment = alignment) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = labelColor)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BulkGroupSheet(
    group: BulkGroup,
    selectedMemberNumbers: Set<String>,
    onToggle: (com.example.Chennai_Coop.data.models.Member) -> Unit,
    onToggleAll: () -> Unit,
    onScan: () -> Unit,
    onReprint: () -> Unit,
    onDismiss: () -> Unit
) {
    val memberListState = rememberLazyListState()
    val hasMoreMembersBelow by remember {
        derivedStateOf { memberListState.canScrollForward }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bulk sweet scan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Group ID: ${group.groupId}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text("${group.members.size} members", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = onReprint) {
                    Icon(Icons.Rounded.Replay, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reprint")
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text("Sno", Modifier.width(42.dp), fontWeight = FontWeight.Bold)
                Text("Mno", Modifier.width(70.dp), fontWeight = FontWeight.Bold)
                Text("Name", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("Status", Modifier.width(92.dp), fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = memberListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = group.members,
                        key = { index, member -> member.memberNumber ?: "member-$index" }
                    ) { index, member ->
                        val memberNumber = member.memberNumber.orEmpty()
                        val scanned = !member.scannerDate.isNullOrBlank()
                        val decoration = if (scanned) TextDecoration.LineThrough else TextDecoration.None
                        val rowColor = if (scanned) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (index + 1).toString(),
                                Modifier.width(42.dp),
                                color = rowColor,
                                textDecoration = decoration
                            )
                            Text(
                                memberNumber,
                                Modifier.width(70.dp),
                                color = rowColor,
                                textDecoration = decoration
                            )
                            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                                Text(
                                    member.name.orEmpty(),
                                    color = rowColor,
                                    textDecoration = decoration,
                                    maxLines = 2
                                )
                                if (scanned) {
                                    Text(
                                        "Scanned ${formatDisplayDate(member.scannerDate)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f),
                                        maxLines = 1
                                    )
                                }
                            }
                            if (scanned) {
                                Box(
                                    modifier = Modifier.width(92.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "SCANNED",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else {
                                Box(Modifier.width(92.dp), contentAlignment = Alignment.Center) {
                                    Checkbox(
                                        checked = memberNumber in selectedMemberNumbers,
                                        enabled = memberNumber.isNotBlank(),
                                        onCheckedChange = { onToggle(member) }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                if (hasMoreMembersBelow) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                                        MaterialTheme.colorScheme.surface
                                    )
                                )
                            ),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Row(
                            modifier = Modifier.padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "More members below",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            val selectableCount = group.members.count {
                it.scannerDate.isNullOrBlank() && !it.memberNumber.isNullOrBlank()
            }
            val allSelected = selectableCount > 0 && selectedMemberNumbers.size == selectableCount
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${selectedMemberNumbers.size} member${if (selectedMemberNumbers.size == 1) "" else "s"} selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onToggleAll, enabled = selectableCount > 0) {
                    Text(if (allSelected) "Deselect all" else "Select all")
                }
            }
            Button(
                onClick = onScan,
                enabled = selectedMemberNumbers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Scan ${selectedMemberNumbers.size} selected")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Scan another QR") }
        }
    }
}

@Composable
private fun BulkProgressSheet(groupId: String, selectedCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Scanning $selectedCount members", style = MaterialTheme.typography.titleLarge)
            Text("Group $groupId")
        }
    }
}

@Composable
private fun BulkBatchHistoryLoadingScreen(groupId: String, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to group")
                }
                Column {
                    Text("Reprint batches", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Group $groupId", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading saved batches...")
                }
            }
        }
    }
}

@Composable
private fun BulkBatchHistoryScreen(
    groupId: String,
    batches: List<BulkScanBatch>,
    errorMessage: String?,
    onBatchSelected: (BulkScanBatch) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to group")
                }
                Column {
                    Text("Reprint batches", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Group $groupId", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))

            when {
                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text(errorMessage, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onRetry) { Text("Try again") }
                        }
                    }
                }
                batches.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No saved scan batches for this group yet.",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Text(
                        "Select a batch to review its members and print two copies.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(batches, key = { _, batch -> batch.id }) { _, batch ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onBatchSelected(batch) },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(batch.displayNumber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        Text(formatDisplayDate(batch.scannedAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Issuer ${batch.scannerNumber.ifBlank { "Unknown" }}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(batch.members.size.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                        Text("members", style = MaterialTheme.typography.labelMedium)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = "View batch")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkBatchDetailsScreen(
    batch: BulkScanBatch,
    isPrinting: Boolean,
    printMessage: String?,
    onReprint: () -> Unit,
    onBack: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to batches")
                }
                Column(Modifier.weight(1f)) {
                    Text(batch.displayNumber, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Group ${batch.groupId}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${batch.members.size} members",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(formatDisplayDate(batch.scannedAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Issuer ${batch.scannerNumber.ifBlank { "Unknown" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(batch.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text("Sno", Modifier.width(48.dp), fontWeight = FontWeight.Bold)
                Text("Mno", Modifier.width(80.dp), fontWeight = FontWeight.Bold)
                Text("Name", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(
                    batch.members,
                    key = { index, member -> member.memberNumber ?: "batch-member-$index" }
                ) { index, member ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text((index + 1).toString(), Modifier.width(48.dp))
                        Text(member.memberNumber.orEmpty(), Modifier.width(80.dp), fontWeight = FontWeight.SemiBold)
                        Text(member.name.orEmpty(), Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            printMessage?.let { message ->
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (message.startsWith("Reprint failed")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onReprint,
                enabled = !isPrinting,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (isPrinting) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Printing...")
                } else {
                    Icon(Icons.Rounded.Print, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reprint two copies")
                }
            }
        }
    }
}

@Composable
private fun BulkScannedSheet(
    status: ScanStatus.BulkScanned,
    printMessage: String?,
    isPrinting: Boolean,
    onReprint: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("Bulk scan complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Group ${status.groupId}")
            Spacer(Modifier.height(16.dp))
            Text(
                "${status.scannedMembers.size}",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                "Members scanned now",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text("${status.totalScanned} of ${status.totalMembers} members scanned in total")
            Text("Scanned ${formatDisplayDate(status.scannedAt)}")
            Text(
                "Batch B-${status.batchId.substringBefore('-').uppercase()}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            printMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = onReprint,
                enabled = !isPrinting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isPrinting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Printing...")
                } else {
                    Icon(Icons.Rounded.Print, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Print two copies again")
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Scan Next") }
        }
    }
}

@Composable
private fun BulkAllScannedSheet(
    groupId: String,
    memberCount: Int,
    onReprint: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 8.dp
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = onReprint, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Rounded.Replay, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reprint")
                }
            }
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Do Not Issue Again",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                "All members in group $groupId have already been scanned.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text("$memberCount members", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Scan Next") }
        }
    }
}
