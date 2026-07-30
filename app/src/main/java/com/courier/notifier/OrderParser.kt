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
     * Parses distance in kilometers from strings like "1,5 км", "800 м", "2.4 km".
     * Returns null if no valid distance pattern is found.
     */
    fun parseDistanceKm(text: String): Double? {
        val normalized = text.lowercase().replace(',', '.')
        
        // Match meters, e.g., "500 м" or "800м"
        val metersMatcher = Pattern.compile("(\\d+)\\s*м\\b").matcher(normalized)
        if (metersMatcher.find()) {
            val meters = metersMatcher.group(1)?.toDoubleOrNull()
            if (meters != null) return meters / 1000.0
        }

        // Match kilometers, e.g., "1.5 км" or "2km"
        val kmMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:км|km)\\b").matcher(normalized)
        if (kmMatcher.find()) {
            return kmMatcher.group(1)?.toDoubleOrNull()
        }

        return null
    }

    /**
     * Parses price in RUB from strings like "450 ₽", "1 200 руб", "500р".
     */
    fun parsePrice(text: String): Int? {
        val normalized = text.replace("\\s".toRegex(), "")
        val matcher = Pattern.compile("(\\d+)(?:₽|руб|р)").matcher(normalized)
        if (matcher.find()) {
            return matcher.group(1)?.toIntOrNull()
        }
        return null
    }

    /**
     * Generates a unique key for the order to avoid sending duplicate notifications.
     */
    fun generateOrderHash(distanceKm: Double, price: Int, rawTextSnippet: String): String {
        return "${price}_${(distanceKm * 10).toInt()}_${rawTextSnippet.take(30).hashCode()}"
    }
}
