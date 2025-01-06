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
import android.content.IntentFilter
import android.os.BatteryManager
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.time.LocalTime
import java.util.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.monitoringmobile.R
import java.io.BufferedReader
import java.io.FileReader
import android.os.Handler
import android.os.Looper



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

        if (!isGpsEnabled()) {
            promptToEnableGps()
        }

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
        val batteryStatus = getBatteryStatus(applicationContext)

        // Prepare data
        val monitoringData = MonitoringData(
            cpuUsage,
            memoryInfo,
            storageInfo,
            networkInfo,
            locationInfo,
            batteryStatus
        )
        println("Dados Coletados:")
        println("  uso da CPU:")
        println(cpuUsage)
        println("  Memoria: $memoryInfo")
        println("  Armazenamento: $storageInfo")
        println("  Internet: $networkInfo")
        println("  Localização: $locationInfo")
        println("  Bateria: ${batteryStatus.batteryPercentage}%")
        println("  Carregando: ${batteryStatus.isCharging}")
        println("  Forma de Carregamento: ${batteryStatus.chargingSource}")


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
        val cpuUsages = mutableListOf<String>()
        val cpuInfoFile = "/proc/stat"
        try {
            // Ler o arquivo /proc/stat
            val reader = BufferedReader(FileReader(cpuInfoFile))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("cpu")) {
                    // Processar a linha para coletar dados dos núcleos
                    val tokens = line!!.split("\\s+".toRegex())
                    if (tokens[0] == "cpu") {
                        // O primeiro "cpu" refere-se ao uso agregado de todos os núcleos
                        // Para cada núcleo individual, podemos pegar a linha "cpu0", "cpu1", etc.
                        val totalIdle = tokens[4].toLong()  // O valor de "idle"
                        val totalSystem = tokens[3].toLong() // O valor de "system"
                        val totalUser = tokens[2].toLong()  // O valor de "user"
                        val totalNice = tokens[5].toLong()  // O valor de "nice"
                        val totalIoWait = tokens[6].toLong() // O valor de "iowait"
                        val totalIrq = tokens[7].toLong()   // O valor de "irq"
                        val totalSoftIrq = tokens[8].toLong()  // O valor de "softirq"

                        val totalCpuTime = totalIdle + totalSystem + totalUser + totalNice + totalIoWait + totalIrq + totalSoftIrq
                        val totalIdleTime = totalIdle + totalIoWait
                        val usage = (100 * (totalCpuTime - totalIdleTime) / totalCpuTime).toInt()

                        cpuUsages.add("CPU Usage: $usage%")
                    }
                }
            }
            reader.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error reading CPU info: ${e.message}")
        }

        return cpuUsages.joinToString("\n")
    }

    private fun getMemoryInfo(): MemoryInfo {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemoryInGB = (memoryInfo.totalMem / (1024 * 1024 * 1024)).toDouble() // Converte para Double
        val availableMemoryInGB = (memoryInfo.availMem / (1024 * 1024 * 1024)).toDouble() // Converte para Double

        return MemoryInfo(totalMemoryInGB, availableMemoryInGB)
    }


    private fun getStorageInfo(): StorageInfo {
        val statFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val totalSpaceInGB = (statFs.totalBytes / (1024 * 1024 * 1024)).toDouble() // Converte para Double
        val availableSpaceInGB = (statFs.availableBytes / (1024 * 1024 * 1024)).toDouble() // Converte para Double

        return StorageInfo(totalSpaceInGB, availableSpaceInGB)
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
            // Solicitar atualizações de localização
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)
        } catch (ex: IllegalArgumentException) {
            Log.e(TAG, "Error trying to use LocationManager. ${ex.message}")
        }
    }

    private fun getLocationInfo(): LocationInfo {
        return if (lastLocation != null) {
            // Se a última localização não for nula, retornamos os dados
            LocationInfo(lastLocation!!.latitude, lastLocation!!.longitude)
        } else {
            // Caso contrário, verificamos a localização atual
            val location = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location != null) {
                LocationInfo(location.latitude, location.longitude)
            } else {
                LocationInfo(null, null) // Se não houver localização conhecida
            }
        }
    }

    private fun blockUntilGpsIsEnabled() {
        while (!isGpsEnabled()) {
            Thread.sleep(1000)
        }
        Log.d(TAG, "GPS ativado")
    }


    fun getBatteryStatus(context: Context): BatteryStatus {
        val batteryStatusIntent: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }

        val level = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatusIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        // Calcular a porcentagem de bateria
        val batteryPercentage = if (level != -1 && scale != -1) {
            (level / scale.toFloat()) * 100
        } else {
            -1f
        }

        // Verificar o status de carregamento
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> true
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }

        // Verificar o tipo de carregamento
        val chargingSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Not Charging"
        }

        return BatteryStatus(batteryPercentage, charging, chargingSource)
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun promptToEnableGps() {
        val intent = Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    // Data class to store battery status information
    data class BatteryStatus(
        val batteryPercentage: Float,
        val isCharging: Boolean,
        val chargingSource: String
    )

    data class MonitoringData(
        val cpuUsage: String,
        val memory: MemoryInfo,
        val storage: StorageInfo,
        val network: NetworkInfo,
        val location: LocationInfo,
        val batteryStatus: BatteryStatus
    )
    data class MemoryInfo(val totalMemory: Double, val availableMemory: Double)

    data class StorageInfo(val totalSpace: Double, val availableSpace: Double)

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