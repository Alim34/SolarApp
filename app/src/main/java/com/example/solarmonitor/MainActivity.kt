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
import kotlin.math.abs

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

    data class CheckResult(
        val isGridOn: Boolean,
        val titleText: String,
        val details: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        setupLayout = createSetupView()
        dashboardLayout = createDashboardView()
        rootLayout.addView(setupLayout)
        rootLayout.addView(dashboardLayout)
        setContentView(rootLayout)

        val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
        if (prefs.getString("app_id", "").isNullOrEmpty()) showSetup() else showDashboard()
    }

    private fun createSetupView(): LinearLayout {
        val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))

            val title = TextView(this.context).apply {
                text = "Настройки Deye Cloud"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 30)
            }
            addView(title)

            val appIdInput = createInputField("App ID", prefs.getString("app_id", ""))
            val appSecretInput = createInputField("App Secret", prefs.getString("app_secret", ""))
            val emailInput = createInputField("Email", prefs.getString("email", ""))
            val passInput = createInputField("Пароль", prefs.getString("password", ""), isPassword = true)

            val saveBtn = Button(this.context).apply {
                text = "Сохранить и запустить"
                setBackgroundColor(Color.parseColor("#BB86FC"))
                setTextColor(Color.BLACK)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setPadding(20, 25, 20, 25)
                setOnClickListener {
                    prefs.edit()
                        .putString("app_id", appIdInput.text.toString().trim())
                        .putString("app_secret", appSecretInput.text.toString().trim())
                        .putString("email", emailInput.text.toString().trim())
                        .putString("password", passInput.text.toString().trim())
                        .apply()
                    scheduleWorker()
                    showDashboard()
                }
            }
            
            addView(Space(this.context).apply { minimumHeight = 20 })
            addView(saveBtn)
        }
    }

    private fun LinearLayout.createInputField(label: String, initialValue: String?, isPassword: Boolean = false): EditText {
        val container = LinearLayout(this.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 15)
        }
        val labelView = TextView(this.context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#BB86FC"))
            setPadding(5, 0, 0, 8)
        }
        val editText = EditText(this.context).apply {
            hint = "Введите $label"
            setHintTextColor(Color.parseColor("#777777"))
            setText(initialValue ?: "")
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(30, 25, 30, 25)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                setStroke(2, Color.parseColor("#333333"))
                cornerRadius = 16f
            }
            if (isPassword) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        container.addView(labelView)
        container.addView(editText)
        this.addView(container)
        return editText
    }

    private fun createDashboardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))

            val title = TextView(this.context).apply { text = "Solar Monitor Deye"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 0, 0, 40) }
            addView(title)

            statusCard = LinearLayout(this.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 50, 50, 50)
                gravity = Gravity.CENTER
                background = createCardBackground("#1E1E1E")
            }

            statusTitle = TextView(this.context).apply { text = "ПРОВЕРКА..."; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
            statusSubtext = TextView(this.context).apply { text = "Запрос к Deye Cloud..."; textSize = 14f; setTextColor(Color.GRAY); setPadding(0, 15, 0, 0); gravity = Gravity.CENTER; maxLines = 10 }

            statusCard.addView(statusTitle)
            statusCard.addView(statusSubtext)
            addView(statusCard)

            lastUpdateText = TextView(this.context).apply { text = "Обновлено: --:--:--"; textSize = 13f; setTextColor(Color.parseColor("#888888")); setPadding(0, 30, 0, 30) }
            addView(lastUpdateText)

            refreshBtn = Button(this.context).apply {
                text = "🔄 ОБНОВИТЬ СТАТУС"
                setBackgroundColor(Color.parseColor("#BB86FC"))
                setTextColor(Color.BLACK)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
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

    private fun showSetup() { setupLayout.visibility = View.VISIBLE; dashboardLayout.visibility = View.GONE }
    private fun showDashboard() { setupLayout.visibility = View.GONE; dashboardLayout.visibility = View.VISIBLE; fetchStatus() }

    private fun fetchStatus() {
        statusTitle.text = "ЗАГРУЗКА..."
        statusCard.background = createCardBackground("#2D2B1E")
        statusTitle.setTextColor(Color.parseColor("#FBBF24"))
        statusSubtext.text = "Анализ сети объекта Ozenbash..."

        thread {
            val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
            val appId = prefs.getString("app_id", "") ?: ""
            val appSecret = prefs.getString("app_secret", "") ?: ""
            val email = prefs.getString("email", "") ?: ""
            val password = prefs.getString("password", "") ?: ""

            var errText = ""
            val token = authenticate(appId, appSecret, email, password) { errText = it }
            val result = if (token != null) checkTelemetry(appId, token) { errText = it } else null

            runOnUiThread {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                lastUpdateText.text = "Обновлено в $time"

                if (result != null) {
                    if (result.isGridOn) {
                        statusCard.background = createCardBackground("#064E3B")
                        statusTitle.text = "🟢 ${result.titleText}"
                        statusTitle.setTextColor(Color.parseColor("#A7F3D0"))
                    } else {
                        statusCard.background = createCardBackground("#7F1D1D")
                        statusTitle.text = "🔴 ${result.titleText}"
                        statusTitle.setTextColor(Color.parseColor("#FECACA"))
                    }
                    statusSubtext.text = result.details
                } else {
                    statusCard.background = createCardBackground("#1F2937")
                    statusTitle.text = "⚠️ ОШИБКА СВЯЗИ"
                    statusTitle.setTextColor(Color.parseColor("#F3F4F6"))
                    statusSubtext.text = if (errText.isNotEmpty()) errText else "Не удалось прочитать телеметрию"
                }
            }
        }
    }

    private fun authenticate(appId: String, appSecret: String, email: String, pass: String, onError: (String) -> Unit): String? {
        return try {
            val json = JsonObject().apply {
                addProperty("appSecret", appSecret)
                addProperty("email", email)
                addProperty("password", pass.toSha256())
            }
            val request = Request.Builder()
                .url("$baseUrl/account/token?appId=$appId")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val res = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                if (res.get("success")?.asBoolean == true) {
                    res.get("accessToken")?.asString
                } else {
                    onError("Auth: ${res.get("msg")?.asString}")
                    null
                }
            }
        } catch (e: Exception) {
            onError("Auth error: ${e.message}")
            null
        }
    }

    private fun checkTelemetry(appId: String, token: String, onError: (String) -> Unit): CheckResult? {
        return try {
            val listJson = JsonObject().apply { addProperty("page", 1); addProperty("size", 10) }
            val listReq = Request.Builder()
                .url("$baseUrl/station/list")
                .post(listJson.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "bearer $token")
                .build()

            var stationId: Long? = null
            var stationName = "Ozenbash"

            client.newCall(listReq).execute().use { response ->
                val res = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                val list = res.getAsJsonArray("stationList") ?: res.getAsJsonObject("data")?.getAsJsonArray("list")
                if (list != null && list.size() > 0) {
                    val st = list[0].asJsonObject
                    stationId = st.get("id")?.asLong ?: st.get("stationId")?.asLong
                    stationName = st.get("name")?.asString ?: "Ozenbash"
                }
            }

            if (stationId == null) {
                onError("Не найден ID объекта")
                return null
            }

            val latestJson = JsonObject().apply { addProperty("stationId", stationId) }
            val latestReq = Request.Builder()
                .url("$baseUrl/station/latest?appId=$appId")
                .post(latestJson.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "bearer $token")
                .build()

            client.newCall(latestReq).execute().use { response ->
                val respStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val res = JsonParser.parseString(respStr).asJsonObject
                    val data = if (res.has("data") && res.get("data").isJsonObject) res.getAsJsonObject("data") else res

                    val gridPower = parseDouble(data, "gridPower")
                    val purchasePower = parseDouble(data, "purchasePower") ?: parseDouble(data, "purchasedPower")
                    val genPower = parseDouble(data, "generationPower") ?: 0.0
                    val conPower = parseDouble(data, "consumptionPower") ?: 0.0

                    val activeGridPower = gridPower ?: purchasePower ?: 0.0
                    val isGridActive = abs(activeGridPower) > 5.0

                    val statusTitleText = if (isGridActive) "СЕТЬ В НОРМЕ" else "СЕТЬ ОТКЛЮЧЕНА (АКБ)"
                    val gridText = if (isGridActive) "${activeGridPower.toInt()} Вт" else "0 Вт (Автономный режим)"

                    val detailsText = StringBuilder().apply {
                        append("Объект: $stationName\n")
                        append("Мощность сети: $gridText\n")
                        append("Нагрузка дома: ${conPower.toInt()} Вт\n")
                        append("Солнце: ${genPower.toInt()} Вт")
                    }.toString()

                    CheckResult(isGridActive, statusTitleText, detailsText)
                } else {
                    onError("Telemetry HTTP ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            onError("Crash: ${e.message}")
            null
        }
    }

    private fun parseDouble(obj: JsonObject, key: String): Double? {
        if (!obj.has(key)) return null
        val elem = obj.get(key)
        if (elem == null || elem.isJsonNull) return null
        return try {
            val str = elem.asString
            str.toDoubleOrNull()
        } catch (e: Exception) {
            try { elem.asDouble } catch (ex: Exception) { null }
        }
    }

    private fun createCardBackground(colorHex: String): GradientDrawable = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = 24f }

    private fun scheduleWorker() {
        val workRequest = PeriodicWorkRequestBuilder<DeyeCloudWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("DeyeCloudCheckWork", ExistingPeriodicWorkPolicy.KEEP, workRequest)
    }

    private fun String.toSha256(): String = MessageDigest.getInstance("SHA-256").digest(this.toByteArray()).joinToString("") { "%02x".format(it) }
}
