package com.courier.notifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object DiscordNotifier {

    suspend fun sendOrderNotification(
        webhookUrl: String,
        order: OrderInfo
    ): Boolean = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) return@withContext false

        try {
            val url = URL(webhookUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true

            val formattedDistance = if (order.distanceKm < 1.0) {
                "${(order.distanceKm * 1000).toInt()} м"
            } else {
                "%.1f км".format(order.distanceKm)
            }

            val embed = JSONObject().apply {
                put("title", "🚀 Новый заказ в Достависте!")
                put("color", 3066993) // Green color
                put("fields", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "💰 Оплата")
                        put("value", "${order.price} ₽")
                        put("inline", true)
                    })
                    put(JSONObject().apply {
                        put("name", "📍 Расстояние")
                        put("value", formattedDistance)
                        put("inline", true)
                    })
                    put(JSONObject().apply {
                        put("name", "📄 Описание")
                        put("value", order.rawText.take(250))
                        put("inline", false)
                    })
                })
            }

            val jsonBody = JSONObject().apply {
                put("username", "Достависта Бот")
                put("embeds", JSONArray().put(embed))
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                os.write(jsonBody.toString())
                os.flush()
            }

            val responseCode = conn.responseCode
            return@withContext responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}
