package com.example.twopchat.data

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * High-performance PRAGMA and WAL tuning for SQLCipher (AES-256 encrypted SQLite).
 */
object DatabaseTuning {
    private const val TAG = "DatabaseTuning"

    val PRAGMA_STATEMENTS = listOf(
        "PRAGMA cipher_memory_security = OFF",
        "PRAGMA synchronous = NORMAL",
        "PRAGMA foreign_keys = ON",
        "PRAGMA temp_store = MEMORY",
        "PRAGMA cache_size = -64000",
        "PRAGMA busy_timeout = 10000",
        "PRAGMA mmap_size = 268435456"
    )

    fun applyOptimizations(db: SQLiteDatabase) {
        try {
            db.enableWriteAheadLogging()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable Write-Ahead Logging (WAL)", e)
        }

        for (pragma in PRAGMA_STATEMENTS) {
            try {
                db.rawQuery(pragma, null).use { cursor ->
                    cursor.moveToFirst()
                }
            } catch (_: Exception) {
                try {
                    db.execSQL(pragma)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to execute $pragma", e)
                }
            }
        }
    }

    fun optimizeDatabase(db: SQLiteDatabase) {
        try {
            db.execSQL("PRAGMA optimize")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to execute PRAGMA optimize", e)
        }
    }
}
