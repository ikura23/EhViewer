package com.hippo.ehviewer.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieSignInParserTest {
    @Test
    fun parsesEhViewerSettingsMultilineFormat() {
        val raw = """
            ipb_member_id: 1234567
            ipb_pass_hash: abcdef0123456789abcdef0123456789
            igneous: 987654321fedcba
        """.trimIndent()
        val parsed = parseIdentityCookies(raw)
        assertEquals("1234567", parsed.ipbMemberId)
        assertEquals("abcdef0123456789abcdef0123456789", parsed.ipbPassHash)
        assertEquals("987654321fedcba", parsed.igneous)
    }

    @Test
    fun parsesBrowserCookieHeaderWithExtraCookies() {
        val raw = "Cookie: nw=1; ipb_member_id=424242; __utmp=skip; ipb_pass_hash=deadbeef1234; igneous=cafebabe;"
        val parsed = parseIdentityCookies(raw)
        assertEquals("424242", parsed.ipbMemberId)
        assertEquals("deadbeef1234", parsed.ipbPassHash)
        assertEquals("cafebabe", parsed.igneous)
    }

    @Test
    fun parsesMultilineEqualsFormatWithCaseInsensitiveKeys() {
        val raw = """
            IPB_MEMBER_ID = 998877
            ipb_pass_hash= 001122334455
        """.trimIndent()
        val parsed = parseIdentityCookies(raw)
        assertEquals("998877", parsed.ipbMemberId)
        assertEquals("001122334455", parsed.ipbPassHash)
        assertNull(parsed.igneous)
    }

    @Test
    fun parsesCookieEditorJsonArrayFormat() {
        val raw = """
            [
              {"domain": ".exhentai.org", "name": "ipb_member_id", "value": "556677"},
              {"domain": ".exhentai.org", "name": "ipb_pass_hash", "value": "aa11bb22"},
              {"domain": ".exhentai.org", "name": "igneous", "value": "cc33dd44"}
            ]
        """.trimIndent()
        val parsed = parseIdentityCookies(raw)
        assertEquals("556677", parsed.ipbMemberId)
        assertEquals("aa11bb22", parsed.ipbPassHash)
        assertEquals("cc33dd44", parsed.igneous)
    }

    @Test
    fun parsesJsonObjectFormat() {
        val raw = """{"ipb_member_id": "10001", "ipb_pass_hash": "ff00ee11", "igneous": "778899"}"""
        val parsed = parseIdentityCookies(raw)
        assertEquals("10001", parsed.ipbMemberId)
        assertEquals("ff00ee11", parsed.ipbPassHash)
        assertEquals("778899", parsed.igneous)
    }

    @Test
    fun ignoresInvalidOrPlaceholderIgneousValues() {
        for (ignored in listOf("null", "NULL", "mystery", "expired", "")) {
            val raw = "ipb_member_id: 123\nipb_pass_hash: 456\nigneous: $ignored"
            val parsed = parseIdentityCookies(raw)
            assertEquals("123", parsed.ipbMemberId)
            assertEquals("456", parsed.ipbPassHash)
            assertNull("Expected $ignored to be ignored", parsed.igneous)
        }
    }

    @Test
    fun returnsEmptyForUnrelatedTextAndRejectsInvalidCookieCharacters() {
        assertTrue(parseIdentityCookies("hello world").isEmpty)
        assertTrue(parseIdentityCookies("").isEmpty)
        assertFalse(isValidCookieValue(""))
        assertFalse(isValidCookieValue("abc;def"))
        assertFalse(isValidCookieValue("abc def"))
        assertFalse(isValidCookieValue("abc\ndef"))
        assertTrue(isValidCookieValue("abcdef123456"))
    }
}
