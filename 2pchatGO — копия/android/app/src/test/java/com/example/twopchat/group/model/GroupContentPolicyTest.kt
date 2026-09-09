package com.example.twopchat.group.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupContentPolicyTest {
    @Test
    fun detectsExternallyActionableLinksDeterministically() {
        listOf(
            "https://example.com/path?q=1",
            "See HTTP://EXAMPLE.COM",
            "mirror at ftp://files.example.org/archive",
            "open www.example.net/docs",
            "visit example.co.uk/path",
            "IDN https://xn--e1afmkfd.xn--p1ai",
            "mailto:team@example.org",
        ).forEach { text ->
            assertTrue(text, GroupContentPolicy.containsExternalLink(text))
        }
    }

    @Test
    fun ordinaryTextAndLinkLikeFragmentsAreNotLinks() {
        listOf(
            "",
            "distributed systems are resilient",
            "version 1.2.3",
            "local/path/to/file",
            "user@example.com",
            "example",
            "www is an acronym",
        ).forEach { text ->
            assertFalse(text, GroupContentPolicy.containsExternalLink(text))
        }
    }
}
