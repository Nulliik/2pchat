package com.example.twopchat.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DiagnosticsExportBoundaryTest {
    private fun mainRoot(): File = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory } ?: error("Android sources unavailable for export-boundary check")

    @Test fun rawLogExportCannotBeRestoredViaSettingsOrFileProvider() {
        val root = mainRoot()
        for (name in listOf("SettingsTab.kt", "NetworkDiagnosticsDialog.kt")) {
            val source = File(root, "java/com/example/twopchat/ui/main/$name").readText()
            assertFalse(source.contains("Intent.EXTRA_STREAM"))
            assertFalse(source.contains("FileProvider.getUriForFile"))
            assertFalse(source.contains("AnnotatedString(formattedLogs.text)"))
            assertTrue(source.contains("PublicDiagnosticReportDialog"))
        }
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "res/xml/file_paths.xml"))
        for (tag in listOf("files-path", "cache-path", "root-path", "external-path")) {
            val nodes = xml.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val path = nodes.item(i).attributes.getNamedItem("path").nodeValue
                assertFalse("overbroad shared path $path", path in setOf(".", "./", "/", "config", "config/"))
            }
        }
    }

    @Test fun reportUiStringsExistInEverySupportedLanguage() {
        val source = File(mainRoot(), "java/com/example/twopchat/data/Localizations.kt").readText()
        val keys = listOf("title", "subtitle", "description", "collect", "retention", "privacy", "preview", "clear",
            "preview_description", "copy", "save", "share", "close", "error", "raw_warning")
        keys.forEach { key ->
            assertEquals("missing or duplicate translation for $key", 7, Regex("\"diagnostics_$key\" to ").findAll(source).count())
        }
    }
}
