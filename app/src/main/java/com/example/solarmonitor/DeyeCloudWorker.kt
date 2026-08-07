package com.example.solarmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class DeyeCloudWorker(val context: Context, params: WorkerParameters) : Worker(context, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://eu1-developer.deyecloud.com/v1.0"

    override fun doWork(): Result {
        val prefs = context.getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
        val appId = prefs.getString("app_id", "") ?: ""
        val secret = prefs.getString("app_secret", "") ?: ""
        val email = prefs.getString("email", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""

        if (appId.isEmpty() || secret.isEmpty()) return Result.success()

        val token = authenticate(appId, secret, email, pass) ?: return Result.retry()
        val isGridActive = checkGridStatus(appId, token) ?: return Result.retry()

        val wasGridOn = prefs.getBoolean("was_grid_on", true)

        // Отправляем уведомление только если статус сети изменился
        if (isGridActive != wasGridOn) {
            if (!isGridActive) {
                sendNotification("🔴 СВЕТ ОТКЛЮЧЕН!", "Городская сеть пропала. Дом работает от батареи.")
            } else {
                sendNotification("🟢 СВЕТ ВКЛЮЧИЛИ!", "Городская сеть снова активна.")
            }
            prefs.edit().putBoolean("was_grid_on", isGridActive).apply()
        }

        return Result.success()
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

    private fun checkGridStatus(appId: String, token: String): Boolean? {
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
                val wirePower = parseDouble(data, "wirePower") ?: 0.0
                
                abs(wirePower) > 2.0
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

    private fun sendNotification(title: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "deye_grid_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Мониторинг сети Deye", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(1001, notification)
    }

    private fun String.toSha256() = MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
