package com.courier.notifier

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("courier_prefs", Context.MODE_PRIVATE)

        val etTgToken = findViewById<EditText>(R.id.et_tg_token)
        val etTgChatId = findViewById<EditText>(R.id.et_tg_chat_id)
        val etDiscordWebhook = findViewById<EditText>(R.id.et_discord_webhook)
        val etMaxRadius = findViewById<EditText>(R.id.et_max_radius)
        val etMinPrice = findViewById<EditText>(R.id.et_min_price)

        val btnSave = findViewById<Button>(R.id.btn_save)
        val btnTest = findViewById<Button>(R.id.btn_test)
        val btnOpenAccessibility = findViewById<Button>(R.id.btn_open_accessibility)

        // Load saved preferences
        etTgToken.setText(prefs.getString("tg_token", ""))
        etTgChatId.setText(prefs.getString("tg_chat_id", ""))
        etDiscordWebhook.setText(prefs.getString("discord_webhook", ""))
        etMaxRadius.setText(prefs.getFloat("max_radius", 3.0f).toString())
        etMinPrice.setText(prefs.getInt("min_price", 0).toString())

        btnSave.setOnClickListener {
            val radius = etMaxRadius.text.toString().toFloatOrNull() ?: 3.0f
            val price = etMinPrice.text.toString().toIntOrNull() ?: 0

            prefs.edit().apply {
                putString("tg_token", etTgToken.text.toString().trim())
                putString("tg_chat_id", etTgChatId.text.toString().trim())
                putString("discord_webhook", etDiscordWebhook.text.toString().trim())
                putFloat("max_radius", radius)
                putInt("min_price", price)
                apply()
            }
            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            val token = etTgToken.text.toString().trim()
            val chatId = etTgChatId.text.toString().trim()
            val webhook = etDiscordWebhook.text.toString().trim()

            val dummyOrder = OrderInfo(
                id = "test",
                distanceKm = 1.2,
                price = 500,
                rawText = "ТЕСТОВЫЙ ЗАКАЗ\nМосква, ул. Тверская 1 -> ул. Арбат 10"
            )

            CoroutineScope(Dispatchers.Main).launch {
                var tgSuccess = false
                var discordSuccess = false

                if (token.isNotBlank() && chatId.isNotBlank()) {
                    tgSuccess = TelegramNotifier.sendOrderNotification(token, chatId, dummyOrder)
                }
                if (webhook.isNotBlank()) {
                    discordSuccess = DiscordNotifier.sendOrderNotification(webhook, dummyOrder)
                }

                val resultMsg = when {
                    tgSuccess && discordSuccess -> "Тест отправлен в Telegram и Discord!"
                    tgSuccess -> "Тест отправлен в Telegram!"
                    discordSuccess -> "Тест отправлен в Discord!"
                    else -> " Ошибка отправки. Проверьте Token / Chat ID / Webhook URL."
                }
                Toast.makeText(this@MainActivity, resultMsg, Toast.LENGTH_LONG).show()
            }
        }

        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }
}
