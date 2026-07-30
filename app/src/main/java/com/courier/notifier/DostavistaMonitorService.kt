package com.courier.notifier

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DostavistaMonitorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifiedOrders = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: ""
        
        // STRICTLY AND ONLY TARGET DOSTAVISTA (com.sebbia.delivery)
        if (pkgName != "com.sebbia.delivery") {
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            val textBlocks = mutableListOf<String>()
            collectTextNodes(rootNode, textBlocks)

            if (textBlocks.isEmpty()) return

            processDostavistaScreen(textBlocks)
        } catch (e: Exception) {
            sendDiagnosticLog("❌ Ошибка: ${e.message}")
        } finally {
            try {
                rootNode.recycle()
            } catch (_: Exception) {}
        }
    }

    private fun processDostavistaScreen(textBlocks: List<String>) {
        val prefs = getSharedPreferences("courier_prefs", Context.MODE_PRIVATE)
        val maxRadiusKm = prefs.getFloat("max_radius", 5.0f).toDouble()
        val minPrice = prefs.getInt("min_price", 0)
        val targetKeywordsCsv = prefs.getString("base_address", "") ?: ""
        val telegramToken = prefs.getString("tg_token", "") ?: ""
        val telegramChatId = prefs.getString("tg_chat_id", "") ?: ""
        val discordWebhook = prefs.getString("discord_webhook", "") ?: ""

        if (telegramToken.isBlank() || telegramChatId.isBlank()) return

        for (text in textBlocks) {
            val price = OrderParser.parsePrice(text)
            val distanceKm = OrderParser.parseDistanceKm(text)
            val (isKwMatch, matchedKw) = OrderParser.checkKeywords(text, targetKeywordsCsv)

            val matchesPrice = price != null && price >= minPrice && price > 50
            val matchesDistance = distanceKm != null && distanceKm <= maxRadiusKm

            if (isKwMatch || matchesPrice || matchesDistance) {
                val actualPrice = price ?: OrderParser.parsePrice(textBlocks.joinToString("\n")) ?: 0
                val actualDistance = distanceKm ?: 0.0

                val orderHash = OrderParser.generateOrderHash(actualDistance, actualPrice, text)

                if (!notifiedOrders.contains(orderHash)) {
                    notifiedOrders.add(orderHash)
                    if (notifiedOrders.size > 300) notifiedOrders.clear()

                    val order = OrderInfo(
                        id = orderHash,
                        distanceKm = actualDistance,
                        price = actualPrice,
                        rawText = text,
                        isKeywordMatch = isKwMatch,
                        matchedKeyword = matchedKw
                    )

                    // 1. Двойной звуковой сигнал и тройная импульсная вибрация
                    triggerIntenseAlertSignal()

                    // 2. Всплывающее баннер-уведомление поверх экрана Достависты
                    showScreenToast(actualPrice, actualDistance, matchedKw)

                    // 3. Логирование в консоль
                    val reason = if (isKwMatch) "Ключевое слово '$matchedKw'" else if (matchesPrice) "Оплата $actualPrice ₽ >= $minPrice ₽" else "$actualDistance км <= $maxRadiusKm км"
                    sendDiagnosticLog("🎉 ЗАКАЗ ДОСТАВИСТА ($reason)! Отправка в Telegram...")

                    // 4. Отправка в Telegram
                    serviceScope.launch {
                        TelegramNotifier.sendOrderNotification(telegramToken, telegramChatId, order)
                        if (discordWebhook.isNotBlank()) {
                            DiscordNotifier.sendOrderNotification(discordWebhook, order)
                        }
                    }
                }
            }
        }
    }

    private fun showScreenToast(price: Int, distanceKm: Double, keyword: String) {
        mainHandler.post {
            val priceStr = if (price > 0) "$price ₽" else "Новый"
            val kwStr = if (keyword.isNotEmpty()) " [$keyword]" else ""
            Toast.makeText(applicationContext, "🚨 ЗАКАЗ ДОСТАВИСТА: $priceStr$kwStr", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerIntenseAlertSignal() {
        try {
            // Звуковой сигнал
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()

            // Тройная импульсная вибрация (не пропустишь!)
            val pattern = longArrayOf(0, 400, 150, 400, 150, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, outList: MutableList<String>) {
        if (node == null) return

        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            outList.add(text.trim())
        }

        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrBlank() && contentDesc != text) {
            outList.add(contentDesc.trim())
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, outList)
            try {
                child.recycle()
            } catch (_: Exception) {}
        }
    }

    private fun sendDiagnosticLog(msg: String) {
        val intent = Intent("com.courier.notifier.LOG_UPDATE")
        intent.putExtra("log_message", msg)
        sendBroadcast(intent)
    }

    override fun onInterrupt() {}
}
