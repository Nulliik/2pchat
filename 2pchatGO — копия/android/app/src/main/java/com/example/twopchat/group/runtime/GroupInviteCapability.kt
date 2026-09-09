package com.example.twopchat.group.runtime

internal fun encodeGroupInviteCapability(randomBytes: ByteArray): String {
    require(randomBytes.size >= 32) { "group invite capabilities need at least 256 bits" }
    return randomBytes.joinToString("") { "%02x".format(it) }
}
