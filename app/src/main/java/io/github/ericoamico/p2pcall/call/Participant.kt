package io.github.ericoamico.p2pcall.call

/**
 * Participante remoto da sala.
 * @param name nome de exibição; null quando o participante não informou (cliente web ou versão antiga).
 */
data class Participant(val id: String, val name: String?, val connected: Boolean) {

    /** Nome para mostrar na lista e nos avisos. */
    val label: String get() = name ?: "Participante ${id.take(4)}"

    companion object {
        const val MAX_NAME_LENGTH = 40

        /**
         * Normaliza um nome digitado ou recebido: sem caracteres de controle nem espaços repetidos,
         * com no máximo [MAX_NAME_LENGTH] caracteres. Mesma regra do cliente desktop.
         */
        fun cleanName(name: String?): String? {
            if (name == null) return null
            var cleaned = name.filterNot { it.isISOControl() }
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (cleaned.length > MAX_NAME_LENGTH) cleaned = cleaned.take(MAX_NAME_LENGTH).trimEnd()
            return cleaned.ifEmpty { null }
        }
    }
}
