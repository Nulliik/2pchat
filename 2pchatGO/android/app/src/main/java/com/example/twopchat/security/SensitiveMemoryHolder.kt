package com.example.twopchat.security

/**
 * Interface implemented by components holding decrypted or sensitive in-memory artifacts
 * (e.g. decrypted messages, database keys, decrypted strings, transient invite secrets, avatar bitmaps).
 *
 * Implementations MUST be:
 * - Thread-safe.
 * - Idempotent (safe to call multiple times in succession).
 * - Non-blocking / fast (never perform network or slow disk I/O inside this method).
 */
interface SensitiveMemoryHolder {
    /**
     * Purges and/or zeroizes all sensitive in-memory artifacts.
     */
    fun clearSensitiveMemory()
}
