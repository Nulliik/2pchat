package com.example.twopchat.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.example.twopchat.BuildConfig
import com.example.twopchat.NativeBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Main process only; no network client, scheduler, or automatic upload exists. */
internal object PublicDiagnostics {
    private val crashHandlerInstalled = AtomicBoolean(false)
    @Volatile private var store: DiagnosticsStore? = null

    @Synchronized
    fun initialize(context: Context): DiagnosticsStore {
        store?.let { return it }
        val app = context.applicationContext
        return DiagnosticsStore(
            java.io.File(app.noBackupFilesDir, "public_diagnostics"),
            java.io.File(app.cacheDir, "public_reports"),
            object : DiagnosticsBackend {
                override fun setEnabled(enabled: Boolean) = NativeBridge.setDiagnosticsEnabled(enabled)
                override fun snapshot() = NativeBridge.getPublicDiagnosticsJSON()
            },
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
            revoke = { file ->
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        ).also { store = it }
    }

    fun installCrashHandler() {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        Thread.setDefaultUncaughtExceptionHandler(DiagnosticsCrashHandler(
            record = { store?.recordCrash(it) },
            delegate = Thread.getDefaultUncaughtExceptionHandler(),
            terminate = {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            },
        ))
    }

    /** Call on IO; the UI launches the returned intent only after explicit Share. */
    fun shareIntent(context: Context, preview: ReportPreview): Intent {
        val file = initialize(context).export(preview)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("2PChat diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
