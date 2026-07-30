package com.courier.notifier

import android.accessibilityservice.AccessibilityService
import android.content.Context
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

        // We target the courier app package (e.g. com.dostavista.courier)
        val packageName = event.packageName?.toString() ?: ""
        if (!packageName.contains("dostavista", ignoreCase = true)) {
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            processNodeTree(rootNode)
        } finally {
            rootNode.recycle()
        }
    }

    private fun processNodeTree(root: AccessibilityNodeInfo) {
        val prefs = getSharedPreferences("courier_prefs", Context.MODE_PRIVATE)
        val maxRadiusKm = prefs.getFloat("max_radius", 3.0f).toDouble()
        val minPrice = prefs.getInt("min_price", 0)
        val telegramToken = prefs.getString("tg_token", "") ?: ""
        val telegramChatId = prefs.getString("tg_chat_id", "") ?: ""
        val discordWebhook = prefs.getString("discord_webhook", "") ?: ""

        val textBlocks = mutableListOf<String>()
        collectTextNodes(root, textBlocks)

        val fullScreenText = textBlocks.joinToString("\n")

        // Search for distances and prices in collected texts
        for (text in textBlocks) {
            val distanceKm = OrderParser.parseDistanceKm(text) ?: continue

            // Found a distance node! Let's search nearby text blocks for price
            val price = OrderParser.parsePrice(fullScreenText) ?: 0

            if (distanceKm <= maxRadiusKm && price >= minPrice) {
                val orderHash = OrderParser.generateOrderHash(distanceKm, price, text)

                if (!notifiedOrders.contains(orderHash)) {
                    notifiedOrders.add(orderHash)
                    // Keep cache bounded
                    if (notifiedOrders.size > 200) {
                        notifiedOrders.clear()
                    }

                    val order = OrderInfo(
                        id = orderHash,
                        distanceKm = distanceKm,
                        price = price,
                        rawText = text
                    )

                    serviceScope.launch {
                        if (telegramToken.isNotBlank() && telegramChatId.isNotBlank()) {
                            TelegramNotifier.sendOrderNotification(telegramToken, telegramChatId, order)
                        }
                        if (discordWebhook.isNotBlank()) {
                            DiscordNotifier.sendOrderNotification(discordWebhook, order)
                        }
                    }
                }
            }
        }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, outList: MutableList<String>) {
        if (node == null) return

        val text = node.text?.toString()
        if (!text.isNull_or_blank()) {
            outList.add(text.trim())
        }

        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNull_or_blank() && contentDesc != text) {
            outList.add(contentDesc.trim())
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, outList)
            child.recycle()
        }
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }

    override fun onInterrupt() {}
}
