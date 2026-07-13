package com.example.twopchat.ui.main

internal fun isDirectP2pTransport(value: String): Boolean =
    value.trim().equals("Direct P2P", ignoreCase = true)
