package com.example.twopchat.ui.chat

object MessageDeliveryStatus {
    fun merge(current: String?, delivery: String): String {
        val edited = current?.contains("edited", ignoreCase = true) == true
        val currentBase = current?.substringBefore('_')?.uppercase().orEmpty()
        val deliveryBase = delivery.substringBefore('_').uppercase()
        val mergedBase = if (rank(deliveryBase) >= rank(currentBase)) {
            deliveryBase
        } else {
            currentBase
        }
        return if (edited && !delivery.contains("edited", ignoreCase = true)) "${mergedBase}_edited" else mergedBase
    }

    private fun rank(status: String): Int = when (status) {
        "READ" -> 3
        "DELIVERED" -> 2
        "SENT" -> 1
        else -> 0
    }
}
