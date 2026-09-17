package com.acmqu.acmo

import com.acmqu.acmo.voice.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun `grammar is a json array ending in unk`() {
        assertEquals("""["ack mo","ak mo","ac mo","hack mo","back mo","act mo","acme","[unk]"]""", WakeWord.GRAMMAR)
    }

    @Test
    fun `what vosk returned for hey acmo wakes`() {
        for (heard in listOf("ak mo", "ack mo", "hack mo", "ak ak mo", "[unk] ack mo", "acme", "ack mo [unk]")) {
            assertTrue(heard, WakeWord.matches(heard))
        }
    }

    @Test
    fun `decoys and a bare mo do not wake by default`() {
        WakeWord.acceptBareMo = false
        for (heard in listOf("", "[unk]", "[unk] mo", "mo", "tell me more", "acmeo")) {
            assertFalse(heard, WakeWord.matches(heard))
        }
    }

    @Test
    fun `bare mo wakes when allowed`() {
        WakeWord.acceptBareMo = true
        try {
            assertTrue(WakeWord.matches("[unk] mo"))
        } finally {
            WakeWord.acceptBareMo = false
        }
    }
}
