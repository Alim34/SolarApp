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

class DeyeCloudWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val client = OkHttpClient()

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("deye_prefs", Context.MODE_PRIVATE)
        val appId = prefs.getString("app_id", "") ?: ""
        val appSecret = prefs.getString("app_secret", "") ?: ""
        val email = prefs.getString("email", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        if (appId.isEmpty() || appSecret.isEmpty() || email.isEmpty() || password.isEmpty()) {
            return Result.failure()
        }

        val token = authenticate(appId, appSecret, email, password) ?: return Result.retry()
        val isGridActive = checkStationStatus(token) ?: return Result.retry()

        val lastState = prefs.getBoolean("last_grid_state", true)
        if (isGridActive != lastState) {
            sendNotification(isGridActive)
            prefs.edit().putBoolean("last_grid_state", isGridActive).apply()
        }

        return Result.success()
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
                        // status: 1 = Normal/Online, 2 = Offline/Fault
                        val status = station.get("status")?.asInt ?: 2
                        status == 1
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sendNotification(isOnline: Boolean) {
        val channelId = "deye_status_channel"
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Статус Deye Cloud",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val text = if (isOnline) "Питание от сети / Инвертор в сети" else "Внимание! Станция Deye не сеть / Отключена"

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Deye Solar Monitor")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
    }
}
