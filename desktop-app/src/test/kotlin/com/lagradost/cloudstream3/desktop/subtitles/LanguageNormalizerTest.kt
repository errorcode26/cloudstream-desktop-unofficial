package com.lagradost.cloudstream3.desktop.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageNormalizerTest {

    @Test
    fun `canonical key normalizes language code`() {
        val keyRu = LanguageNormalizer.getCanonicalKey("ru", "https://cdn.example.com/subs/test.srt")
        val keyRus = LanguageNormalizer.getCanonicalKey("rus", "https://cdn.example.com/subs/test.srt")
        val keyRussian = LanguageNormalizer.getCanonicalKey("Russian", "https://cdn.example.com/subs/test.srt")

        assertEquals(keyRu, keyRus)
        assertEquals(keyRus, keyRussian)
        assertEquals("rus:https://cdn.example.com/subs/test.srt", keyRus)
    }

    @Test
    fun `canonical key strips pre-signed auth query params for subtitle files`() {
        val urlA = "https://cdn.example.com/subtitles/track1.srt?Policy=ey...&Signature=sigA&EpochTime=1796142627&Key-Pair-Id=K123"
        val urlB = "https://cdn.example.com/subtitles/track1.srt?Policy=ey...&Signature=sigB&EpochTime=1796145879&Key-Pair-Id=K123"

        val keyA = LanguageNormalizer.getCanonicalKey("eng", urlA)
        val keyB = LanguageNormalizer.getCanonicalKey("English", urlB)

        assertEquals("eng:https://cdn.example.com/subtitles/track1.srt", keyA)
        assertEquals(keyA, keyB)
    }

    @Test
    fun `canonical key decodes proxied target URLs and deduplicates across signers`() {
        val proxyUrlA = "http://127.0.0.1:8080/proxy/subtitle?url=https%3A%2F%2Fcdn.example.com%2Fmsubt%2Fhash123.srt%3FPolicy%3DtestA%26Signature%3DsigA%26EpochTime%3D1796142627"
        val proxyUrlB = "http://127.0.0.1:8080/proxy/subtitle?url=https%3A%2F%2Fcdn.example.com%2Fmsubt%2Fhash123.srt%3FPolicy%3DtestB%26Signature%3DsigB%26EpochTime%3D1796145879"

        val keyA = LanguageNormalizer.getCanonicalKey("rus", proxyUrlA)
        val keyB = LanguageNormalizer.getCanonicalKey("rus", proxyUrlB)

        assertEquals("rus:https://cdn.example.com/msubt/hash123.srt", keyA)
        assertEquals(keyA, keyB)
    }

    @Test
    fun `canonical key preserves functional non-transient query parameters on generic endpoints`() {
        val urlA = "https://api.example.com/v1/subtitles?id=456&sig=transient1&token=abc"
        val urlB = "https://api.example.com/v1/subtitles?sig=transient2&token=def&id=456"

        val keyA = LanguageNormalizer.getCanonicalKey("fre", urlA)
        val keyB = LanguageNormalizer.getCanonicalKey("fr", urlB)

        assertEquals("fre:https://api.example.com/v1/subtitles?id=456", keyA)
        assertEquals(keyA, keyB)
    }

    @Test
    fun `canonical key handles blank or null urls gracefully`() {
        assertEquals("und:", LanguageNormalizer.getCanonicalKey(null, null))
        assertEquals("eng:", LanguageNormalizer.getCanonicalKey("eng", ""))
        assertEquals("und:https://cdn.example.com/sub.vtt", LanguageNormalizer.getCanonicalKey("", "https://cdn.example.com/sub.vtt"))
    }
}
