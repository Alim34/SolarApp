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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://eu1-developer.deyecloud.com/v1.0"

    private lateinit var setupLayout: LinearLayout
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var refreshBtn: Button

    data class CheckResult(val isGridOn: Boolean, val titleText: String, val details: String)

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
        if (prefs.getString("app_id", "").isNullOrEmpty()) {
            showSetup()
        } else {
            showDashboard()
        }
    }

    private fun createSetupView(): LinearLayout {
        val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            gravity = Gravity.CENTER_HORIZONTAL

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
            val passInput = createInputField("Пароль", prefs.getString("password", ""), true)

            val saveBtn = Button(this.context).apply {
                text = "Сохранить и запустить"
                setBackgroundColor(Color.parseColor("#BB86FC"))
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
            addView(saveBtn)
        }
    }

    private fun LinearLayout.createInputField(label: String, initial: String?, isPass: Boolean = false): EditText {
        val et = EditText(this.context).apply {
            hint = label
            setText(initial ?: "")
            setTextColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 16f
            }
            if (isPass) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        this.addView(TextView(this.context).apply {
            text = label
            setTextColor(Color.parseColor("#BB86FC"))
            setPadding(0, 10, 0, 5)
        })
        this.addView(et)
        return et
    }

    private fun createDashboardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            gravity = Gravity.CENTER_HORIZONTAL

            addView(TextView(this.context).apply {
                text = "Solar Monitor Deye"
                textSize = 22f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 40)
            })

            statusCard = LinearLayout(this.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 50, 50, 50)
                background = createCardBackground("#1E1E1E")
            }

            statusTitle = TextView(this.context).apply {
                text = "ПРОВЕРКА..."
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }

            // Оборачиваем текст в ScrollView на случай, если ключей будет много
            val scroll = ScrollView(this.context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 600)
                setPadding(0, 15, 0, 0)
            }

            statusSubtext = TextView(this.context).apply {
                text = "Загрузка..."
                textSize = 12f // Сделали меньше, чтобы влезло больше сырых данных
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
            }

            scroll.addView(statusSubtext)
            statusCard.addView(statusTitle)
            statusCard.addView(scroll)
            addView(statusCard)

            lastUpdateText = TextView(this.context).apply {
                text = "Обновлено: --:--:--"
                setPadding(0, 30, 0, 30)
                setTextColor(Color.GRAY)
            }
            addView(lastUpdateText)

            refreshBtn = Button(this.context).apply {
                text = "🔄 ОБНОВИТЬ СТАТУС"
                setBackgroundColor(Color.parseColor("#BB86FC"))
                setOnClickListener { fetchStatus() }
            }
            addView(refreshBtn)

            addView(Button(this.context).apply {
                text = "⚙️ Настройки"
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showSetup() }
            })
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
        refreshBtn.isEnabled = false
        refreshBtn.text = "⌛ ЗАГРУЗКА..."

        thread {
            val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
            val appId = prefs.getString("app_id", "") ?: ""
            val secret = prefs.getString("app_secret", "") ?: ""
            val email = prefs.getString("email", "") ?: ""
            val pass = prefs.getString("password", "") ?: ""

            val token = authenticate(appId, secret, email, pass)
            val result = if (token != null) checkTelemetry(appId, token) else null

            runOnUiThread {
                refreshBtn.isEnabled = true
                refreshBtn.text = "🔄 ОБНОВИТЬ СТАТУС"

                lastUpdateText.text = "Обновлено в ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
                
                if (result != null) {
                    // Пока зафиксируем цвет для отладки
                    statusCard.background = createCardBackground("#4B5563") // Серый цвет режима отладки
                    statusTitle.text = result.titleText
                    statusTitle.setTextColor(Color.WHITE)
                    statusSubtext.text = result.details
                } else {
                    statusTitle.text = "🔴 ОШИБКА ДАННЫХ"
                    statusTitle.setTextColor(Color.parseColor("#FECACA"))
                    statusSubtext.text = "Не удалось подключиться к серверу Deye."
                }
            }
        }
    }

    private fun authenticate(appId: String, secret: String, email: String, pass: String): String? {
        return try {
            val json = JsonObject().apply {
                addProperty("appSecret", secret)
                addProperty("email", email)
                addProperty("password", pass.toSha256())
            }
            val req = Request.Builder()
                .url("$baseUrl/account/token?appId=$appId")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string() ?: ""
                JsonParser.parseString(bodyStr).asJsonObject.get("accessToken")?.asString
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkTelemetry(appId: String, token: String): CheckResult? {
        return try {
            val listJson = JsonObject().apply {
                addProperty("page", 1)
                addProperty("size", 10)
            }
            val listReq = Request.Builder()
                .url("$baseUrl/station/list")
                .post(listJson.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "bearer $token")
                .build()

            var stationId: Long? = null

            client.newCall(listReq).execute().use { response ->
                val res = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                val list = res.getAsJsonArray("stationList") ?: res.getAsJsonObject("data")?.getAsJsonArray("list")
                if (list != null && list.size() > 0) {
                    val st = list[0].asJsonObject
                    stationId = st.get("id")?.asLong ?: st.get("stationId")?.asLong
                }
            }

            if (stationId == null) return null

            val latestJson = JsonObject().apply { addProperty("stationId", stationId) }
            val latestReq = Request.Builder()
                .url("$baseUrl/station/latest?appId=$appId")
                .post(latestJson.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "bearer $token")
                .build()

            client.newCall(latestReq).execute().use { response ->
                val res = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                val data = if (res.has("data") && res.get("data").isJsonObject) res.getAsJsonObject("data") else res

                val conPower = parseDouble(data, "consumptionPower") ?: 0.0

                // РЕНТГЕН: Вытаскиваем все ключи, где значение больше 1, чтобы найти нужные
                val debugInfo = java.lang.StringBuilder()
                data.entrySet().forEach { (key, element) ->
                    try {
                        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                            val value = element.asDouble
                            if (abs(value) > 1.0) { // Пропускаем нули
                                debugInfo.append("$key: ${value.toInt()}\n")
                            }
                        }
                    } catch (e: Exception) { }
                }

                val statusTitleText = "🔍 РЕЖИМ ОТЛАДКИ"
                val detailsText = buildString {
                    append("Нагрузка совпала: ${conPower.toInt()} Вт\n")
                    append("--- СЫРЫЕ ДАННЫЕ СЕРВЕРА ---\n")
                    append(debugInfo.toString())
                }

                CheckResult(true, statusTitleText, detailsText)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDouble(obj: JsonObject, key: String): Double? {
        return try {
            if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asDouble else null
        } catch (e: Exception) {
            null
        }
    }

    private fun createCardBackground(hex: String) = GradientDrawable().apply {
        setColor(Color.parseColor(hex))
        cornerRadius = 24f
    }

    private fun scheduleWorker() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "Deye",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeyeCloudWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    private fun String.toSha256() = MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://eu1-developer.deyecloud.com/v1.0"

    private lateinit var setupLayout: LinearLayout
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtext: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var refreshBtn: Button

    data class CheckResult(val isGridOn: Boolean, val titleText: String, val details: String)

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
        if (prefs.getString("app_id", "").isNullOrEmpty()) {
            showSetup()
        } else {
            showDashboard()
        }
    }

    private fun createSetupView(): LinearLayout {
        val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            gravity = Gravity.CENTER_HORIZONTAL

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
            val passInput = createInputField("Пароль", prefs.getString("password", ""), true)

            val saveBtn = Button(this.context).apply {
                text = "Сохранить и запустить"
                setBackgroundColor(Color.parseColor("#BB86FC"))
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
            addView(saveBtn)
        }
    }

    private fun LinearLayout.createInputField(label: String, initial: String?, isPass: Boolean = false): EditText {
        val et = EditText(this.context).apply {
            hint = label
            setText(initial ?: "")
            setTextColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 16f
            }
            if (isPass) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        this.addView(TextView(this.context).apply {
            text = label
            setTextColor(Color.parseColor("#BB86FC"))
            setPadding(0, 10, 0, 5)
        })
        this.addView(et)
        return et
    }

    private fun createDashboardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
            gravity = Gravity.CENTER_HORIZONTAL

            addView(TextView(this.context).apply {
                text = "Solar Monitor Deye"
                textSize = 22f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 40)
            })

            statusCard = LinearLayout(this.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 50, 50, 50)
                background = createCardBackground("#1E1E1E")
            }

            statusTitle = TextView(this.context).apply {
                text = "ПРОВЕРКА..."
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }

            // Оборачиваем текст в ScrollView на случай, если ключей будет много
            val scroll = ScrollView(this.context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 600)
                setPadding(0, 15, 0, 0)
            }

            statusSubtext = TextView(this.context).apply {
                text = "Загрузка..."
                textSize = 12f // Сделали меньше, чтобы влезло больше сырых данных
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
            }

            scroll.addView(statusSubtext)
            statusCard.addView(statusTitle)
            statusCard.addView(scroll)
            addView(statusCard)

            lastUpdateText = TextView(this.context).apply {
                text = "Обновлено: --:--:--"
                setPadding(0, 30, 0, 30)
                setTextColor(Color.GRAY)
            }
            addView(lastUpdateText)

            refreshBtn = Button(this.context).apply {
                text = "🔄 ОБНОВИТЬ СТАТУС"
                setBackgroundColor(Color.parseColor("#BB86FC"))
                setOnClickListener { fetchStatus() }
            }
            addView(refreshBtn)

            addView(Button(this.context).apply {
                text = "⚙️ Настройки"
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showSetup() }
            })
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
        refreshBtn.isEnabled = false
        refreshBtn.text = "⌛ ЗАГРУЗКА..."

        thread {
            val prefs = getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
            val appId = prefs.getString("app_id", "") ?: ""
            val secret = prefs.getString("app_secret", "") ?: ""
            val email = prefs.getString("email", "") ?: ""
            val pass = prefs.getString("password", "") ?: ""

            val token = authenticate(appId, secret, email, pass)
            val result = if (token != null) checkTelemetry(appId, token) else null

            runOnUiThread {
                refreshBtn.isEnabled = true
                refreshBtn.text = "🔄 ОБНОВИТЬ СТАТУС"

                lastUpdateText.text = "Обновлено в ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
                
                if (result != null) {
                    // Пока зафиксируем цвет для отладки
                    statusCard.background = createCardBackground("#4B5563") // Серый цвет режима отладки
                    statusTitle.text = result.titleText
                    statusTitle.setTextColor(Color.WHITE)
                    statusSubtext.text = result.details
                } else {
                    statusTitle.text = "🔴 ОШИБКА ДАННЫХ"
                    statusTitle.setTextColor(Color.parseColor("#FECACA"))
                    statusSubtext.text = "Не удалось подключиться к серверу Deye."
                }
            }
        }
    }

    private fun authenticate(appId: String, secret: String, email: String, pass: String): String? {
        return try {
            val json = JsonObject().apply {
                addProperty("appSecret", secret)
                addProperty("email", email)
                addProperty("password", pass.toSha256())
            }
            val req = Request.Builder()
                .url("$baseUrl/account/token?appId=$appId")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string() ?: ""
                JsonParser.parseString(bodyStr).asJsonObject.get("accessToken")?.asString
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkTelemetry(appId: String, token: String): CheckResult? {
        return try {
            val listJson = JsonObject().apply {
                addProperty("page", 1)
                addProperty("size", 10)
            }
            val listReq = Request.Builder()
                .url("$baseUrl/station/list")
                .post(listJson.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "bearer $token")
                .build()

            var stationId: Long? = null

            client.newCall(listReq).execute().use { response ->
                val res = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                val list = res.getAsJsonArray("stationList") ?: res.getAsJsonObject("data")?.getAsJsonArray("list")
                if (list != null && list.size() > 0) {
                    val st = list[0].asJsonObject
                    stationId = st.get("id")?.asLong ?: st.get("stationId")?.asLong
                }
            }

            if (stationId == null) return null

            val latestJson = JsonObject().apply { addProperty("stationId", stationId) }
            val latestReq = Request.Builder()
                .url("$baseUrl/station/latest?appId=$appId")
                .post(latestJson.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "bearer $token")
                .build()

            client.newCall(latestReq).execute().use { response ->
                val res = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                val data = if (res.has("data") && res.get("data").isJsonObject) res.getAsJsonObject("data") else res

                val conPower = parseDouble(data, "consumptionPower") ?: 0.0

                // РЕНТГЕН: Вытаскиваем все ключи, где значение больше 1, чтобы найти нужные
                val debugInfo = java.lang.StringBuilder()
                data.entrySet().forEach { (key, element) ->
                    try {
                        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                            val value = element.asDouble
                            if (abs(value) > 1.0) { // Пропускаем нули
                                debugInfo.append("$key: ${value.toInt()}\n")
                            }
                        }
                    } catch (e: Exception) { }
                }

                val statusTitleText = "🔍 РЕЖИМ ОТЛАДКИ"
                val detailsText = buildString {
                    append("Нагрузка совпала: ${conPower.toInt()} Вт\n")
                    append("--- СЫРЫЕ ДАННЫЕ СЕРВЕРА ---\n")
                    append(debugInfo.toString())
                }

                CheckResult(true, statusTitleText, detailsText)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDouble(obj: JsonObject, key: String): Double? {
        return try {
            if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asDouble else null
        } catch (e: Exception) {
            null
        }
    }

    private fun createCardBackground(hex: String) = GradientDrawable().apply {
        setColor(Color.parseColor(hex))
        cornerRadius = 24f
    }

    private fun scheduleWorker() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "Deye",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeyeCloudWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    private fun String.toSha256() = MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
