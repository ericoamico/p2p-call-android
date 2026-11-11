package io.github.ericoamico.p2pcall.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTest {
    private val offer = listOf(
        "v=0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 0",
        "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=rtpmap:0 PCMU/8000",
        "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "a=rtpmap:96 VP8/90000",
    ).joinToString("\r\n", postfix = "\r\n")

    @Test
    fun adicionaEstereoETaxa() {
        val out = Sdp.tuneOpus(offer, 256000)
        assertTrue(out.contains("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;maxaveragebitrate=256000\r\n"))
        assertTrue(out.contains("a=rtpmap:96 VP8/90000\r\n"))
    }

    @Test
    fun criaFmtpQuandoFalta() {
        val sdp = "v=0\r\nm=audio 9 RTP 109\r\na=rtpmap:109 opus/48000/2\r\n"
        val out = Sdp.tuneOpus(sdp)
        assertEquals(
            "v=0\r\nm=audio 9 RTP 109\r\na=rtpmap:109 opus/48000/2\r\na=fmtp:109 stereo=1;sprop-stereo=1;useinbandfec=1\r\n",
            out,
        )
    }

    @Test
    fun semOpusNaoMuda() {
        val sdp = "v=0\r\nm=video 9 RTP 96\r\na=rtpmap:96 VP8/90000\r\n"
        assertEquals(sdp, Sdp.tuneOpus(sdp, 64000))
    }

    @Test
    fun limpaNome() {
        assertEquals("Ana Maria", Participant.cleanName("  Ana \u0007  Maria "))
        assertNull(Participant.cleanName("   "))
        assertEquals(40, Participant.cleanName("x".repeat(60))!!.length)
        assertEquals("Participante abcd", Participant("abcdefg", null, true).label)
    }
}
