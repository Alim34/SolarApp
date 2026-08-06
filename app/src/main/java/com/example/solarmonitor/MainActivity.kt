package com.example.solarmonitor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val appIdInput = EditText(this).apply {
            hint = "App ID"
            setText(prefs.getString("app_id", ""))
        }

        val appSecretInput = EditText(this).apply {
            hint = "App Secret"
            setText(prefs.getString("app_secret", ""))
        }

        val emailInput = EditText(this).apply {
            hint = "Email / Логин Deye Cloud"
            setText(prefs.getString("email", ""))
        }

        val passInput = EditText(this).apply {
            hint = "Пароль Deye Cloud"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("password", ""))
        }

        val startBtn = Button(this).apply {
            text = "Сохранить и запустить мониторинг Deye"
            setOnClickListener {
                val appId = appIdInput.text.toString().trim()
                val appSecret = appSecretInput.text.toString().trim()
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString().trim()

                if (appId.isNotEmpty() && appSecret.isNotEmpty() && email.isNotEmpty() && pass.isNotEmpty()) {
                    prefs.edit()
                        .putString("app_id", appId)
                        .putString("app_secret", appSecret)
                        .putString("email", email)
                        .putString("password", pass)
                        .apply()
                    scheduleWorker()
                    Toast.makeText(context, "Мониторинг Deye Cloud запущен!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Заполните все 4 поля!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(appIdInput)
        layout.addView(appSecretInput)
        layout.addView(emailInput)
        layout.addView(passInput)
        layout.addView(startBtn)

        setContentView(layout)
    }

    private fun scheduleWorker() {
        val workRequest = PeriodicWorkRequestBuilder<DeyeCloudWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DeyeCloudCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
