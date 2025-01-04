package com.example.monitoringmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.Composable
import com.example.monitoringmobile.ui.theme.MonitoringMobileTheme
import androidx.core.content.ContextCompat
import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper


class MainActivity : ComponentActivity() {

    private val REQUEST_CODE_PERMISSIONS = 1000
    private val REQUIRED_PERMISSIONS =
        mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).toTypedArray()

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val gpsCheckRunnable = object : Runnable {
        override fun run() {
            if (!isGpsEnabled()) {
                redirectToGpsSettings()
            }
            handler.postDelayed(this, 3000) // Verifica a cada 3 segundos
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Text("Verificando GPS...")
            }
        }

        // Inicia o monitoramento do GPS
        handler.post(gpsCheckRunnable)
    }

    private fun isGpsEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun redirectToGpsSettings() {
        val intent = Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        startActivity(intent)
    }

    override fun onResume() { // Chamado quando a Activity fica visível para o usuário
        super.onResume()
        if (allPermissionsGranted()) {
            startService() // Inicia o serviço SOMENTE se as permissões foram concedidas
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startService()
        } else {
            // Lidar com a negação das permissões
            finish()
        }
    }

    private fun startService() {
        val serviceIntent = Intent(this, MonitoringService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, MonitoringService::class.java))
    }


}