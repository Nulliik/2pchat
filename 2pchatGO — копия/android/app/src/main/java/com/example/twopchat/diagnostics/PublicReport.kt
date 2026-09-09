package com.example.twopchat.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/** Public reports are reconstructed from a closed vocabulary, never redacted logs. */
internal object PublicReport {
    const val MAX_CORE_BYTES = 8 * 1024
    const val MAX_REPORT_BYTES = 16 * 1024
    private val transports = listOf("direct", "tor", "yggdrasil", "mixed", "unknown")
    private val outcomes = listOf("success", "rejected", "dial_failed", "handshake_failed", "timeout")
    private val latencies = listOf("under_1s", "1_to_10s", "10_to_60s", "60s_plus")
    private val countBuckets = setOf("0", "1", "2-5", "6-20", "21-100", "101+")

    // No core-provided JSON object or string is forwarded without validation.
    fun coreSummary(raw: String?): JSONObject {
        val unavailable = { JSONObject().put("status", "unavailable") }
        if (raw == null || raw.length > MAX_CORE_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_CORE_BYTES) return unavailable()
        return try {
            val input = JSONObject(raw)
            require(input.keys().asSequence().toSet() == setOf("schema_version", "enabled", "outbound"))
            require(input.get("schema_version") == 1)
            require(input.get("enabled") is Boolean)
            val enabled = input.getBoolean("enabled")
            val sourceRows = input.getJSONArray("outbound")
            require(sourceRows.length() == transports.size)
            val rows = JSONArray()
            transports.forEachIndexed { index, transport ->
                val source = sourceRows.getJSONObject(index)
                require(source.keys().asSequence().toSet() == setOf("transport", "outcomes", "latency"))
                require(source.get("transport") == transport)
                val row = JSONObject().put("transport", transport)
                listOf("outcomes" to outcomes, "latency" to latencies).forEach { (field, keys) ->
                    val values = source.getJSONObject(field)
                    require(values.keys().asSequence().toSet() == keys.toSet())
                    val safe = JSONObject()
                    keys.forEach { key ->
                        val value = values.get(key)
                        require(value is String && value in countBuckets)
                        safe.put(key, value)
                    }
                    row.put(field, safe)
                }
                rows.put(row)
            }
            if (enabled) JSONObject().put("status", "available").put("outbound", rows)
            else JSONObject().put("status", "disabled")
        } catch (_: Exception) {
            unavailable()
        }
    }

    fun render(versionName: String, versionCode: Int, sdk: Int, core: String?, crash: CrashSummary?): String {
        val safeVersion = versionName.takeIf {
            it.length <= 32 && it.matches(Regex("[0-9]+(?:\\.[0-9]+){1,4}(?:\\([0-9]+\\))?"))
        } ?: "unavailable"
        val report = JSONObject()
            .put("report_schema", 1)
            .put("app_version", safeVersion)
            .put("app_version_code", versionCode.takeIf { it in 1..10_000_000 } ?: 0)
            .put("android_api_range", when (sdk) {
                in 24..28 -> "24-28"
                in 29..32 -> "29-32"
                in 33..35 -> "33-35"
                in 36..100 -> "36+"
                else -> "unknown"
            })
            .put("collection", "opt_in_local_current_process")
            .put("core", coreSummary(core))
            .put("previous_crash", when {
                crash == null -> JSONObject().put("status", "none_recorded")
                crash.versionCode != versionCode -> JSONObject().put("status", "different_build")
                else -> JSONObject().put("status", "recorded")
                    .put("category", crash.kind.name.lowercase(java.util.Locale.ROOT))
                    .put("component", crash.area.name.lowercase(java.util.Locale.ROOT))
            })
        val text = """
            2PChat public diagnostic summary

            This file contains coarse counters and crash categories, not raw logs.
            Counts cover completed outbound operations in this process since consent/reset.
            Cached sessions and incoming connections are not counted. A successful handshake
            does not prove message delivery; mixed failures have no winning transport.
            Counts and latency histograms are bucketed. They are not user counts or rates.
            No stack traces, messages, addresses, peer IDs, device model or timestamps are included.
            Publishing still links this report to your GitHub account and issue text.

        """.trimIndent() + "\n" + report.toString(2) + "\n"
        check(text.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES)
        return text
    }
}

internal enum class CrashKind { NULL_POINTER, ILLEGAL_STATE, INVALID_ARGUMENT, IO, SECURITY, OUT_OF_MEMORY, LINKAGE, STACK_OVERFLOW, OTHER }
internal enum class CrashArea { CORE_BRIDGE, TRANSPORT, TOR, YGGDRASIL, GROUP, STORAGE, UI, APP, OTHER }
internal data class CrashSummary(val versionCode: Int, val kind: CrashKind, val area: CrashArea) {
    companion object {
        fun from(throwable: Throwable, versionCode: Int): CrashSummary {
            // Exact platform types only. Never inspect message, cause, suppressed
            // exceptions, thread name, toString(), fileName, methodName or lineNumber.
            val kind = when (throwable.javaClass.name) {
                "java.lang.NullPointerException" -> CrashKind.NULL_POINTER
                "java.lang.IllegalStateException" -> CrashKind.ILLEGAL_STATE
                "java.lang.IllegalArgumentException" -> CrashKind.INVALID_ARGUMENT
                "java.io.IOException", "java.io.FileNotFoundException", "java.net.SocketException", "java.net.SocketTimeoutException" -> CrashKind.IO
                "java.lang.SecurityException" -> CrashKind.SECURITY
                "java.lang.OutOfMemoryError" -> CrashKind.OUT_OF_MEMORY
                "java.lang.UnsatisfiedLinkError", "java.lang.NoClassDefFoundError" -> CrashKind.LINKAGE
                "java.lang.StackOverflowError" -> CrashKind.STACK_OVERFLOW
                else -> CrashKind.OTHER
            }
            val area = throwable.stackTrace.take(24).firstNotNullOfOrNull { frame ->
                when {
                    frame.className == "com.example.twopchat.NativeBridge" || frame.className.startsWith("com.example.twopchat.bridge.") -> CrashArea.CORE_BRIDGE
                    frame.className.startsWith("com.example.twopchat.relay.") -> CrashArea.TRANSPORT
                    frame.className.startsWith("com.example.twopchat.tor.") -> CrashArea.TOR
                    frame.className.startsWith("com.example.twopchat.yggdrasil.") -> CrashArea.YGGDRASIL
                    frame.className.startsWith("com.example.twopchat.group.") -> CrashArea.GROUP
                    frame.className.startsWith("com.example.twopchat.data.") || frame.className.startsWith("com.example.twopchat.security.") -> CrashArea.STORAGE
                    frame.className.startsWith("com.example.twopchat.ui.") -> CrashArea.UI
                    frame.className == "com.example.twopchat.MainActivity" -> CrashArea.APP
                    else -> null
                }
            } ?: CrashArea.OTHER
            return CrashSummary(versionCode, kind, area)
        }
    }
}
