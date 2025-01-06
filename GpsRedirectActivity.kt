 /** package com.example.monitoringmobile

 import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GpsRedirectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gps_redirect)

        val textView = findViewById<TextView>(R.id.gpsMessage)
        val enableGpsButton = findViewById<Button>(R.id.enableGpsButton)

        textView.text = "O GPS está desativado. Por favor, ative o GPS para continuar o monitoramento."

        enableGpsButton.setOnClickListener {
            // Redireciona o usuário para as configurações de localização
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }
    }
}
 */