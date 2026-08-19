package com.example.twopchat.group.model

/**
 * Deterministic, platform-independent link recognition used by both local and
 * remote event validation. It intentionally recognises only externally
 * actionable URLs, rather than relying on Android's mutable Patterns table.
 */
object GroupContentPolicy {
    private val schemeUrl = Regex(
        """(?i)(?:https?|ftp)://[^\s<>{}\[\]]+|mailto:[^\s<>{}\[\]]+""",
    )
    private val wwwUrl = Regex(
        """(?i)(?<![\p{L}\p{N}_-])www\.[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?(?:/[^\s<>{}\[\]]*)?""",
    )
    private val bareDomainUrl = Regex(
        """(?i)(?<![@\p{L}\p{N}_-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{2,59})(?::[0-9]{1,5})?(?:/[^\s<>{}\[\]]*)?""",
    )

    fun containsExternalLink(text: String): Boolean =
        text.isNotBlank() && (
            schemeUrl.containsMatchIn(text) ||
                wwwUrl.containsMatchIn(text) ||
                bareDomainUrl.containsMatchIn(text)
            )
}
