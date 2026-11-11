package io.github.ericoamico.p2pcall.call

import android.net.Uri

/**
 * Links de sala no mesmo formato da versão web (`https://servidor/?sala=nome`): um link copiado
 * aqui abre no navegador e no desktop, e um link de lá funciona aqui.
 */
object RoomLink {
    private const val ROOM_PARAMETER = "sala"

    fun build(server: String, room: String): String {
        val uri = Uri.parse(server.trim())
        return "${uri.scheme}://${uri.encodedAuthority}/?$ROOM_PARAMETER=${Uri.encode(room)}"
    }

    /** Reconhece um link de sala (com ou sem "https://") e devolve (servidor, sala). */
    fun parse(text: String): Pair<String, String>? {
        val trimmed = text.trim()
        if (!trimmed.contains("$ROOM_PARAMETER=", ignoreCase = true)) return null

        var uri = Uri.parse(trimmed)
        // Link colado sem o esquema, ex.: voz.tempsharing.com.br/?sala=amigos
        if (uri.scheme !in setOf("http", "https")) uri = Uri.parse("https://$trimmed")
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null

        val key = uri.queryParameterNames.firstOrNull { it.equals(ROOM_PARAMETER, ignoreCase = true) } ?: return null
        // getQueryParameter decodifica %xx e "+" vira espaço, como o URLSearchParams do web.
        val room = uri.getQueryParameter(key)?.trim()
        if (room.isNullOrEmpty()) return null
        return "${uri.scheme}://${uri.encodedAuthority}" to room
    }

    fun sameServer(a: String, b: String): Boolean {
        val ua = Uri.parse(a.trim())
        val ub = Uri.parse(b.trim())
        return ua.scheme.equals(ub.scheme, true) && ua.encodedAuthority.equals(ub.encodedAuthority, true)
    }
}
