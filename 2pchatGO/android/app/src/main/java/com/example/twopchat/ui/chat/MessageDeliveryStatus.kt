package com.example.twopchat.ui.chat

object MessageDeliveryStatus {
    fun merge(current: String?, delivery: String): String {
        val edited = current?.contains("edited", ignoreCase = true) == true
        return if (edited && !delivery.contains("edited", ignoreCase = true)) {
            "${delivery}_edited"
        } else {
            delivery
        }
    }
}
