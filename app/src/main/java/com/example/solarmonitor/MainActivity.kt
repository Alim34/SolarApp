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

    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()
    private val baseUrl = "https://eu1-developer.deyecloud.com/v1.0"

    private lateinit var setupLayout: LinearLayout
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var lastUpdateText: TextView

    data class CheckResult(val isGridOn: Boolean, val titleText: String, val details: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            orientation = LinearLayout.VERTICAL; setPadding(50, 60, 50, 60); gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(this.context).apply { text = "Настройки Deye Cloud"; textSize = 22f; setTextColor(Color.WHITE) })
            val appId = createInputField("App ID", prefs.getString("app_id", ""))
            val appSecret = createInputField("App Secret", prefs.getString("app_secret", ""))
            val email = createInputField("Email", prefs.getString("email", ""))
            val pass = createInputField("Пароль", prefs.getString("password", ""), true)
            addView(Button(this.context).apply {
                text = "Сохранить и запустить"
                setOnClickListener {
                    prefs.edit().putString("app_id", appId.text.toString().trim()).putString("app_secret", appSecret.text.toString().trim())
                        .putString("email", email.text.toString().trim()).putString("password", pass.text.toString().trim()).apply()
                    showDashboard()
                }
            })
        }
    }

    private fun LinearLayout.createInputField(label: String, initial: String?, isPass: Boolean = false): EditText {
        val et = EditText(this.context).apply { hint = label; setText(initial ?: ""); setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.parseColor("#1E1E1E")); cornerRadius = 16f } }
        this.addView(TextView(this.context).apply { text = label; setTextColor(Color.parseColor("#BB86FC")) })
        this.addView(et)
        return et
    }

    private fun createDashboardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(50, 60, 50, 60); gravity = Gravity.CENTER_HORIZONTAL
            statusCard = LinearLayout(this.context).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 50, 50, 50); background = createCardBackground("#1E1E1E") }
            statusTitle = TextView(this.context).apply { text = "Нажми обновить"; textSize = 20f; setTextColor(Color.WHITE) }
            statusSubtext = TextView(this.context).apply { text = ""; textSize = 14f; setTextColor(Color.LTGRAY) }
            statusCard.addView(statusTitle); statusCard.addView(statusSubtext); addView(statusCard)
            lastUpdateText = TextView(this.context).apply { text = "Обновлено: --:--"; setPadding(0, 30, 0, 30); setTextColor(Color.GRAY) }
            addView(lastUpdateText)
            addView(Button(this.context).apply { text = "🔄 ОБНОВИТЬ СТАТУС"; setOnClickListener { fetchStatus() } })
            addView(Button(this.context).apply { text = "⚙️ Настройки"; setOnClickListener { showSetup() } })
        }
    }

    private fun showSetup() { setupLayout.visibility = View.VISIBLE; dashboardLayout.visibility = View.GONE }
    private fun showDashboard() { setupLayout.visibility = View.GONE; dashboardLayout.visibility = View.VISIBLE; fetchStatus() }

    private fun fetchStatus() {
        thread {
            val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
            val appId = prefs.getString("app_id", "") ?: ""
            val secret = prefs.getString("app_secret", "") ?: ""
            val email = prefs.getString("email", "") ?: ""
            val pass = prefs.getString("password", "") ?: ""
            
            val token = authenticate(appId, secret, email, pass)
            if (token != null) {
                val result = getTelemetry(appId, token)
                runOnUiThread {
                    if (result != null) {
                        statusTitle.text = result.titleText
                        statusSubtext.text = result.details
                        statusCard.background = createCardBackground(if (result.isGridOn) "#064E3B" else "#7F1D1D")
                        lastUpdateText.text = "Обновлено в ${SimpleDateFormat("HH:mm:ss").format(Date())}"
                    }
                }
            }
        }
    }

    private fun authenticate(appId: String, secret: String, email: String, pass: String): String? {
        return try {
            val json = JsonObject().apply { addProperty("appSecret", secret); addProperty("email", email); addProperty("password", pass.toSha256()) }
            val req = Request.Builder().url("$baseUrl/account/token?appId=$appId").post(json.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { res -> JsonParser.parseString(res.body?.string() ?: "").asJsonObject.get("accessToken")?.asString }
        } catch (e: Exception) { null }
    }

    private fun getTelemetry(appId: String, token: String): CheckResult? {
        try {
            // 1. Получаем ID станции
            val listReq = Request.Builder().url("$baseUrl/station/list").post(JsonObject().toString().toRequestBody("application/json".toMediaType())).header("Authorization", "bearer $token").build()
            val listRes = client.newCall(listReq).execute().use { JsonParser.parseString(it.body?.string() ?: "").asJsonObject }
            val stationId = listRes.getAsJsonArray("stationList")?.get(0)?.asJsonObject?.get("id")?.asString ?: return null

            // 2. Получаем данные по ID
            val latestReq = Request.Builder().url("$baseUrl/station/latest?appId=$appId").post(JsonObject().apply { addProperty("stationId", stationId) }.toString().toRequestBody("application/json".toMediaType())).header("Authorization", "bearer $token").build()
            client.newCall(latestReq).execute().use { response ->
                val data = JsonParser.parseString(response.body?.string() ?: "").asJsonObject.getAsJsonObject("data")
                val v = data.get("gridVoltage")?.asDouble ?: 0.0
                val p = data.get("gridPower")?.asDouble ?: 0.0
                val active = v > 50.0 || p > 0.0
                return CheckResult(active, if (active) "🟢 СЕТЬ В НОРМЕ" else "🔴 СЕТЬ ОТКЛЮЧЕНА", "Вольтаж: ${v.toInt()} В\nМощность сети: ${p.toInt()} Вт")
            }
        } catch (e: Exception) { return null }
    }

    private fun String.toSha256() = MessageDigest.getInstance("SHA-256").digest(this.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun createCardBackground(hex: String) = GradientDrawable().apply { setColor(Color.parseColor(hex)); cornerRadius = 24f }
}
