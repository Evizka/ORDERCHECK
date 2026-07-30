package com.courier.notifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object TelegramNotifier {

    suspend fun sendOrderNotification(
        botToken: String,
        chatId: String,
        order: OrderInfo
    ): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) return@withContext false

        try {
            val apiUrl = "https://api.telegram.org/bot$botToken/sendMessage"
            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true

            val formattedDistance = if (order.distanceKm < 1.0) {
                "${(order.distanceKm * 1000).toInt()} м"
            } else {
                "%.1f км".format(order.distanceKm)
            }

            val messageText = """
                🚀 *НОВЫЙ ЗАКАЗ В ДОСТАВИСТЕ!*
                
                💰 *Оплата:* ${order.price} ₽
                📍 *Расстояние:* $formattedDistance
                
                📄 *Детали:*
                ${order.rawText.take(300)}
                
                ⏱ _Отправлено автоматически через Courier Monitor_
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                put("chat_id", chatId)
                put("text", messageText)
                put("parse_mode", "Markdown")
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
