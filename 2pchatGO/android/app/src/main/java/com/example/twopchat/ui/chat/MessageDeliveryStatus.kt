package com.example.twopchat.ui.chat

object MessageDeliveryStatus {
    enum class DisplayState { QUEUED, SENDING, FAILED, SENT, DELIVERED, READ }

    fun displayState(status: String?, isMine: Boolean, isSavedMessages: Boolean = false): DisplayState? {
        if (!isMine) return null
        val base = status?.substringBefore('_')?.uppercase().orEmpty()
        return when {
            base == "FAILED" || base == "CANCELLED" -> DisplayState.FAILED
            base == "PENDING" -> DisplayState.QUEUED
            base == "SENDING" -> DisplayState.SENDING
            base == "READ" || isSavedMessages -> DisplayState.READ
            base == "DELIVERED" -> DisplayState.DELIVERED
            base == "SENT" -> DisplayState.SENT
            else -> null
        }
    }

    private val TERMINAL_TRANSFER_STATES = setOf("CANCELLED", "FAILED")

    fun merge(current: String?, delivery: String): String {
        val currentBase = current?.substringBefore('_')?.uppercase().orEmpty()
        val deliveryBase = delivery.substringBefore('_').uppercase()
        // Ignore late transfer callbacks after terminal state, but permit an explicit
        // retry (PENDING/SENDING) to begin a fresh attempt with the same message ID.
        if (currentBase in TERMINAL_TRANSFER_STATES && deliveryBase !in setOf("PENDING", "SENDING")) {
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
