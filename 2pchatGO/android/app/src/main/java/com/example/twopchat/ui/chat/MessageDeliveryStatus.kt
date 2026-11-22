package com.example.twopchat.ui.chat

object MessageDeliveryStatus {
    private val TERMINAL_TRANSFER_STATES = setOf("CANCELLED", "FAILED")

    fun merge(current: String?, delivery: String): String {
        val currentBase = current?.substringBefore('_')?.uppercase().orEmpty()
        val deliveryBase = delivery.substringBefore('_').uppercase()
        // File-transfer terminal states must not be undone by a replayed offer or a
        // late progress/status callback. A new explicit transfer can use a new ID.
        if (currentBase in TERMINAL_TRANSFER_STATES) {
            return currentBase
        }
        val edited = current?.contains("edited", ignoreCase = true) == true
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
