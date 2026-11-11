package io.github.ericoamico.p2pcall.call

/**
 * Ajustes de SDP para o Opus ficar igual ao do desktop: estéreo (o áudio do aparelho vai em L/R)
 * e, na descrição remota, a taxa de bits escolhida em "Qualidade" (maxaveragebitrate configura o
 * nosso codificador, como o maxBitrate do sender no navegador).
 */
object Sdp {
    private val opusRtpmap = Regex("^a=rtpmap:(\\d+) opus/48000", setOf(RegexOption.IGNORE_CASE))

    fun tuneOpus(sdp: String, bitrateBps: Int? = null): String {
        val lines = sdp.split("\n").map { it.trimEnd('\r') }.toMutableList()
        if (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)

        val payloads = lines.mapNotNull { opusRtpmap.find(it)?.groupValues?.get(1) }.toSet()
        if (payloads.isEmpty()) return sdp

        val wanted = linkedMapOf("stereo" to "1", "sprop-stereo" to "1", "useinbandfec" to "1")
        if (bitrateBps != null) wanted["maxaveragebitrate"] = bitrateBps.toString()

        val out = mutableListOf<String>()
        val withFmtp = mutableSetOf<String>()
        for (line in lines) {
            val pt = payloads.firstOrNull { line.startsWith("a=fmtp:$it ") }
            if (pt == null) {
                out += line
                continue
            }
            withFmtp += pt
            val params = linkedMapOf<String, String>()
            line.substringAfter(' ').split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                params[it.substringBefore('=')] = it.substringAfter('=', "")
            }
            params.putAll(wanted)
            out += "a=fmtp:$pt " + params.entries.joinToString(";") { "${it.key}=${it.value}" }
        }

        // Opus sem linha fmtp: cria logo depois do rtpmap.
        for (pt in payloads - withFmtp) {
            val index = out.indexOfFirst { opusRtpmap.find(it)?.groupValues?.get(1) == pt }
            if (index >= 0) out.add(index + 1, "a=fmtp:$pt " + wanted.entries.joinToString(";") { "${it.key}=${it.value}" })
        }
        return out.joinToString("\r\n", postfix = "\r\n")
    }
}
