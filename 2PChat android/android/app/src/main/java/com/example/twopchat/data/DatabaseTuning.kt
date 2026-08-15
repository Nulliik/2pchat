package com.example.twopchat.data

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * High-performance PRAGMA and WAL tuning for SQLCipher (AES-256 encrypted SQLite).
 */
object DatabaseTuning {
    private const val TAG = "DatabaseTuning"

    val PRAGMA_STATEMENTS = listOf(
        "PRAGMA synchronous = NORMAL",
        "PRAGMA foreign_keys = ON",
        "PRAGMA temp_store = MEMORY",
        "PRAGMA cache_size = -2000",
        "PRAGMA busy_timeout = 5000"
    )

    fun applyOptimizations(db: SQLiteDatabase) {
        try {
            db.enableWriteAheadLogging()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable Write-Ahead Logging (WAL)", e)
        }

        for (pragma in PRAGMA_STATEMENTS) {
            try {
                db.execSQL(pragma)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to execute $pragma", e)
            }
        }
    }
}
