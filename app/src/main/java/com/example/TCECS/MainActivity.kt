package com.example.TCECS
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.example.TCECS.ui.screens.IssueScreen
import com.example.TCECS.ui.screens.PrinterScreen
import com.example.TCECS.ui.screens.ReportScreen
import com.example.TCECS.ui.screens.ScanScreen
import com.example.TCECS.ui.theme.SocietyTheme
import com.example.TCECS.utils.BluetoothPrinterManager
import com.example.TCECS.utils.ThermalPrinterManager

// ==================== APP MODE CONFIGURATION ====================
// Change this value to control which features are visible:
// - AppMode.ALL: Show all tabs (Issue, Scan, Report, Printer)
// - AppMode.SCAN: Show only Scan and Report tabs (hide Issue and Printer)
// - AppMode.ISSUE: Show only Issue, Report, and Printer tabs (hide Scan)
val CURRENT_APP_MODE = AppMode.ALL
// ================================================================

enum class AppMode {
    ALL,    // Show all features
    SCAN,   // Scan-only version (hide Issue and Printer)
    ISSUE   // Issue-only version (hide Scan)
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Permissions are required for printing", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissions()
        enableEdgeToEdge()
        setContent {
            SocietyTheme {
                SocietyApp()
            }
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
}

@Composable
fun SocietyApp() {
    // Set default destination based on app mode
    val defaultDestination = when (CURRENT_APP_MODE) {
        AppMode.ALL -> AppDestinations.ISSUE
        AppMode.SCAN -> AppDestinations.SCAN
        AppMode.ISSUE -> AppDestinations.ISSUE
    }

    var currentDestination by rememberSaveable { mutableStateOf(defaultDestination) }

    // --- STATE HOISTING ---
    // Create the managers here so they survive tab changes
    val context = LocalContext.current
    val thermalPrinter = remember { ThermalPrinterManager(context) }
    val bluetoothManager = remember { BluetoothPrinterManager(context) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries
                .filter { destination ->
                    when (CURRENT_APP_MODE) {
                        AppMode.ALL -> true // Show all tabs
                        AppMode.SCAN -> destination in listOf(
                            AppDestinations.SCAN,
                            AppDestinations.REPORT
                        ) // Show only Scan and Report
                        AppMode.ISSUE -> destination in listOf(
                            AppDestinations.ISSUE,
                            AppDestinations.REPORT,
                            AppDestinations.PRINTER
                        ) // Show Issue, Report, and Printer (hide Scan)
                    }
                }
                .forEach {
                    item(
                        icon = { Icon(it.icon, contentDescription = it.label) },
                        label = { Text(it.label) },
                        selected = it == currentDestination,
                        onClick = { currentDestination = it }
                    )
                }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination) {
                AppDestinations.ISSUE -> {
                    IssueScreen(
                        // Pass the hoisted printer instance
                        thermalPrinterManager = thermalPrinter,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
                AppDestinations.SCAN -> {
                    ScanScreen(modifier = Modifier.padding(innerPadding))
                }
                // --- NEW REPORT TAB ---
                AppDestinations.REPORT -> {
                    ReportScreen(modifier = Modifier.padding(innerPadding))
                }
                AppDestinations.PRINTER -> {
                    PrinterScreen(
                        // Pass the hoisted bluetooth manager
                        printerManager = bluetoothManager,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    ISSUE("Issue", Icons.Default.ConfirmationNumber),
    SCAN("Scan", Icons.Default.QrCode2),
    REPORT("Report", Icons.Default.Assessment), // New Tab added here
    PRINTER("Printer", Icons.Default.Print),
}