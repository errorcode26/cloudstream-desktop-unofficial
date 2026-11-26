package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.AuthAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniListTokenParserTest {

    @Test
    fun `splitRedirectUrl parses fragment and query parameters cleanly`() {
        val url = "https://anilist.co/api/v2/oauth/pin#access_token=abc.def.ghi==&token_type=Bearer&expires_in=31536000"
        val params = AuthAPI.splitRedirectUrl(url)
        assertEquals("abc.def.ghi==", params["access_token"])
        assertEquals("Bearer", params["token_type"])
        assertEquals("31536000", params["expires_in"])

        val bareParams = AuthAPI.splitRedirectUrl("access_token=rawTok123&expires_in=7200")
        assertEquals("rawTok123", bareParams["access_token"])
        assertEquals("7200", bareParams["expires_in"])
    }

    @Test
    fun `parseTokenInput accepts raw jwt with padding and bearer prefix`() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.payloadSegment.signature=="
        val parsedJwt = AniListApi.parseTokenInput(jwt)
        assertNotNull(parsedJwt)
        assertEquals(jwt, parsedJwt.accessToken)
        assertNotNull(parsedJwt.accessTokenLifetime)

        val bearerParsed = AniListApi.parseTokenInput("  Bearer $jwt  ")
        assertNotNull(bearerParsed)
        assertEquals(jwt, bearerParsed.accessToken)
    }

    @Test
    fun `parseTokenInput accepts redirect url fragment and honors expires_in`() {
        val beforeUnix = AuthAPI.unixTime
        val parsed = AniListApi.parseTokenInput(
            "https://anilist.co/api/v2/oauth/pin#access_token=tok_implicit_99&token_type=Bearer&expires_in=3600",
        )
        val afterUnix = AuthAPI.unixTime
        assertNotNull(parsed)
        assertEquals("tok_implicit_99", parsed.accessToken)
        val lifetime = parsed.accessTokenLifetime ?: 0L
        assertTrue(lifetime in (beforeUnix + 3600L)..(afterUnix + 3600L))
    }

    @Test
    fun `parseTokenInput rejects blank urls without tokens and malformed text`() {
        assertNull(AniListApi.parseTokenInput(null))
        assertNull(AniListApi.parseTokenInput("   "))
        assertNull(AniListApi.parseTokenInput("https://anilist.co/api/v2/oauth/authorize?client_id=123"))
        assertNull(AniListApi.parseTokenInput("not a single token"))
        assertNull(AniListApi.parseTokenInput("<html>Error</html>"))
        assertNull(AniListApi.parseTokenInput("{\"error\":\"invalid_grant\"}"))
    }

    @Test
    fun `hasGraphQlErrors distinguishes graphql failures from valid payloads`() {
        assertTrue(AniListApi.hasGraphQlErrors(null))
        assertTrue(AniListApi.hasGraphQlErrors(""))
        assertTrue(AniListApi.hasGraphQlErrors("""{"errors":[{"message":"Invalid token","status":401}],"data":null}"""))
        assertTrue(AniListApi.hasGraphQlErrors("not json"))
        assertFalse(AniListApi.hasGraphQlErrors("""{"data":{"Viewer":{"id":42,"name":"Tester"}}}"""))
    }
}
