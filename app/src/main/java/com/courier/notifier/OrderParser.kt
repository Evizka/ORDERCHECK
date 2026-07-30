package com.courier.notifier

import java.util.regex.Pattern

data class OrderInfo(
    val id: String,
    val distanceKm: Double,
    val price: Int,
    val rawText: String,
    val pickupAddress: String = "",
    val deliveryAddress: String = ""
)

object OrderParser {

    /**
     * Parses distance in kilometers from strings like "1,5 км", "800 м", "2.4 km", "до 3 км", "забор 500м".
     */
    fun parseDistanceKm(text: String): Double? {
        if (text.isBlank()) return null
        val normalized = text.lowercase()
            .replace('\u00A0', ' ')
            .replace(',', '.')

        // Match meters, e.g., "500 м", "800м", "300m"
        val metersMatcher = Pattern.compile("(\\d+)\\s*(?:м|m)\\b").matcher(normalized)
        if (metersMatcher.find()) {
            val meters = metersMatcher.group(1)?.toDoubleOrNull()
            if (meters != null) return meters / 1000.0
        }

        // Match kilometers, e.g., "1.5 км", "2,4км", "0.8km", "до 3.5 км"
        val kmMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:км|km)").matcher(normalized)
        if (kmMatcher.find()) {
            return kmMatcher.group(1)?.toDoubleOrNull()
        }

        return null
    }

    /**
     * Parses price in RUB from strings like "450 ₽", "1 200 руб", "350р", "500".
     * Replaces non-breaking spaces and handles 3 to 6 digit payouts.
     */
    fun parsePrice(text: String): Int? {
        if (text.isBlank()) return null
        // Clean all whitespace variations and unicode non-breaking spaces
        val cleaned = text.replace("[\\s\\u00A0\\u2007\\u202F]+".toRegex(), "")

        // Match explicit currency formats, e.g., "450₽", "1200руб", "350р"
        val currencyMatcher = Pattern.compile("(\\d+)(?:₽|руб|р)").matcher(cleaned)
        if (currencyMatcher.find()) {
            val price = currencyMatcher.group(1)?.toIntOrNull()
            if (price != null && price in 50..100000) return price
        }

        // Fallback: match standalone numbers between 100 and 50000 (typical courier payouts)
        val standaloneMatcher = Pattern.compile("\\b(\\d{3,5})\\b").matcher(cleaned)
        if (standaloneMatcher.find()) {
            val price = standaloneMatcher.group(1)?.toIntOrNull()
            if (price != null && price in 100..50000) return price
        }

        return null
    }

    /**
     * Generates a unique key for the order to avoid sending duplicate notifications.
     */
    fun generateOrderHash(distanceKm: Double, price: Int, rawTextSnippet: String): String {
        val distKey = (distanceKm * 10).toInt()
        val textSnippet = rawTextSnippet.take(40).trim()
        return "${price}_${distKey}_${textSnippet.hashCode()}"
    }
}
