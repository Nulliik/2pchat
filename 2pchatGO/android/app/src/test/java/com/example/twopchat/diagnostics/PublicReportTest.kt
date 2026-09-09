package com.example.twopchat.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// A shared fixture is checked against Go's actual serializer, not generated from
// the Kotlin parser's key lists, so producer/consumer schema drift fails tests.
internal fun validCore(): JSONObject {
    val relative = "pkg/diagnostics/testdata/core_snapshot_v1.json"
    val fixture = listOf(File("../core-go/$relative"), File("core-go/$relative"))
        .firstOrNull { it.isFile } ?: error("Native diagnostics contract fixture missing")
    return JSONObject(fixture.readText())
}

class PublicReportTest {
    @Test fun hostileNativePayloadsFailClosed() {
        val secret = "Alice alice@example.com [::1] 10.0.0.1 secret.onion /data/user/0/private.key"
        val inputs = listOf(
            secret, "[1,2]", "{}", "x".repeat(PublicReport.MAX_CORE_BYTES + 1),
            validCore().put("identity", secret).toString(),
            validCore().put("schema_version", 2).toString(),
            validCore().put("enabled", "true").toString(),
            validCore().apply { getJSONArray("outbound").getJSONObject(0).put("transport", secret) }.toString(),
            validCore().apply { getJSONArray("outbound").getJSONObject(0).getJSONObject("outcomes").put("success", secret) }.toString(),
            validCore().apply { getJSONArray("outbound").getJSONObject(0).getJSONObject("outcomes").put("success", 20) }.toString(),
        )
        inputs.forEach { input ->
            val result = PublicReport.coreSummary(input)
            assertEquals("unavailable", result.getString("status"))
            assertEquals(1, result.length())
            assertFalse(result.toString().contains(secret))
        }
        assertEquals("available", PublicReport.coreSummary(validCore().toString()).getString("status"))
    }

    @Test fun exceptionMessagesNamesFramesAndCausesAreNeverExported() {
        val secret = "CONTACT_NAME secret-token user@example.org [::ffff:192.168.1.8] C:\\Users\\Alice\\message.txt"
        val error = IllegalStateException(secret, RuntimeException(secret)).apply {
            addSuppressed(RuntimeException(secret))
            stackTrace = arrayOf(StackTraceElement("com.example.twopchat.group.$secret", secret, secret, 123456))
        }
        val report = PublicReport.render("0.0.9.3(1)", 30, 35, validCore().toString(), CrashSummary.from(error, 30))
        listOf("CONTACT_NAME", "secret-token", "user@example.org", "192.168", "Alice", "123456").forEach {
            assertFalse("leaked $it", report.contains(it))
        }
        assertTrue(report.contains("illegal_state"))
        assertTrue(report.contains("group"))
        assertFalse(PublicReport.render(secret, 30, 35, null, null).contains(secret))
    }

    @Test fun crashCaptureDoesNotRenderOrWalkThrowableChains() {
        val hostile = object : Throwable() {
            override val message: String get() = error("must not read message")
            override val cause: Throwable get() = error("must not read cause")
            override fun toString(): String = error("must not render exception")
        }
        assertEquals(CrashKind.OTHER, CrashSummary.from(hostile, 30).kind)
    }
}
