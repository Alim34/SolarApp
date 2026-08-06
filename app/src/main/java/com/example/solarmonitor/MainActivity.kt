package com.example.solarmonitor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val client = OkHttpClient()

    private lateinit var setupLayout: LinearLayout
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var refreshBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6F9"))
        }

        setupLayout = createSetupView()
        dashboardLayout = createDashboardView()

        rootLayout.addView(setupLayout)
        rootLayout.addView(dashboardLayout)

        setContentView(rootLayout)

        val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
        val appId = prefs.getString("app_id", "") ?: ""

        if (appId.isNotEmpty()) {
            showDashboard()
        } else {
            showSetup()
        }
    }

    private fun createSetupView(): LinearLayout {
        val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 80)
            gravity = Gravity.CENTER_HORIZONTAL

            val title = TextView(context).apply {
                text = "Deye Cloud Настройка"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1F2937"))
                setPadding(0, 0, 0, 40)
            }
            addView(title)

            val appIdInput = EditText(context).apply {
                hint = "App ID"
                setText(prefs.getString("app_id", ""))
                setPadding(30, 30, 30, 30)
            }
            val appSecretInput = EditText(context).apply {
                hint = "App Secret"
                setText(prefs.getString("app_secret", ""))
                setPadding(30, 30, 30, 30)
            }
            val emailInput = EditText(context).apply {
                hint = "Email Deye Cloud"
                setText(prefs.getString("email", ""))
                setPadding(30, 30, 30, 30)
            }
            val passInput = EditText(context).apply {
                hint = "Пароль Deye Cloud"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(prefs.getString("password", ""))
                setPadding(30, 30, 30, 30)
            }

            val saveBtn = Button(context).apply {
                text = "Сохранить и запустить"
                setBackgroundColor(Color.parseColor("#2563EB"))
                setTextColor(Color.WHITE)
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
                        showDashboard()
                    } else {
                        Toast.makeText(context, "Заполните все 4 поля!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            addView(appIdInput)
            addView(appSecretInput)
            addView(emailInput)
            addView(passInput)
            addView(Space(context).apply { minimumHeight = 30 })
            addView(saveBtn)
        }
    }

    private fun createDashboardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            gravity = Gravity.CENTER_HORIZONTAL

            val title = TextView(context).apply {
                text = "Solar Monitor Deye"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#4B5563"))
                setPadding(0, 0, 0, 40)
            }
            addView(title)

            // Статусная карточка
            statusCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 60, 60, 60)
                gravity = Gravity.CENTER
                background = createCardBackground("#E5E7EB")
            }

            statusTitle = TextView(context).apply {
                text = "ПРОВЕРКА..."
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#374151"))
                gravity = Gravity.CENTER
            }

            statusSubtext = TextView(context).apply {
                text = "Подключение к Deye Cloud"
                textSize = 14f
                setTextColor(Color.parseColor("#4B5563"))
                setPadding(0, 10, 0, 0)
                gravity = Gravity.CENTER
            }

            statusCard.addView(statusTitle)
            statusCard.addView(statusSubtext)
            addView(statusCard)

            lastUpdateText = TextView(context).apply {
                text = "Обновлено: --:--:--"
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 25, 0, 40)
            }
            addView(lastUpdateText)

            refreshBtn = Button(context).apply {
                text = "🔄 Обновить статус"
                setBackgroundColor(Color.parseColor("#2563EB"))
                setTextColor(Color.WHITE)
                setOnClickListener { fetchStatus() }
            }
            addView(refreshBtn)

            val settingsBtn = Button(context).apply {
                text = "⚙️ Настройки аккаунта"
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.parseColor("#6B7280"))
                setOnClickListener { showSetup() }
            }
            addView(settingsBtn)
        }
    }

    private fun showSetup() {
        setupLayout.visibility = View.VISIBLE
        dashboardLayout.visibility = View.GONE
    }

    private fun showDashboard() {
        setupLayout.visibility = View.GONE
        dashboardLayout.visibility = View.VISIBLE
        fetchStatus()
    }

    private fun fetchStatus() {
        statusTitle.text = "ЗАГРУЗКА..."
        statusCard.background = createCardBackground("#FEF3C7") // Желтый
        statusTitle.setTextColor(Color.parseColor("#92400E"))
        statusSubtext.text = "Запрос к Deye Cloud API"

        thread {
            val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
            val appId = prefs.getString("app_id", "") ?: ""
            val appSecret = prefs.getString("app_secret", "") ?: ""
            val email = prefs.getString("email", "") ?: ""
            val password = prefs.getString("password", "") ?: ""

            val token = authenticate(appId, appSecret, email, password)
            val isOnline = if (token != null) checkStationStatus(token) else null

            runOnUiThread {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                lastUpdateText.text = "Обновлено в $time"

                if (isOnline == true) {
                    statusCard.background = createCardBackground("#D1FAE5") // Зеленый
                    statusTitle.text = "🟢 СЕТЬ В НОРМЕ"
                    statusTitle.setTextColor(Color.parseColor("#065F46"))
                    statusSubtext.text = "Инвертор в сети / Питание подается"
                } else if (isOnline == false) {
                    statusCard.background = createCardBackground("#FEE2E2") // Красный
                    statusTitle.text = "🔴 НЕТ СЕТИ / ОТКЛЮЧЕНИЕ"
                    statusTitle.setTextColor(Color.parseColor("#991B1B"))
                    statusSubtext.text = "Станция оффлайн или пропал свет"
                } else {
                    statusCard.background = createCardBackground("#F3F4F6") // Серый
                    statusTitle.text = "⚠️ ОШИБКА СВЯЗИ"
                    statusTitle.setTextColor(Color.parseColor("#374151"))
                    statusSubtext.text = "Проверьте App ID / Secret или интернет"
                }
            }
        }
    }

    private fun authenticate(appId: String, appSecret: String, email: String, pass: String): String? {
        return try {
            val json = JsonObject().apply {
                addProperty("appId", appId)
                addProperty("appSecret", appSecret)
                addProperty("email", email)
                addProperty("password", pass)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://openapi.deyecloud.com/v1.0/account/token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseData = response.body?.string() ?: return null
                val jsonRes = JsonParser.parseString(responseData).asJsonObject
                if (jsonRes.get("code")?.asInt == 0) {
                    jsonRes.getAsJsonObject("data")?.get("accessToken")?.asString
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkStationStatus(token: String): Boolean? {
        return try {
            val request = Request.Builder()
                .url("https://openapi.deyecloud.com/v1.0/station/list")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseData = response.body?.string() ?: return null
                val jsonRes = JsonParser.parseString(responseData).asJsonObject
                if (jsonRes.get("code")?.asInt == 0) {
                    val list = jsonRes.getAsJsonArray("data")
                    if (list != null && list.size() > 0) {
                        val station = list.get(0).asJsonObject
                        val status = station.get("status")?.asInt ?: 2
                        status == 1
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createCardBackground(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = 30f
        }
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
