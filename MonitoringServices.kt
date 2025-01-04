package com.example.monitoringmobile

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.time.LocalTime
import java.util.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.monitoringmobile.R


class MonitoringService : Service() {

    private val CHANNEL_ID = "MonitoringChannel"
    private val NOTIFICATION_ID = 1
    private val TAG = "MonitoringService"
    private var locationManager: LocationManager? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private var lastLocation: Location? = null
    private val client = OkHttpClient()
    private val gson = Gson()
    private lateinit var handler: Handler
    private lateinit var runnable: Runnable

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupLocationManager()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Monitoring:WakeLock")
        handler = Handler(Looper.getMainLooper())

        runnable = object : Runnable {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun run() {
                val currentTime = LocalTime.now()
                if (currentTime.hour in 8..16) {
                    wakeLock.acquire()
                    collectAndSendData()
                    if (!wakeLock.isHeld){
                        wakeLock.release()
                    }
                    Log.d(TAG, "Service running ${currentTime.hour}")
                }else{
                    Log.d(TAG, "Service off ${currentTime.hour}")
                }
                handler.postDelayed(this, 10000) // 10 seconds
            }
        }

        handler.post(runnable)
        startForeground(NOTIFICATION_ID, createNotification()) // Start as foreground service
    }
    private fun setupLocationManager() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permissions not granted.")
            return
        }
        try {
            // requestLocationUpdates
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,locationListener)
        }catch(ex:IllegalArgumentException){
            Log.e(TAG, "Error trying to use LocationManager. ${ex.message}")
        }

    }
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
        locationManager?.removeUpdates(locationListener)
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }


    private fun collectAndSendData() {
        // Collect data
        val cpuUsage = getCpuUsage()
        val memoryInfo = getMemoryInfo()
        val storageInfo = getStorageInfo()
        val networkInfo = getNetworkInfo()
        val locationInfo = getLocationInfo()

        // Prepare data
        val monitoringData = MonitoringData(
            cpuUsage,
            memoryInfo,
            storageInfo,
            networkInfo,
            locationInfo
        )
        println("Dados Coletados:")
        println("  CPU Usage: $cpuUsage")
        println("  Memory: $memoryInfo")
        println("  Storage: $storageInfo")
        println("  Network: $networkInfo")
        println("  Location: $locationInfo")

        val jsonData = gson.toJson(monitoringData)
        Log.d(TAG,jsonData)
        sendDataToZabbix(jsonData)
    }

    private fun sendDataToZabbix(jsonData: String) {

        val mediaType = "application/json".toMediaType()
        val requestBody = RequestBody.create(mediaType, jsonData)
        val request = Request.Builder()
            .url("http://92.113.38.123/zabbix/api_jsonrpc.php")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Error sending data to Zabbix: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Data sent successfully to Zabbix.")
                } else {
                    Log.e(TAG, "Error sending data to Zabbix: ${response.code}")
                }
            }
        })
    }

    private fun getCpuUsage(): String {
        return "Not Implemented yet"
    }
    private fun getMemoryInfo(): MemoryInfo {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return MemoryInfo(
            memoryInfo.totalMem,
            memoryInfo.availMem
        )
    }
    private fun getStorageInfo(): StorageInfo {
        val statFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val totalSpace = statFs.totalBytes
        val availableSpace = statFs.availableBytes
        return StorageInfo(
            totalSpace,
            availableSpace
        )
    }
    private fun getNetworkInfo(): NetworkInfo {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        var connectionType = "No Connection"

        if(capabilities != null){
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                connectionType = "Wi-Fi"
            }else if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)){
                connectionType = "Mobile Data"
            }
        }
        return NetworkInfo(connectionType)
    }

    private fun getLocationInfo(): LocationInfo {
        return if (lastLocation != null) {
            LocationInfo(lastLocation!!.latitude,lastLocation!!.longitude)
        }else{
            LocationInfo(null,null)
        }
    }
    data class MonitoringData(
        val cpuUsage: String,
        val memory: MemoryInfo,
        val storage: StorageInfo,
        val network: NetworkInfo,
        val location: LocationInfo
    )
    data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long
    )
    data class StorageInfo(
        val totalSpace: Long,
        val availableSpace: Long
    )
    data class NetworkInfo(
        val connectionType: String
    )
    data class LocationInfo(
        val latitude: Double?,
        val longitude: Double?
    )

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Celular Monitorado - Mar Brasil - Bruno Oliveira")
            .setContentText("Monitorando o seu dispositivo - Bruno Oliveira")
            .setSmallIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)) // Use seu icone aqui
            .setContentIntent(pendingIntent)
            .build()
    }
}

