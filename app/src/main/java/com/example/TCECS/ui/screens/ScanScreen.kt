package com.example.TCECS.ui.screens

import android.app.Activity
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.TCECS.ui.components.QRCodeScannerView
import com.example.TCECS.ui.viewmodel.ScanStatus
import com.example.TCECS.ui.viewmodel.ScanViewModel
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

    Box(modifier = modifier.fillMaxSize()) {
        if (!hasScanned) {
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
                        title = "Issue Sweet",
                        member = status.member,
                        statusMessage = "Verified Successfully",
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
                    )
                }
                is ScanStatus.AlreadyScanned -> {
                    ResultSheet(
                        icon = Icons.Rounded.Warning,
                        iconColor = Color(0xFFFF9800), // Orange
                        title = "Already Scanned",
                        member = status.member,
                        statusMessage = "Scanned on ${formatDisplayDate(status.member.scannerDate)}",
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
                    )
                }
                is ScanStatus.AlreadyIssued -> {
                    ResultSheet(
                        icon = Icons.Rounded.Info,
                        iconColor = Color(0xFF2196F3), // Blue
                        title = "Issued Only",
                        member = status.member,
                        statusMessage = "Not yet scanned at gate",
                        onDismiss = { hasScanned = false; viewModel.resetScan() }
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
    member: com.example.TCECS.data.models.Member,
    statusMessage: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
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

            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(24.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // Member Details Grid
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DataColumn("Name", member.name ?: "Unknown")
                DataColumn("Member No", member.memberNumber ?: "N/A", alignment = Alignment.End)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DataColumn("Employee No", member.employeeNumber ?: "N/A")
                DataColumn("Issued", formatDisplayDate(member.issueDate), alignment = Alignment.End)
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
fun DataColumn(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}