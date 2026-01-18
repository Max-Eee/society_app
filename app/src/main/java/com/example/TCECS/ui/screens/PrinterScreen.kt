package com.example.TCECS.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.TCECS.ui.components.DeviceItem
import com.example.TCECS.utils.BluetoothPrinterManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PrinterScreen(
    printerManager: BluetoothPrinterManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isScanning by remember { mutableStateOf(false) }
    // Collect flows from the manager passed from MainActivity
    val scannedDevices by printerManager.scannedDevices.collectAsState()
    var pairedDevices by remember { mutableStateOf(printerManager.getPairedDevices()) }
    var connectingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { printerManager.stopDiscovery() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        // Scan Controls
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Bluetooth Scan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isScanning) "Scanning..." else "Idle",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(onClick = {
                    if (isScanning) {
                        printerManager.stopDiscovery()
                    } else {
                        printerManager.startDiscovery()
                    }
                    isScanning = !isScanning
                }) {
                    Icon(if (isScanning) Icons.Default.Close else Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isScanning) "Stop" else "Scan")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Device List
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // PAIRED DEVICES SECTION
            item {
                Text(
                    "Paired Devices",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (pairedDevices.isEmpty()) {
                item { Text("No paired devices found", style = MaterialTheme.typography.bodyMedium) }
            }

            items(pairedDevices) { device ->
                DeviceItem(device, true, connectingId == device.address) {
                    // CONNECT LOGIC
                    connectingId = device.address
                    printerManager.stopDiscovery()
                    isScanning = false
                    Toast.makeText(context, "Connecting...", Toast.LENGTH_SHORT).show()

                    scope.launch {
                        val success = printerManager.connect(device)
                        connectingId = null
                        Toast.makeText(
                            context,
                            if (success) "Connected!" else "Connection Failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            // AVAILABLE (SCANNED) DEVICES SECTION
            if (scannedDevices.isNotEmpty()) {
                item {
                    Divider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "Available Devices",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(scannedDevices) { device ->
                    if (pairedDevices.none { it.address == device.address }) {
                        DeviceItem(device, false, false) {
                            // PAIRING LOGIC
                            val initiated = printerManager.pairDevice(device)
                            Toast.makeText(
                                context,
                                if (initiated) "Pairing..." else "Pairing Error",
                                Toast.LENGTH_SHORT
                            ).show()

                            scope.launch {
                                delay(5000) // Wait for pairing
                                pairedDevices = printerManager.getPairedDevices()
                            }
                        }
                    }
                }
            }
        }
    }
}