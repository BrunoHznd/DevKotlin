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
import android.app.Service
import android.os.IBinder
import android.os.AsyncTask
import android.content.SharedPreferences
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import android.content.IntentFilter
import android.os.BatteryManager
import java.net.InetAddress
import java.net.NetworkInterface
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalTime
import java.util.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.monitoringmobile.R
import java.io.BufferedReader
import java.io.FileReader
import java.util.UUID
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.Charset
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import org.json.JSONException








class MonitoringService : Service() {

    private var lastKnownIp: String? = null
    private val interval: Long = 300000L
    private val UUID_KEY = "unique_device_uuid"
    private lateinit var sharedPreferences: SharedPreferences
    private val CHANNEL_ID = "MonitoringChannel"
    private val NOTIFICATION_ID = 1
    private val TAG = "MonitoringService"
    private var locationManager: LocationManager? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false
    private lateinit var wakeLock: PowerManager.WakeLock


    //Token da API
    val zabbixManager =
        ZabbixHostManager(authToken = "9d3c54b3728d7dd6233b18e90e1ca681ba50c403ca76295ab418da93087a0d6d")


    companion object {
        const val ZABBIX_SERVER = "92.113.38.123" // Substitua pelo IP/hostname
        const val ZABBIX_PORT = 10051
        const val SEND_INTERVAL = 10000L // Intervalo de envio em milissegundos (1 minuto)
    }

    private val locationListener = object : LocationListener {


        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var lastLocation: Location? = null
    private val client = OkHttpClient()
    private val gson = Gson()
    private lateinit var handler: Handler
    private lateinit var runnable: Runnable

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupLocationManager()

        // Inicializando SharedPreferences
        sharedPreferences = getSharedPreferences("DevicePreferences", Context.MODE_PRIVATE)

        // Verifica se já existe um UUID armazenado, senão, gera e armazena um novo
        val uuid = getOrCreateUUID()
        println("UUID: $uuid")

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
                if (currentTime.hour in 7..17) { // monitora o celular somente no periodo das 07:00 até 17:00
                    wakeLock.acquire()
                    collectAndSendData()
                    if (!wakeLock.isHeld) {
                        wakeLock.release()
                    }
                    collectAndSendData()
                    Log.d(TAG, "Service running ${currentTime.hour}")
                } else {
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
        Log.d(TAG, jsonData)
        sendDataToZabbix(jsonData)
    }

    private fun sendDataToZabbix(jsonData: String) {
        // Recupera o UUID do dispositivo
        val uuid = getOrCreateUUID()

        // Adiciona o UUID aos dados
        val dataWithUUID = jsonData.replace("}", ", \"uuid\": \"$uuid\"}")

        val mediaType = "application/json".toMediaType()
        val requestBody = RequestBody.create(mediaType, dataWithUUID)
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

    private fun getOrCreateUUID(): String {
        // Verifica se já existe um UUID armazenado
        val uuid = sharedPreferences.getString(UUID_KEY, null)

        return if (uuid != null) {
            // Se o UUID já estiver armazenado, retorna ele
            uuid
        } else {
            // Se não existir, gera um novo UUID, armazena e retorna ele
            val newUUID = UUID.randomUUID().toString()
            sharedPreferences.edit().putString(UUID_KEY, newUUID).apply()
            newUUID
        }
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

                        val totalCpuTime =
                            totalIdle + totalSystem + totalUser + totalNice + totalIoWait + totalIrq + totalSoftIrq
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

        val totalMemoryInGB =
            (memoryInfo.totalMem / (1024 * 1024 * 1024)).toDouble() // Converte para Double
        val availableMemoryInGB =
            (memoryInfo.availMem / (1024 * 1024 * 1024)).toDouble() // Converte para Double

        return MemoryInfo(totalMemoryInGB, availableMemoryInGB)
    }


    private fun getStorageInfo(): StorageInfo {
        val statFs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val totalSpaceInGB =
            (statFs.totalBytes / (1024 * 1024 * 1024)).toDouble() // Converte para Double
        val availableSpaceInGB =
            (statFs.availableBytes / (1024 * 1024 * 1024)).toDouble() // Converte para Double

        return StorageInfo(totalSpaceInGB, availableSpaceInGB)
    }

    private fun getNetworkInfo(): NetworkInfo {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        var connectionType = "No Connection"

        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                connectionType = "Wi-Fi"
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                connectionType = "Mobile Data"
            }
        }
        return NetworkInfo(connectionType)
    }

    private fun startMonitoring() {
        scope.launch {
            val hostName = obterNomeDoHost(applicationContext) // Obtém o nome do host ou UUID
            while (isRunning) {
                try {
                    // Coleta de dados
                    val cpuUsage = getCpuUsage()
                    val memoryInfo = getMemoryInfo()
                    val storageInfo = getStorageInfo()
                    val networkInfo = getNetworkInfo()
                    val locationInfo = getLocationInfo()
                    val batteryStatus = getBatteryStatus(applicationContext)

                    // Organize os dados em um mapa
                    val dadosParaEnviar = mapOf(
                        "cpu_usage" to cpuUsage,
                        "memory_info" to memoryInfo.toString(),
                        "storage_info" to storageInfo.toString(),
                        "network_info" to networkInfo.connectionType,
                        "location_info" to locationInfo.toString(),
                        "battery_status" to batteryStatus.toString()
                    )

                    // Envia os dados para o Zabbix
                    enviarDadosParaZabbix(hostName, dadosParaEnviar)
                    Log.d(TAG, "Dados enviados para o Zabbix: $dadosParaEnviar")

                } catch (e: Exception) {
                    Log.e(TAG, "Erro durante a coleta/envio: ${e.message}", e)
                }

                delay(SEND_INTERVAL)
            }
        }
    }


    private fun enviarDadosParaZabbix(host: String, dados: Map<String, String>) {
        scope.launch {
            try {
                val zabbixServerAddress = InetSocketAddress(ZABBIX_SERVER, ZABBIX_PORT)
                val channel = SocketChannel.open(zabbixServerAddress)

                val dataToSend = StringBuilder()
                dados.forEach { (key, value) ->
                    dataToSend.append("$host $key $value\n")
                }
                val charset = Charset.forName("UTF-8")
                val buffer = charset.encode(dataToSend.toString())

                var bytesWritten = 0
                while (bytesWritten < buffer.limit()) {
                    bytesWritten += channel.write(buffer) //Escreve no canal
                }


                val responseBuffer = ByteBuffer.allocate(1024)
                channel.read(responseBuffer)
                responseBuffer.flip()
                val response = charset.decode(responseBuffer).toString().trim()
                channel.close()

                if (response.contains("\"response\":\"success\"")) {
                    Log.d(TAG, "Dados enviados: $response")
                } else {
                    Log.e(TAG, "Erro ao enviar: $response")
                }

            } catch (e: IOException) {
                Log.e(TAG, "Erro de IO: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Erro geral no envio: ${e.message}", e)
            }
        }
    }

    private fun obterNomeDoHost(context: Context): String {
        val uuidFile = File(context.filesDir, "device_uuid.txt")
        return if (uuidFile.exists()) {
            uuidFile.readText()
        } else {
            val uuid = UUID.randomUUID().toString()
            uuidFile.writeText(uuid)
            uuid
        }
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
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,
                0f,
                locationListener
            )
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
            val location =
                locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) //Seila porque ta essa porra dessa cobra vermelha falando que é bug. Mas ta rodando e logo se ta rodando não é Bug é enfeite de código
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
        val batteryStatusIntent: Intent? =
            IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
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
            .setSmallIcon(
                IconCompat.createWithResource(
                    this,
                    R.mipmap.ic_launcher
                )
            ) // Use seu icone aqui
            .setContentIntent(pendingIntent)
            .build()
    }

    // aqui o Pau Vai Cumer Solto AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
    // ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
    // nesse ponto em diante ja não sei mais oq ta acontecendo


    // api do zabbix -

    class ZabbixHostManager(private val authToken: String) {

        private val zabbixApiUrl = "http://92.113.38.123/zabbix/api_jsonrpc.php"
        private var hostId: String? = null
        private var interfaceId: String? = null
        private var lastKnownIp: String? = null
        private val TAG = "MonitoringService"
        private var groupId: Int = 35 // ID do grupo, agora como Int diretamente

        fun manageHost(hostName: String, templateId: String) {
            val currentIp = getDeviceIp()
            if (currentIp != null) {
                if (hostId == null) {
                    createHost(hostName, currentIp, templateId)
                } else {
                    if (currentIp != lastKnownIp) {
                        updateHostInterface(currentIp)
                    }
                }
            }
        }

        private fun updateHostInterface(newIp: String) {
            if (hostId != null && interfaceId != null) {
                val jsonBody = """
            {
                "jsonrpc": "2.0",
                "method": "host.update",
                "params": {
                    "hostid": "$hostId",
                    "interfaces": [
                        {
                            "interfaceid": "$interfaceId",
                            "type": 1,
                            "main": 1,
                            "useip": 1,
                            "ip": "$newIp",
                            "dns": "",
                            "port": "10050"
                        }
                    ]
                },
                "auth": "$authToken",
                "id": 1
            }
            """.trimIndent()

                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(zabbixApiUrl)
                    .post(RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Failed to update host interface: ${e.message}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string()
                            Log.d(TAG, "Response: $responseBody")

                            try {
                                val jsonResponse = JSONObject(responseBody)
                                if (jsonResponse.has("error")) {
                                    val error = jsonResponse.getJSONObject("error")
                                    Log.e(
                                        TAG,
                                        "Error updating host interface: ${error.getString("message")}"
                                    )
                                } else {
                                    Log.d(TAG, "Host interface updated successfully")
                                    lastKnownIp = newIp  // Atualiza o IP conhecido
                                }
                            } catch (e: JSONException) {
                                Log.e(TAG, "Failed to parse response: ${e.message}")
                            }
                        } else {
                            Log.e(TAG, "Error in response: ${response.message}")
                        }
                    }
                })
            } else {
                Log.e(TAG, "hostId or interfaceId is null. Cannot update host interface.")
            }
        }

        // Obter IP do dispositivo
        private fun getDeviceIp(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val inetAddress = addresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }

        // Criar o host no Zabbix
        private fun createHost(hostName: String, ip: String, templateId: String) {
            val jsonBody = """
        {
            "jsonrpc": "2.0",
            "method": "host.create",
            "params": {
                "host": "$hostName",
                "interfaces": [
                    {
                        "type": 1,
                        "main": 1,
                        "useip": 1,
                        "ip": "$ip",
                        "dns": "",
                        "port": "10050"
                    }
                ],
                "groups": [
                    {
                        "groupid": $groupId
                    }
                ],
                "templates": [
                    {
                        "templateid": "$templateId"
                    }
                ]
            },
            "auth": "$authToken",
            "id": 1
        }
    """.trimIndent()

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(zabbixApiUrl)
                .post(RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody))
                .build()

            Log.d(
                TAG,
                "Sending request to Zabbix API: $jsonBody"
            )  // Log antes de enviar a requisição

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Request failed: ${e.message}")  // Log de falha na requisição
                }

                override fun onResponse(call: Call, response: Response) {
                    Log.d(
                        TAG,
                        "Received response: ${response.code} ${response.message}"
                    )  // Log do código de resposta HTTP

                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        Log.d(TAG, "Response Body: $responseBody")  // Log do corpo da resposta

                        try {
                            val jsonResponse = JSONObject(responseBody)
                            if (jsonResponse.has("error")) {
                                val error = jsonResponse.getJSONObject("error")
                                Log.e(TAG, "Error creating host: ${error.getString("message")}")
                            } else {
                                if (jsonResponse.has("result")) {
                                    val result = jsonResponse.getJSONObject("result")
                                    val hostIds = result.getJSONArray("hostids")
                                    if (hostIds.length() > 0) {
                                        hostId = hostIds.getString(0)
                                        val interfaces = result.getJSONArray("interfaces")
                                        if (interfaces.length() > 0) {
                                            interfaceId =
                                                interfaces.getJSONObject(0).getString("interfaceid")
                                            lastKnownIp = ip
                                            Log.d(
                                                TAG,
                                                "Host created successfully with hostId: $hostId and interfaceId: $interfaceId"
                                            )
                                        } else {
                                            Log.e(TAG, "No interfaces found in the result.")
                                        }
                                    } else {
                                        Log.e(TAG, "No host IDs found in the result.")
                                    }
                                } else {
                                    Log.e(TAG, "Missing result in response.")
                                }
                            }
                        } catch (e: JSONException) {
                            Log.e(TAG, "Failed to parse response: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "Error in response: ${response.code} ${response.message}")
                    }
                }
            })
        }
    }
}