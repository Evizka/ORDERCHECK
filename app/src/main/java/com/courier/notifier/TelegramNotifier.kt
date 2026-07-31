package com.courier.notifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            val distanceStr = if (order.distanceKm > 0.0) {
                if (order.distanceKm < 1.0) "${(order.distanceKm * 1000).toInt()} м от вас" else "%.1f км от вас".format(order.distanceKm)
            } else if (order.isKeywordMatch) {
                "📍 Рядом с вашей улицей ('${order.matchedKeyword.uppercase()}')"
            } else {
                "📍 ~ Из вашей зоны (Карта Достависта)"
            }

            val priceStr = if (order.price > 0) "${order.price} ₽" else "Указана на карточке"

            val tagHeader = if (order.isKeywordMatch) {
                "⭐ *СОВПАДЕНИЕ ПО АДРЕСУ: '${order.matchedKeyword.uppercase()}'*"
            } else {
                "🚨 *СРОЧНЫЙ ЗАКАЗ В ДОСТАВИСТЕ!*"
            }

            val messageText = """
                $tagHeader
                
                💰 *Оплата:* $priceStr
                📍 *Дистанция:* $distanceStr
                ⏱ *Время:* $timeStr
                
                📄 *Детали:*
                `${order.rawText.take(250)}`
                
                ⚡ _Отправлено автоматически через Courier Monitor Pro_
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
