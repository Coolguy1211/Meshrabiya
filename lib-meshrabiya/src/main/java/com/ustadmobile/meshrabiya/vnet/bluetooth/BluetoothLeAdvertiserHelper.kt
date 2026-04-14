package com.ustadmobile.meshrabiya.vnet.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.ustadmobile.meshrabiya.MeshrabiyaConstants.LOG_TAG
import java.nio.charset.StandardCharsets
import java.util.UUID

class BluetoothLeAdvertiserHelper(
    private val context: Context,
    private val serviceUuid: UUID
) {
    private val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    private var isAdvertising = false
    private var currentConnectUri: String? = null
    private var gattServer: BluetoothGattServer? = null

    // Generate a characteristic UUID by slightly altering the service UUID
    private val characteristicUuid = UUID(serviceUuid.mostSignificantBits, serviceUuid.leastSignificantBits xor 1L)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(LOG_TAG, "BLE Advertising started successfully")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(LOG_TAG, "BLE Advertising failed with error code: $errorCode")
            isAdvertising = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothGatt.STATE_CONNECTED && device != null) {
                Log.d(LOG_TAG, "GATT Server client connected: ${device.address}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic.uuid == characteristicUuid) {
                val uriBytes = currentConnectUri?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)

                // BLE limits reads, usually handle MTU size, but we'll return what we can
                val value = if (offset < uriBytes.size) {
                    uriBytes.copyOfRange(offset, uriBytes.size)
                } else {
                    ByteArray(0)
                }

                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, ByteArray(0))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(connectUri: String) {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(LOG_TAG, "Cannot start BLE advertising: Bluetooth is disabled")
            return
        }

        currentConnectUri = connectUri

        if (isAdvertising) {
            Log.d(LOG_TAG, "BLE advertising already running, just updated URI.")
            return
        }

        try {
            // Setup GATT Server to host the full URI
            if (gattServer == null) {
                gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

                val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                val characteristic = BluetoothGattCharacteristic(
                    characteristicUuid,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
                service.addCharacteristic(characteristic)
                gattServer?.addService(service)
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true) // Must be connectable to allow GATT reads
                .build()

            // Just advertise the service UUID so scanners know to connect to this device
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(serviceUuid))
                .build()

            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(LOG_TAG, "Starting BLE advertising with service UUID: $serviceUuid")
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "Missing Bluetooth permissions to start advertising", e)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Exception starting BLE advertising/GATT", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        currentConnectUri = null

        try {
            gattServer?.close()
            gattServer = null
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error closing GATT server", e)
        }

        if (!isAdvertising || advertiser == null) return

        try {
            advertiser.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Log.d(LOG_TAG, "BLE Advertising stopped")
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "Missing Bluetooth permissions to stop advertising", e)
        }
    }
}
