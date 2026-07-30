package com.courier.notifier

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DostavistaMonitorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifiedOrders = mutableSetOf<String>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: "unknown"
        val rootNode = rootInActiveWindow ?: return

        try {
            val textBlocks = mutableListOf<String>()
            collectTextNodes(rootNode, textBlocks)

            sendDiagnosticLog("⚡ Сканирование... Пакет: $pkgName | Узлов: ${textBlocks.size}")

            if (textBlocks.isEmpty()) return

            processTexts(textBlocks)
        } catch (e: Exception) {
            sendDiagnosticLog("❌ Ошибка: ${e.message}")
        } finally {
            try {
                rootNode.recycle()
            } catch (_: Exception) {}
        }
    }

    private fun processTexts(textBlocks: List<String>) {
        val prefs = getSharedPreferences("courier_prefs", Context.MODE_PRIVATE)
        val maxRadiusKm = prefs.getFloat("max_radius", 50.0f).toDouble()
        val minPrice = prefs.getInt("min_price", 0)
        val telegramToken = prefs.getString("tg_token", "") ?: ""
        val telegramChatId = prefs.getString("tg_chat_id", "") ?: ""
        val discordWebhook = prefs.getString("discord_webhook", "") ?: ""

        if (telegramToken.isBlank() || telegramChatId.isBlank()) return

        val fullScreenText = textBlocks.joinToString("\n")
        val parsedPrice = OrderParser.parsePrice(fullScreenText) ?: 0

        for (text in textBlocks) {
            val distanceKm = OrderParser.parseDistanceKm(text) ?: continue

            sendDiagnosticLog("📍 Расстояние: $text | Оплата: $parsedPrice ₽")

            if (distanceKm <= maxRadiusKm && parsedPrice >= minPrice) {
                val orderHash = OrderParser.generateOrderHash(distanceKm, parsedPrice, text)

                if (!notifiedOrders.contains(orderHash)) {
                    notifiedOrders.add(orderHash)
                    if (notifiedOrders.size > 300) notifiedOrders.clear()

                    val order = OrderInfo(
                        id = orderHash,
                        distanceKm = distanceKm,
                        price = parsedPrice,
                        rawText = text
                    )

                    // 1. Воспроизвести громкий сигнал и вибрацию
                    triggerAlertSoundAndVibration()

                    // 2. Логирование успеха
                    sendDiagnosticLog("🎉 ЗАКАЗ НАЙДЕН! Отправка в Telegram...")

                    // 3. Сетевое уведомление
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

    private fun triggerAlertSoundAndVibration() {
        try {
            // Звук уведомления
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()

            // Вибрация
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
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
