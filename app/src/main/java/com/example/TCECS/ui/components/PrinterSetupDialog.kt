package com.example.TCECS.ui.components

import android.bluetooth.BluetoothDevice
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.TCECS.utils.BluetoothPrinterManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PrinterSetupDialog(
    onDismiss: () -> Unit,
    printerManager: BluetoothPrinterManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // <--- FIXED: Get the Compose Coroutine Scope

    var isScanning by remember { mutableStateOf(false) }
    val scannedDevices by printerManager.scannedDevices.collectAsState()
    var pairedDevices by remember { mutableStateOf(printerManager.getPairedDevices()) }
    var connectingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { printerManager.stopDiscovery() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(600.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                // Header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Printer Setup", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }

                HorizontalDivider()

                // Scan Button
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        if (isScanning) {
                            printerManager.stopDiscovery()
                        } else {
                            printerManager.startDiscovery()
                        }
                        isScanning = !isScanning
                    }) {
                        Icon(if (isScanning) Icons.Default.Close else Icons.Default.Refresh, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isScanning) "Stop Scan" else "Scan New")
                    }
                }

                // Device List
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // PAIRED DEVICES SECTION
                    item {
                        Text(
                            "Paired Devices",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    items(pairedDevices) { device ->
                        DeviceItem(device, true, connectingId == device.address) {
                            // CONNECT LOGIC
                            connectingId = device.address
                            printerManager.stopDiscovery() // Stop scan before connecting
                            Toast.makeText(context, "Connecting...", Toast.LENGTH_SHORT).show()

                            // FIXED: Use 'scope.launch' instead of GlobalScope
                            scope.launch {
                                val success = printerManager.connect(device)
                                connectingId = null
                                Toast.makeText(
                                    context,
                                    if(success) "Connected!" else "Connection Failed",
                                    Toast.LENGTH_SHORT
                                ).show()

                                if(success) onDismiss()
                            }
                        }
                    }

                    // AVAILABLE (SCANNED) DEVICES SECTION
                    if (scannedDevices.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Available Devices",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(scannedDevices) { device ->
                            // Filter out already paired devices from the scanned list
                            if (pairedDevices.none { it.address == device.address }) {
                                DeviceItem(device, false, false) {
                                    // PAIRING LOGIC
                                    val initiated = printerManager.pairDevice(device)
                                    Toast.makeText(
                                        context,
                                        if (initiated) "Pairing..." else "Pairing Error",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                    // FIXED: Use 'scope.launch' to refresh list after delay
                                    scope.launch {
                                        delay(5000) // Wait for pairing to complete
                                        pairedDevices = printerManager.getPairedDevices()
                                    }
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
fun DeviceItem(
    device: BluetoothDevice,
    isPaired: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isPaired) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (isPaired) Icons.Default.Print else Icons.Default.Bluetooth, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Use safe calls for name as it can be null
                Text(
                    text = device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (!isPaired) {
                Text(
                    "PAIR",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}