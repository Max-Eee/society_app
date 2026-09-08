package com.example.Chennai_Coop.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.Chennai_Coop.data.models.Member
import com.example.Chennai_Coop.ui.viewmodel.IssueViewModel
import com.example.Chennai_Coop.utils.ThermalPrinterManager
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

// Helper function to format date
private fun formatDate(dateString: String?): String {
    if (dateString.isNullOrEmpty()) return ""
    return try {
        val inputFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        )
        var parsedDate: java.util.Date? = null
        for (format in inputFormats) {
            try {
                parsedDate = format.parse(dateString)
                if (parsedDate != null) break
            } catch (e: Exception) { continue }
        }
        if (parsedDate != null) {
            val outputFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
            outputFormat.format(parsedDate)
        } else {
            dateString
        }
    } catch (e: Exception) {
        dateString
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueScreen(
    viewModel: IssueViewModel = viewModel(),
    thermalPrinterManager: ThermalPrinterManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // --- Load saved phone number on first launch ---
    LaunchedEffect(Unit) {
        viewModel.loadSavedPhoneNumber(context)
    }

    // --- DEBOUNCE STATE ---
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debounceDuration = 2000L

    // --- REPRINT LOADING STATE ---
    var isReprintLoading by remember { mutableStateOf(false) }

    fun startIssueProcess() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < debounceDuration) return
        lastClickTime = currentTime

        val preparedMember = viewModel.prepareMemberForIssue() ?: return
        viewModel.setLoadingState(true)

        thermalPrinterManager.printViaBluetooth(
            member = preparedMember,
            onSuccess = {
                coroutineScope.launch {
                    viewModel.finalizeIssue(preparedMember)
                }
            },
            onError = { errorMsg ->
                coroutineScope.launch {
                    viewModel.setPrintFailure(errorMsg)
                }
            }
        )
    }

    // --- Actions ---
    fun performSearch() {
        if (viewModel.searchQuery.isNotEmpty()) {
            viewModel.searchMember()
            focusManager.clearFocus()
        }
    }

    // --- Phone Number Logic ---
    val phoneNumberHintLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.dismissPhoneNumberPicker()
        if (result.resultCode == Activity.RESULT_OK) {
            val phoneNumber = result.data?.getStringExtra("phone_number_hint_result")
                ?: result.data?.getStringExtra("EXTRA_PHONE_NUMBER")
                ?: result.data?.extras?.getString("phone_number_hint_result")
                ?: result.data?.extras?.getString("EXTRA_PHONE_NUMBER")

            if (!phoneNumber.isNullOrEmpty()) {
                viewModel.updateIssuerPhoneNumber(phoneNumber, context)
                startIssueProcess()
            } else {
                viewModel.showErrorMessage("Phone number required.")
            }
        }
    }

    LaunchedEffect(viewModel.showPhoneNumberPicker) {
        if (viewModel.showPhoneNumberPicker) {
            try {
                val request = GetPhoneNumberHintIntentRequest.builder().build()
                val client = Identity.getSignInClient(context as Activity)
                client.getPhoneNumberHintIntent(request)
                    .addOnSuccessListener { intentSender ->
                        phoneNumberHintLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build()
                        )
                    }
                    .addOnFailureListener {
                        viewModel.dismissPhoneNumberPicker()
                        viewModel.showErrorMessage("Phone picker failed: ${it.message}")
                    }
            } catch (e: Exception) {
                viewModel.dismissPhoneNumberPicker()
            }
        }
    }

    // --- Print Failure Dialog ---
    if (viewModel.showPrintFailureDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPrintFailureDialog() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Print Failed") },
            text = {
                Column {
                    Text("The receipt could not be printed.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Reason: ${viewModel.printFailureReason}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "The member was NOT marked as issued because printing failed.",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPrintFailureDialog() }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            // --- STICKY BOTTOM ACTION BAR ---
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Search Field
                    OutlinedTextField(
                        value = viewModel.searchQuery,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() } && newValue.length <= 8) {
                                viewModel.updateSearchQuery(newValue)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Mem/Emp No",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        singleLine = true,
                        shape = CircleShape,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { performSearch() }
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (viewModel.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.clearSearch() }) {
                                    Icon(Icons.Default.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    // 2. Dynamic Action Button
                    val member = viewModel.member
                    val isIssued = member?.isIssued == true
                    val isMemberLoaded = member != null
                    val isQueryMatchingMember = isMemberLoaded && (
                            member?.memberNumber == viewModel.searchQuery ||
                                    member?.employeeNumber == viewModel.searchQuery
                            )

                    val buttonColor = when {
                        isIssued && isQueryMatchingMember -> MaterialTheme.colorScheme.surfaceVariant
                        isMemberLoaded && isQueryMatchingMember -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }

                    val isAnyLoading = viewModel.isLoading || isReprintLoading

                    val buttonIcon = when {
                        isAnyLoading -> null
                        isMemberLoaded && isQueryMatchingMember -> Icons.Default.Print
                        else -> Icons.Default.Search
                    }

                    FilledIconButton(
                        onClick = {
                            when {
                                isAnyLoading -> {} // Busy
                                isMemberLoaded && isQueryMatchingMember && !isIssued -> startIssueProcess()
                                isMemberLoaded && isQueryMatchingMember && isIssued -> {
                                    if (!isReprintLoading) {
                                        isReprintLoading = true
                                        coroutineScope.launch {
                                            delay(2000)
                                            thermalPrinterManager.printViaBluetooth(
                                                member = member!!,
                                                onSuccess = {
                                                    Toast.makeText(context, "Reprint sent", Toast.LENGTH_SHORT).show()
                                                    isReprintLoading = false
                                                },
                                                onError = {
                                                    viewModel.setPrintFailure(it)
                                                    isReprintLoading = false
                                                }
                                            )
                                        }
                                    }
                                }
                                else -> performSearch()
                            }
                        },
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = buttonColor
                        )
                    ) {
                        if (isAnyLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else if (buttonIcon != null) {
                            Icon(
                                imageVector = buttonIcon,
                                contentDescription = "Action",
                                tint = if (isMemberLoaded && isQueryMatchingMember && !isIssued)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // innerPadding naturally handles the BottomBar AND Keyboard height
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. MAIN CONTENT
            if (viewModel.member != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Spacer adds margin for status bar
                    MemberDetailsCard(member = viewModel.member!!)
                    Spacer(modifier = Modifier.height(20.dp))
                }
            } else {
                // --- FIXED EMPTY STATE LAYOUT ---
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()) // Allow scroll if screen is tiny
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // This spacer pushes content down from the top (Status Bar safe)
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

                    // This weight spacer pushes content to the vertical center
                    Spacer(Modifier.weight(1f))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(120.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Search Member",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter a Member or Employee number\nto view details and issue.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    // This weight spacer ensures content stays centered but has space at bottom
                    Spacer(Modifier.weight(1f))
                }
            }

            // 2. FLOATING ERROR / SUCCESS TOASTS (Top Layer)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                viewModel.errorMessage?.let { error ->
                    Card(
                        shape = CircleShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                viewModel.successMessage?.let { msg ->
                    Card(
                        shape = CircleShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = msg,
                                color = Color(0xFF1B5E20),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MemberDetailsCard(member: Member) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Member Details",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!member.scannerDate.isNullOrEmpty()) {
                        Surface(
                            color = Color(0xFF2E7D32),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "ISSUED",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (!member.isEligibleForQr) {
                        Surface(
                            color = Color.Red,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = member.qrIneligibilityLabel.orEmpty(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            DetailRow(label = "Member No", value = member.memberNumber ?: "")
            DetailRow(label = "Employee No", value = member.employeeNumber ?: "")
            HorizontalDivider()
            DetailRow(label = "Name", value = member.name ?: "", valueStyle = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            DetailRow(label = "Station", value = member.station ?: "")
            HorizontalDivider()

            // 1. DEPOSIT DETAILS (Only if account not closed)
            if (!member.isAccountClosed) {
                Text(
                    text = "Deposit Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                FinancialTable3Col(
                    headers = listOf("Share Capital", "Thrift Deposit", "F.D"),
                    values = listOf(
                        "₹${(member.shareCapital ?: 0.0).toInt()}",
                        "₹${(member.thriftDeposit ?: 0.0).toInt()}",
                        "₹${(member.familyDeposit ?: 0.0).toInt()}"
                    )
                )
                HorizontalDivider()
            }

            // 2. LOAN DETAILS (Only if loan balance > 0)
            val loanBalance = member.loanBalance ?: 0.0
            if (loanBalance > 0) {
                Text(
                    text = "Loan Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                FinancialTable3Col(
                    headers = listOf("Loan Date", "Amount", "Balance"),
                    values = listOf(
                        formatDate(member.loanDate),
                        "₹${(member.loanAmount ?: 0.0).toInt()}",
                        "₹${(member.loanBalance ?: 0.0).toInt()}"
                    )
                )
                HorizontalDivider()
            }

            // 3. DIVIDEND DETAILS
            Text(
                text = "Dividend Calculation Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (member.dividend.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("From", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                        Text("To", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                        Text("Days", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                        Text("Bal", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                        Text("Amt", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                    }
                    HorizontalDivider()

                    member.dividend.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(formatDate(entry.from), modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
                            Text(formatDate(entry.to), modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
                            Text((entry.days ?: 0).toString(), modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                            Text((entry.balance ?: 0.0).toInt().toString(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                            Text("%.2f".format(entry.amount ?: 0.0), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                        }
                    }
                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(2.4f))
                        Text(
                            "Dividend @ 14%:",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(2f),
                            textAlign = TextAlign.End
                        )
                        Text(
                            "₹${String.format("%.2f", member.dividend.sumOf { it.amount ?: 0.0 })}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text("No dividend entries", style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            DetailRow(label = "NEFT Amount", value = "₹${member.neft ?: 0}")
            DetailRow(label = "A/C No", value = member.accountNumber?.trim() ?: "")

            if (member.scannerDate != null && member.scannerDate.isNotEmpty()) {
                DetailRow(label = "Scan Date", value = member.scannerDate, valueColor = MaterialTheme.colorScheme.primary)
            }
            if (member.scannerNumber != null && member.scannerNumber.isNotEmpty()) {
                DetailRow(label = "Sweet Issuer Mobile", value = member.scannerNumber, valueColor = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun FinancialTable3Col(
    headers: List<String>,
    values: List<String>
) {
    if (headers.size != 3 || values.size != 3) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(headers[0], fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Text(headers[1], fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(headers[2], fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(values[0], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Text(values[1], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(values[2], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    valueColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
