package com.postbox.shared.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceUrlTest {
    @Test
    fun bareHostDefaultsToHttps() {
        assertEquals("https://postbox.example.com", InstanceUrl.normalize("postbox.example.com"))
        assertEquals("https://postbox.example.com", InstanceUrl.normalize("  postbox.example.com  "))
    }

    @Test
    fun explicitSchemesAreKeptAndPathIsDropped() {
        assertEquals("https://postbox.example.com", InstanceUrl.normalize("https://postbox.example.com/"))
        assertEquals("https://postbox.example.com", InstanceUrl.normalize("https://postbox.example.com/api"))
        assertEquals("http://192.168.1.10:8080", InstanceUrl.normalize("http://192.168.1.10:8080/postbox"))
    }

    @Test
    fun hostWithPortIsPreservedAndDefaultPortDropped() {
        assertEquals("https://postbox.example.com:8443", InstanceUrl.normalize("postbox.example.com:8443"))
        assertEquals("https://postbox.example.com", InstanceUrl.normalize("https://postbox.example.com:443"))
        assertEquals("http://localhost:8080", InstanceUrl.normalize("http://localhost:8080"))
    }

    @Test
    fun invalidInputIsRejected() {
        assertNull(InstanceUrl.normalize(""))
        assertNull(InstanceUrl.normalize("   "))
        assertNull(InstanceUrl.normalize("ftp://example.com"))
        assertNull(InstanceUrl.normalize("https://"))
        assertNull(InstanceUrl.normalize("://nohost"))
    }

    @Test
    fun plainHttpFlagFollowsScheme() {
        assertTrue(InstanceUrl.isPlainHttp("http://192.168.1.10:8080"))
        assertFalse(InstanceUrl.isPlainHttp("https://postbox.example.com"))
    }
}
