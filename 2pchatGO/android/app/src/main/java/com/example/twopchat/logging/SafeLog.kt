package com.example.twopchat.logging

import android.util.Log
import com.example.twopchat.BuildConfig
import com.example.twopchat.AppLog

/**
 * Единственная разрешённая точка вывода в logcat.
 * - release: v/d/i подавлены; w/e выводятся после редакции.
 * - debug: всё выводится, но тоже после редакции (логи из багрепортов
 *   тестеров не должны содержать метаданных).
 * Импорт android.util.Log разрешён только здесь (см. scripts/check_no_raw_log.sh).
 */
object SafeLog {
    @JvmStatic fun v(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.v(tag, r(msg)) }
    @JvmStatic fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.d(tag, r(msg)) }
    @JvmStatic fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, r(msg)) }
    @JvmStatic fun w(tag: String, msg: String, t: Throwable? = null) { Log.w(tag, r(msg), t) }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, r(msg), t) }

    /** Короткий идентификатор пира для логов: первые 8 hex-символов. */
    @JvmStatic fun fp(fingerprint: String?): String =
        fingerprint?.take(8)?.plus("…") ?: "<null>"

    @JvmStatic fun getStackTraceString(t: Throwable): String = Log.getStackTraceString(t)

    private fun r(msg: String): String = AppLog.redactSensitive(msg)
}
