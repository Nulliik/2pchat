package com.example.twopchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TorTransport(val storedValue: String) {
    AUTO("auto"),
    OBFS4("obfs4"),
    SNOWFLAKE("snowflake");

    companion object {
        fun fromStored(value: String?): TorTransport =
            entries.firstOrNull { it.storedValue == value } ?: AUTO
    }
}

/**
 * Offline bootstrap pool from Tor Browser's public built-in bridge catalog.
 *
 * Source: tor-browser-build/projects/tor-expert-bundle/pt_config.json
 * Revision: 7fb9dcedd548c02b0013d49761d56f5132132a80 (2026-08-13),
 * reachability checked from the Android emulator on 2026-08-13.
 *
 * Keeping the snapshot in the APK mirrors the Yggdrasil public-peer bootstrap
 * model and avoids a fingerprintable clearnet bridge-directory request before
 * Tor is available. Tor/Lyrebird probes the pool and selects a working bridge.
 */
internal object TorBridgeCatalog {
    val PUBLIC_OBFS4_BRIDGES: List<String> = listOf(
        "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D " +
            "cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
        "obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 " +
            "cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNinLmxQDpVa+7kTOa9/m+tGWT1SmSYpQ9uTBGa6Hw iat-mode=0",
        "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C " +
            "cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0",
        "obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 " +
            "cert=2uplIpLQ0q9+0qMFrK5pkaYRDOe460LL9WHBvatgkuRr/SL31wBOEupaMMJ6koRE6Ld0ew iat-mode=0",
        "obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 " +
            "cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1",
        "obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 " +
            "cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=1",
    )

    // Tor Browser's Snowflake rendezvous configuration. It uses ephemeral
    // volunteer WebRTC proxies instead of a fixed bridge address.
    val PUBLIC_SNOWFLAKE_BRIDGES: List<String> = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=app.datapacket.com,www.datapacket.com " +
            "ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478," +
            "stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478," +
            "stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 " +
            "utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=app.datapacket.com,www.datapacket.com " +
            "ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478," +
            "stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478," +
            "stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 " +
            "utls-imitate=hellorandomizedalpn",
    )

    private val _currentBridgeIndex = MutableStateFlow(0)
    val currentBridgeIndex: StateFlow<Int> = _currentBridgeIndex.asStateFlow()

    fun rotateNextBridge(transport: TorTransport = TorTransport.AUTO): String {
        val candidates = publicBridges(transport)
        val nextIdx = (_currentBridgeIndex.value + 1) % candidates.size
        _currentBridgeIndex.value = nextIdx
        return candidates[nextIdx]
    }

    fun getCurrentBridge(transport: TorTransport = TorTransport.AUTO): String =
        publicBridges(transport).getOrElse(_currentBridgeIndex.value) { publicBridges(transport).first() }

    fun select(
        customBridges: List<String>,
        publicBridgesEnabled: Boolean,
        transport: TorTransport = TorTransport.AUTO,
    ): List<String> {
        val custom = customBridges.map(String::trim).filter(String::isNotEmpty)
        return when {
            custom.isNotEmpty() -> custom
            publicBridgesEnabled -> {
                val candidates = publicBridges(transport)
                val current = getCurrentBridge(transport)
                val rest = candidates.filter { it != current }
                listOf(current) + rest
            }
            else -> emptyList()
        }
    }

    private fun publicBridges(transport: TorTransport): List<String> = when (transport) {
        TorTransport.OBFS4 -> PUBLIC_OBFS4_BRIDGES
        TorTransport.SNOWFLAKE -> PUBLIC_SNOWFLAKE_BRIDGES
        TorTransport.AUTO -> PUBLIC_OBFS4_BRIDGES + PUBLIC_SNOWFLAKE_BRIDGES
    }
}


