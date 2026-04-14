package com.ustadmobile.meshrabiya.vnet.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.ustadmobile.meshrabiya.MeshrabiyaConstants.LOG_TAG
import java.nio.charset.StandardCharsets
import java.util.UUID

class BluetoothLeScannerHelper(
    private val context: Context,
    private val serviceUuid: UUID,
    private val onConnectUriFound: (String) -> Unit
) {
    private val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var isScanning = false
    private val seenDevices = mutableSetOf<String>()

    // Generate a characteristic UUID by slightly altering the service UUID
    private val characteristicUuid = UUID(serviceUuid.mostSignificantBits, serviceUuid.leastSignificantBits xor 1L)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(LOG_TAG, "BLE Scan failed with error code: $errorCode")
            isScanning = false
        }

        @SuppressLint("MissingPermission")
        private fun handleScanResult(result: ScanResult?) {
            val device = result?.device ?: return

            // Deduplicate using MAC address to avoid spamming connections
            if (seenDevices.contains(device.address)) {
                return
            }

            // Log it right away to avoid multiple simultaneous attempts
            seenDevices.add(device.address)
            Log.d(LOG_TAG, "Found BLE device advertising Meshrabiya service: ${device.address}. Connecting to GATT...")

            // Connect to GATT server to retrieve the connectUri
            try {
                device.connectGatt(context, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothGatt.STATE_CONNECTED) {
                            Log.d(LOG_TAG, "Connected to GATT server on ${device.address}. Discovering services...")
                            gatt.discoverServices()
                        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                            Log.d(LOG_TAG, "Disconnected from GATT server on ${device.address}")
                            gatt.close()
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val service = gatt.getService(serviceUuid)
                            if (service != null) {
                                val characteristic = service.getCharacteristic(characteristicUuid)
                                if (characteristic != null) {
                                    gatt.readCharacteristic(characteristic)
                                } else {
                                    Log.e(LOG_TAG, "Failed to find characteristic on ${device.address}")
                                }
                            } else {
                                Log.e(LOG_TAG, "Failed to find service on ${device.address}")
                            }
                        } else {
                            Log.w(LOG_TAG, "Service discovery failed with status $status")
                        }
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val connectUri = String(characteristic.value, StandardCharsets.UTF_8)
                            Log.d(LOG_TAG, "GATT Read success: $connectUri")

                            // Callback MUST be invoked on the main thread
                            mainHandler.post {
                                onConnectUriFound(connectUri)
                            }

                            // Disconnect once we got the URI
                            gatt.disconnect()
                        } else {
                            Log.e(LOG_TAG, "Failed to read characteristic, status: $status")
                        }
                    }
                })
            } catch (e: SecurityException) {
                 Log.e(LOG_TAG, "Missing Bluetooth permissions to connect to GATT", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(LOG_TAG, "Cannot start BLE scanning: Bluetooth is disabled")
            return
        }

        if (scanner == null || isScanning) return

        seenDevices.clear() // reset cache on new scan

        try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.d(LOG_TAG, "Started BLE scanning for service UUID: $serviceUuid")
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "Missing Bluetooth permissions to start scanning", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning || scanner == null) return

        try {
            scanner.stopScan(scanCallback)
            isScanning = false
            Log.d(LOG_TAG, "BLE Scanning stopped")
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "Missing Bluetooth permissions to stop scanning", e)
        }
    }
}
