package com.courier.notifier

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvAppVersion: TextView
    private val logBuffer = StringBuilder()

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("log_message") ?: return
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val formattedMsg = "[$time] $msg\n"

            logBuffer.insert(0, formattedMsg)
            if (logBuffer.length > 4000) {
                logBuffer.setLength(4000)
            }
            tvLog.text = logBuffer.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("courier_prefs", Context.MODE_PRIVATE)

        val etTgToken = findViewById<EditText>(R.id.et_tg_token)
        val etTgChatId = findViewById<EditText>(R.id.et_tg_chat_id)
        val etBaseAddress = findViewById<EditText>(R.id.et_base_address)
        val etMaxRadius = findViewById<EditText>(R.id.et_max_radius)
        val etMinPrice = findViewById<EditText>(R.id.et_min_price)
        tvLog = findViewById(R.id.tv_log)
        tvStatusBadge = findViewById(R.id.tv_status_badge)
        tvAppVersion = findViewById(R.id.tv_app_version)

        val btnSave = findViewById<Button>(R.id.btn_save)
        val btnTest = findViewById<Button>(R.id.btn_test)
        val btnOpenAccessibility = findViewById<Button>(R.id.btn_open_accessibility)

        // Set version label dynamically
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvAppVersion.text = "v${pInfo.versionName} (Build ${pInfo.versionCode}) • Courier Monitor Pro"
        } catch (_: Exception) {
            tvAppVersion.text = "v1.0.4 • Courier Monitor Pro"
        }

        // Load saved preferences
        etTgToken.setText(prefs.getString("tg_token", "8806599669:AAHmRoCfNl2JTOD-ZtBMbZWTrwrZrBkOZtE"))
        etTgChatId.setText(prefs.getString("tg_chat_id", "1787466306"))
        etBaseAddress.setText(prefs.getString("base_address", "Молодежная 54"))
        etMaxRadius.setText(prefs.getFloat("max_radius", 5.0f).toString())
        etMinPrice.setText(prefs.getInt("min_price", 0).toString())

        btnSave.setOnClickListener {
            val radius = etMaxRadius.text.toString().toFloatOrNull() ?: 5.0f
            val price = etMinPrice.text.toString().toIntOrNull() ?: 0

            prefs.edit().apply {
                putString("tg_token", etTgToken.text.toString().trim())
                putString("tg_chat_id", etTgChatId.text.toString().trim())
                putString("base_address", etBaseAddress.text.toString().trim())
                putFloat("max_radius", radius)
                putInt("min_price", price)
                apply()
            }
            Toast.makeText(this, "✅ Настройки сохранены!", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            val token = etTgToken.text.toString().trim()
            val chatId = etTgChatId.text.toString().trim()

            val dummyOrder = OrderInfo(
                id = "test_${System.currentTimeMillis()}",
                distanceKm = 1.2,
                price = 500,
                rawText = "ТЕСТОВЫЙ ЗАКАЗ\nМосква, ул. Молодежная 54 -> ул. Арбат 10"
            )

            CoroutineScope(Dispatchers.Main).launch {
                val ok = TelegramNotifier.sendOrderNotification(token, chatId, dummyOrder)
                if (ok) {
                    Toast.makeText(this@MainActivity, "🎉 Тест отправлен в Telegram!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "❌ Ошибка! Проверьте Token / Chat ID", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        val filter = IntentFilter("com.courier.notifier.LOG_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatusBadge()
    }

    private fun updateServiceStatusBadge() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
            tvStatusBadge.text = "🟢 Служба активна"
            tvStatusBadge.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvStatusBadge.text = "🔴 Служба выключена"
            tvStatusBadge.setTextColor(Color.parseColor("#F43F5E"))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                for (service in enabledServices) {
                    if (service.resolveInfo.serviceInfo.packageName == packageName) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        val settingValue = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return settingValue.contains(packageName)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
