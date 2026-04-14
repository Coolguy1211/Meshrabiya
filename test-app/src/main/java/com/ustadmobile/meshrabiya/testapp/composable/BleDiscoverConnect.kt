package com.ustadmobile.meshrabiya.testapp.composable

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ustadmobile.meshrabiya.MeshrabiyaConstants.LOG_TAG
import com.ustadmobile.meshrabiya.vnet.bluetooth.BluetoothLeScannerHelper
import java.util.UUID

@Composable
fun rememberBleDiscoverConnectLauncher(
    serviceUuid: UUID,
    onConnectUriFound: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    var isScanningRequested by remember { mutableStateOf(false) }

    val scannerHelper = remember(context, serviceUuid) {
        BluetoothLeScannerHelper(context, serviceUuid) { uri ->
            onConnectUriFound(uri)
            isScanningRequested = false
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted && isScanningRequested) {
            scannerHelper.startScanning()
        } else {
            isScanningRequested = false
            Log.e(LOG_TAG, "BLE scan permissions denied")
        }
    }

    DisposableEffect(isScanningRequested) {
        onDispose {
            scannerHelper.stopScanning()
        }
    }

    return {
        isScanningRequested = true

        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            scannerHelper.startScanning()
        }
    }
}
