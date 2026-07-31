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
    private val notifiedOrders = mutableMapOf<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var totalDetectedCount = 0
    private var maxPriceToday = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val rootNode = rootInActiveWindow ?: return
        val activePkg = rootNode.packageName?.toString() ?: event.packageName?.toString() ?: ""

        val isDostavista = activePkg.contains("sebbia", ignoreCase = true) || 
                           activePkg.contains("dostavista", ignoreCase = true)

        if (!isDostavista) {
            try { rootNode.recycle() } catch (_: Exception) {}
            return
        }

        try {
            val textBlocks = mutableListOf<String>()
            collectTextNodes(rootNode, textBlocks)

            if (textBlocks.isNotEmpty()) {
                sendDiagnosticLog("📱 Достависта активна (${textBlocks.size} блоков). Сканирую...")
                processDostavistaScreen(textBlocks)
            }
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

        val now = System.currentTimeMillis()
        notifiedOrders.entries.removeIf { (now - it.value) > 180_000 }

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

                if (!notifiedOrders.containsKey(orderHash)) {
                    notifiedOrders[orderHash] = now

                    totalDetectedCount++
                    if (actualPrice > maxPriceToday) {
                        maxPriceToday = actualPrice
                    }

                    // Send updated stats to MainActivity
                    sendStatsUpdate(totalDetectedCount, maxPriceToday)

                    val order = OrderInfo(
                        id = orderHash,
                        distanceKm = actualDistance,
                        price = actualPrice,
                        rawText = text,
                        isKeywordMatch = isKwMatch,
                        matchedKeyword = matchedKw
                    )

                    // 1. Двойной звуковой сигнал и вибрация
                    triggerIntenseAlertSignal()

                    // 2. Наэкранный баннер
                    showScreenToast(actualPrice, actualDistance, matchedKw)

                    // 3. Лог в консоль
                    val reason = if (isKwMatch) "Улица '$matchedKw'" else if (matchesPrice) "Цена $actualPrice ₽ >= $minPrice ₽" else "$actualDistance км <= $maxRadiusKm км"
                    sendDiagnosticLog("🎉 НАЙДЕН ЗАКАЗ ($reason)! Отправка в Telegram...")

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
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()

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

    private fun sendStatsUpdate(total: Int, maxPrice: Int) {
        val intent = Intent("com.courier.notifier.STATS_UPDATE")
        intent.putExtra("total_count", total)
        intent.putExtra("max_price", maxPrice)
        sendBroadcast(intent)
    }

    override fun onInterrupt() {}
}
