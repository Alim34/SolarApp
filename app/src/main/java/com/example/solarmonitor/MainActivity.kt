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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://eu1-developer.deyecloud.com/v1.0"

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
            setBackgroundColor(Color.parseColor("#121212"))
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
            setBackgroundColor(Color.parseColor("#121212"))

            val title = TextView(this.context).apply {
                text = "Deye Cloud Настройка"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 40)
            }
            addView(title)

            val appIdInput = EditText(this.context).apply {
                hint = "App ID"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                setText(prefs.getString("app_id", ""))
                setPadding(30, 30, 30, 30)
            }
            val appSecretInput = EditText(this.context).apply {
                hint = "App Secret"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                setText(prefs.getString("app_secret", ""))
                setPadding(30, 30, 30, 30)
            }
            val emailInput = EditText(this.context).apply {
                hint = "Email Deye Cloud"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                setText(prefs.getString("email", ""))
                setPadding(30, 30, 30, 30)
            }
            val passInput = EditText(this.context).apply {
                hint = "Обычный пароль Deye"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(prefs.getString("password", ""))
                setPadding(30, 30, 30, 30)
            }

            val saveBtn = Button(this.context).apply {
                text = "Сохранить и запустить"
                setBackgroundColor(Color.parseColor("#BB86FC"))
                setTextColor(Color.BLACK)
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
                        Toast.makeText(this.context, "Заполните все 4 поля!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            addView(appIdInput)
            addView(appSecretInput)
            addView(emailInput)
            addView(passInput)
            addView(Space(this.context).apply { minimumHeight = 30 })
            addView(saveBtn)
        }
    }

    private fun createDashboardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))

            val title = TextView(this.context).apply {
                text = "Solar Monitor Deye"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 50)
            }
            addView(title)

            statusCard = LinearLayout(this.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 60, 60, 60)
                gravity = Gravity.CENTER
                background = createCardBackground("#1E1E1E")
            }

            statusTitle = TextView(this.context).apply {
                text = "ПРОВЕРКА..."
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
            }

            statusSubtext = TextView(this.context).apply {
                text = "Запрос к Deye Cloud..."
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, 15, 0, 0)
                gravity = Gravity.CENTER
                maxLines = 6
            }

            statusCard.addView(statusTitle)
            statusCard.addView(statusSubtext)
            addView(statusCard)

            lastUpdateText = TextView(this.context).apply {
                text = "Обновлено: --:--:--"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding(0, 30, 0, 40)
            }
            addView(lastUpdateText)

            refreshBtn = Button(this.context).apply {
                text = "🔄 ОБНОВИТЬ СТАТУС"
                setBackgroundColor(Color.parseColor("#BB86FC"))
                setTextColor(Color.BLACK)
                setOnClickListener { fetchStatus() }
            }
            addView(refreshBtn)

            val settingsBtn = Button(this.context).apply {
                text = "⚙️ Настройки аккаунта"
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.parseColor("#A0A0A0"))
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
        statusCard.background = createCardBackground("#2D2B1E")
        statusTitle.setTextColor(Color.parseColor("#FBBF24"))
        statusSubtext.text = "Подключение через VPN..."

        thread {
            val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
            val appId = (prefs.getString("app_id", "") ?: "").trim()
            val appSecret = (prefs.getString("app_secret", "") ?: "").trim()
            val email = (prefs.getString("email", "") ?: "").trim()
            val password = (prefs.getString("password", "") ?: "").trim()

            var errorDetail = ""
            val token = authenticate(appId, appSecret, email, password) { err -> errorDetail = err }
            val isOnline = if (token != null) checkStationStatus(token) { err -> errorDetail = err } else null

            runOnUiThread {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                lastUpdateText.text = "Обновлено в $time"

                if (isOnline == true) {
                    statusCard.background = createCardBackground("#064E3B")
                    statusTitle.text = "🟢 СЕТЬ В НОРМЕ"
                    statusTitle.setTextColor(Color.parseColor("#A7F3D0"))
                    statusSubtext.text = "Инвертор в сети / Питание подается"
                } else if (isOnline == false) {
                    statusCard.background = createCardBackground("#7F1D1D")
                    statusTitle.text = "🔴 НЕТ СЕТИ / ОТКЛЮЧЕНИЕ"
                    statusTitle.setTextColor(Color.parseColor("#FECACA"))
                    statusSubtext.text = "Станция оффлайн или пропал свет"
                } else {
                    statusCard.background = createCardBackground("#1F2937")
                    statusTitle.text = "⚠️ ОШИБКА СВЯЗИ"
                    statusTitle.setTextColor(Color.parseColor("#F3F4F6"))
                    statusSubtext.text = if (errorDetail.isNotEmpty()) errorDetail else "Проверьте VPN или данные"
                }
            }
        }
    }

    private fun authenticate(appId: String, appSecret: String, email: String, pass: String, onError: (String) -> Unit): String? {
        return try {
            val sha256Password = pass.toSha256()
            val json = JsonObject().apply {
                addProperty("appSecret", appSecret)
                addProperty("email", email)
                addProperty("password", sha256Password)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/account/token?appId=$appId")
                .post(body)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Android 13; Mobile)")
                .build()

            client.newCall(request).execute().use { response ->
                val responseData = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val jsonRes = JsonParser.parseString(responseData).asJsonObject
                    val success = jsonRes.get("success")?.asBoolean ?: false
                    val code = jsonRes.get("code")?.asString ?: ""
                    if (success && code == "1000000") {
                        jsonRes.get("accessToken")?.asString ?: run {
                            onError("Токен отсутствует в ответе")
                            null
                        }
                    } else {
                        val msg = jsonRes.get("msg")?.asString ?: "Неверный логин/пароль"
                        onError("Ошибка Deye: $msg")
                        null
                    }
                } else {
                    val code = response.code
                    val cleanError = responseData.replace("\n", " ").replace("\r", " ").take(100)
                    onError("Auth $code: $cleanError")
                    null
                }
            }
        } catch (e: Exception) {
            onError("Auth Crash: ${e.localizedMessage}")
            null
        }
    }

    private fun checkStationStatus(token: String, onError: (String) -> Unit): Boolean? {
        return try {
            val json = JsonObject().apply {
                addProperty("page", 1)
                addProperty("size", 10)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/station/list")
                .post(body)
                .addHeader("Authorization", "bearer $token")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Android 13; Mobile)")
                .build()

            client.newCall(request).execute().use { response ->
                val responseData = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val jsonRes = JsonParser.parseString(responseData).asJsonObject
                    val success = jsonRes.get("success")?.asBoolean ?: false
                    if (success) {
                        val stationList = jsonRes.getAsJsonArray("stationList")
                            ?: jsonRes.getAsJsonObject("data")?.getAsJsonArray("list")
                            ?: jsonRes.getAsJsonArray("list")

                        if (stationList != null && stationList.size() > 0) {
                            val firstStation = stationList[0].asJsonObject
                            val status = firstStation.get("status")?.asInt
                            if (status != null) {
                                status == 1
                            } else {
                                true
                            }
                        } else {
                            true
                        }
                    } else {
                        val msg = jsonRes.get("msg")?.asString ?: "Не удалось получить статус"
                        onError("Stat Error: $msg")
                        null
                    }
                } else {
                    val code = response.code
                    val cleanError = responseData.replace("\n", " ").replace("\r", " ").take(100)
                    onError("Stat $code: $cleanError")
                    null
                }
            }
        } catch (e: Exception) {
            onError("Stat Crash: ${e.localizedMessage}")
            null
        }
    }

    private fun createCardBackground(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = 24f
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

    private fun String.toSha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
