import os
import re
import time
import xml.etree.ElementTree as ET
import requests

# ==================== НАСТРОЙКИ ====================
TELEGRAM_BOT_TOKEN = "ТВОЙ_TELEGRAM_BOT_TOKEN"
TELEGRAM_CHAT_ID = "ТВОЙ_CHAT_ID"
DISCORD_WEBHOOK_URL = ""  # Опционально

MAX_RADIUS_KM = 3.0  # Максимальный радиус в км (например 3.0 или 0.8)
MIN_PRICE_RUB = 0    # Минимальная стоимость заказа в рублях

CHECK_INTERVAL = 3  # Интервал проверки экрана в секундах
# ====================================================

notified_hashes = set()

def send_telegram(text):
    if not TELEGRAM_BOT_TOKEN or TELEGRAM_BOT_TOKEN.startswith("ТВОЙ"):
        print("[!] Не указан TELEGRAM_BOT_TOKEN")
        return
    url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage"
    payload = {
        "chat_id": TELEGRAM_CHAT_ID,
        "text": text,
        "parse_mode": "Markdown"
    }
    try:
        requests.post(url, json=payload, timeout=5)
    except Exception as e:
        print(f"[!] Ошибка отправки в Telegram: {e}")

def send_discord(title, description, price, distance_str):
    if not DISCORD_WEBHOOK_URL:
        return
    payload = {
        "username": "Достависта Сканер",
        "embeds": [{
            "title": title,
            "color": 3066993,
            "fields": [
                {"name": "💰 Оплата", "value": f"{price} ₽", "inline": True},
                {"name": "📍 Расстояние", "value": distance_str, "inline": True},
                {"name": "📄 Текст", "value": description[:300], "inline": False}
            ]
        }]
    }
    try:
        requests.post(DISCORD_WEBHOOK_URL, json=payload, timeout=5)
    except Exception as e:
        print(f"[!] Ошибка отправки в Discord: {e}")

def get_adb_dump():
    """Снимает дампинг UI элементов с подключенного по ADB телефона."""
    os.system("adb shell uiautomator dump /sdcard/window_dump.xml > null 2>&1")
    os.system("adb pull /sdcard/window_dump.xml dump.xml > null 2>&1")
    if os.path.exists("dump.xml"):
        with open("dump.xml", "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
        return content
    return ""

def parse_distance(text):
    text_lower = text.lower().replace(',', '.')
    # Метры
    m_match = re.search(r'(\d+)\s*м\b', text_lower)
    if m_match:
        return float(m_match.group(1)) / 1000.0

    # Километры
    km_match = re.search(r'(\d+(?:\.\d+)?)\s*(?:км|km)\b', text_lower)
    if km_match:
        return float(km_match.group(1))
    return None

def parse_price(text):
    cleaned = re.sub(r'\s+', '', text)
    match = re.search(r'(\d+)(?:₽|руб|р)', cleaned)
    if match:
        return int(match.group(1))
    return 0

def scan_orders():
    dump_xml = get_adb_dump()
    if not dump_xml:
        print("[!] Не удалось получить UI dump через ADB. Проверьте USB-отладку.")
        return

    try:
        root = ET.fromstring(dump_xml)
    except Exception:
        return

    texts = []
    for node in root.iter('node'):
        text = node.attrib.get('text', '')
        desc = node.attrib.get('content-desc', '')
        if text:
            texts.append(text)
        if desc and desc != text:
            texts.append(desc)

    full_screen = "\n".join(texts)
    price = parse_price(full_screen)

    for item_text in texts:
        dist_km = parse_distance(item_text)
        if dist_km is not None:
            if dist_km <= MAX_RADIUS_KM and price >= MIN_PRICE_RUB:
                order_hash = f"{price}_{int(dist_km*10)}_{hash(item_text[:30])}"
                if order_hash not in notified_hashes:
                    notified_hashes.add(order_hash)
                    
                    dist_str = f"{int(dist_km*1000)} м" if dist_km < 1.0 else f"{dist_km:.1f} км"
                    print(f"[+] НАЙДЕН ЗАКАЗ! Расстояние: {dist_str}, Оплата: {price} ₽")

                    msg = (
                        f"🚀 *НОВЫЙ ЗАКАЗ В ДОСТАВИСТЕ!*\n\n"
                        f"💰 *Оплата:* {price} ₽\n"
                        f"📍 *Расстояние:* {dist_str}\n\n"
                        f"📄 *Детали:* {item_text[:250]}"
                    )
                    send_telegram(msg)
                    send_discord("🚀 Новый заказ Достависта", item_text[:250], price, dist_str)

def main():
    print("==========================================")
    print("   Достависта Сканер Заказов (PC + ADB)   ")
    print("==========================================")
    print(f"Макс. радиус: {MAX_RADIUS_KM} км")
    print(f"Мин. цена: {MIN_PRICE_RUB} руб")
    print("Сканирование запущенно... (Ctrl+C для выхода)\n")

    while True:
        try:
            scan_orders()
            time.sleep(CHECK_INTERVAL)
        except KeyboardInterrupt:
            print("\nОстановлено пользователем.")
            break
        except Exception as e:
            print(f"[!] Ошибка: {e}")
            time.sleep(CHECK_INTERVAL)

if __name__ == "__main__":
    main()
