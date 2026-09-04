package com.example.Chennai_Coop.utils

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class BluetoothPrinterManager(private val context: Context) {
    private val TAG = "BTManager"
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null

    // Standard SPP UUID
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Track registration to avoid crashes
    private var isReceiverRegistered = false

    // Lock to prevent multiple connection attempts at once
    private val connectionLock = Any()

    private val btAdapter: BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    } else {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()
    }

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice = _connectedDevice.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    device?.let { d ->
                        if (!d.name.isNullOrEmpty() && !_scannedDevices.value.any { it.address == d.address }) {
                            _scannedDevices.value += d
                        }
                    }
                }
            }
        }
    }

    fun startDiscovery() {
        if (!hasPerms() || btAdapter == null) return
        if (!isReceiverRegistered) {
            try {
                context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
                isReceiverRegistered = true
                if (btAdapter.isDiscovering) btAdapter.cancelDiscovery()
                btAdapter.startDiscovery()
                Log.d(TAG, "Discovery started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting discovery", e)
            }
        }
    }

    fun stopDiscovery() {
        if (!hasPerms() || btAdapter == null) return
        try {
            if (btAdapter.isDiscovering) btAdapter.cancelDiscovery()
            if (isReceiverRegistered) {
                context.unregisterReceiver(receiver)
                isReceiverRegistered = false
                Log.d(TAG, "Discovery stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Err stop disc", e)
        }
    }

    fun pairDevice(device: BluetoothDevice): Boolean {
        if (!hasPerms()) return false
        return try {
            Log.d(TAG, "Pairing with ${device.name}")
            device.createBond()
        } catch (e: Exception) {
            Log.e(TAG, "Pair err", e)
            false
        }
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasPerms() || btAdapter == null) return emptyList()
        return btAdapter.bondedDevices?.toList() ?: emptyList()
    }

    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        synchronized(connectionLock) {
            if (!hasPerms() || btAdapter == null) return@withContext false

            // 1. Force close previous connection
            close()

            // 2. Stop discovery (Critical for stability)
            try { btAdapter.cancelDiscovery() } catch(e: Exception) {}

            try {
                Log.d(TAG, "Connecting to ${device.name} (${device.address})...")

                // 3. Try standard connection
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()

                btSocket = socket
                outStream = socket.outputStream
                _connectedDevice.value = device
                Log.d(TAG, "Connected successfully")
                return@withContext true

            } catch (e: IOException) {
                Log.w(TAG, "Standard connect failed, trying fallback...")
                try {
                    // 4. Fallback connection
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    val socket = method.invoke(device, 1) as BluetoothSocket
                    socket.connect()

                    btSocket = socket
                    outStream = socket.outputStream
                    _connectedDevice.value = device
                    Log.d(TAG, "Fallback connect successful")
                    return@withContext true
                } catch (e2: Exception) {
                    Log.e(TAG, "All connection attempts failed", e2)
                    close()
                    return@withContext false
                }
            }
        }
    }

    suspend fun print(data: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(connectionLock) {
            try {
                if (outStream == null) return@withContext false
                outStream?.write(data.toByteArray(Charsets.ISO_8859_1))
                outStream?.flush()
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Print string failed", e)
                close() // Auto-close on error
                return@withContext false
            }
        }
    }

    suspend fun printBytes(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        synchronized(connectionLock) {
            try {
                if (outStream == null) return@withContext false

                val chunkSize = 1024
                var offset = 0
                while (offset < data.size) {
                    val length = Math.min(chunkSize, data.size - offset)
                    outStream?.write(data, offset, length)
                    outStream?.flush()
                    offset += length
                    Thread.sleep(15) // Slight delay for buffer
                }
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Print bytes failed", e)
                close()
                return@withContext false
            }
        }
    }

    fun close() {
        try {
            outStream?.close()
            btSocket?.close()
            Log.d(TAG, "Socket closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        } finally {
            btSocket = null
            outStream = null
            _connectedDevice.value = null
        }
    }

    fun isConnected(): Boolean {
        return btSocket?.isConnected == true
    }

    private fun hasPerms(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }
}
