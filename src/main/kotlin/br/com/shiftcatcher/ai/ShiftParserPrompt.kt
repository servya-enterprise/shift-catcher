package br.com.shiftcatcher.ai

import java.time.LocalDate

/**
 * The prompt is part of the contract, not a string thrown at a model. It is built here so the
 * schema, the examples and the framing stay in one reviewable place, and so a change to any of them
 * is a change to a file someone reads rather than to a literal buried in an adapter.
 *
 * Three things do most of the work:
 * - the reference date and timezone, because "amanha" is meaningless without them;
 * - the operator's known locations, so the model recognises places instead of inventing them;
 * - worked examples covering the shapes the deterministic parser keeps missing.
 */
object ShiftParserPrompt {
    /**
     * Ollama enforces this schema on the decoder, so a structurally invalid answer is not possible;
     * what remains possible is a *wrong* answer, which the hard rules downstream still judge.
     */
    val SCHEMA: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "isShiftOffer" to mapOf("type" to "boolean"),
                    "confidence" to mapOf("type" to "number"),
                    "date" to nullable("string"),
                    "startTime" to nullable("string"),
                    "endTime" to nullable("string"),
                    "durationHours" to nullable("integer"),
                    "location" to nullable("string"),
                    "city" to nullable("string"),
                    "amount" to nullable("number"),
                    "specialty" to nullable("string"),
                ),
            "required" to
                listOf(
                    "isShiftOffer",
                    "confidence",
                    "date",
                    "startTime",
                    "endTime",
                    "durationHours",
                    "location",
                    "city",
                    "amount",
                    "specialty",
                ),
        )

    fun build(
        text: String,
        referenceDate: LocalDate,
        timezone: String,
        knownLocations: List<String>,
    ): String {
        val places =
            if (knownLocations.isEmpty()) {
                "Nenhum local conhecido foi cadastrado."
            } else {
                "Locais conhecidos do operador: ${knownLocations.joinToString(", ")}."
            }
        return """
                        Voce interpreta mensagens de grupos de WhatsApp onde medicos oferecem e pegam plantoes.
                        Sua tarefa e extrair os campos de UMA mensagem. Voce nao decide nada alem disso.

                        Contexto:
                        - Hoje e $referenceDate, fuso $timezone.
                        - "hoje", "amanha", "depois de amanha" e dias da semana devem virar uma data absoluta.
                        - $places

                        Regras de preenchimento:
                        - isShiftOffer: true apenas se a mensagem OFERECE ou pede cobertura de um plantao.
                          Conversa comum, agradecimento, combinacao ja fechada ou pergunta generica = false.
                        - date: formato YYYY-MM-DD. Se a mensagem nao permite determinar a data, use null.
                        - startTime/endTime: formato HH:MM em 24 horas. "19-07" significa 19:00 as 07:00 do dia
                          seguinte. "7h a 12h" significa 07:00 as 12:00.
                        - durationHours: apenas quando a mensagem diz a DURACAO ("plantao de 12h"), nao quando
                          diz um horario ("as 12h").
                        - amount: apenas o valor do pagamento, em numero. "1.2k" e 1200. Nunca confunda com
                          horario. Sem valor declarado, use null.
                        - NUNCA invente um campo que a mensagem nao permite deduzir: use null.
                        - confidence: 0 a 1, quao seguro voce esta da leitura completa.

                        Exemplos:
                        Mensagem: "Plantao amanha 19-07 no PS Central R$ 1.200"
                        {"isShiftOffer":true,"confidence":0.97,"date":"${referenceDate.plusDays(
            1,
        )}","startTime":"19:00","endTime":"07:00","durationHours":null,"location":"PS Central","city":null,"amount":1200,"specialty":null}

                        Mensagem: "7h a 12h plantao na UPA norte dia 30/08"
                        {"isShiftOffer":true,"confidence":0.95,"date":"${referenceDate.withDayOfMonth(
            1,
        ).plusMonths(
            if (referenceDate.dayOfMonth > 30) 1 else 0,
        ).withDayOfMonth(
            30,
        )}","startTime":"07:00","endTime":"12:00","durationHours":null,"location":"UPA norte","city":null,"amount":null,"specialty":null}

                        Mensagem: "alguem consegue plantao pra amanha as 18h?"
                        {"isShiftOffer":true,"confidence":0.6,"date":"${referenceDate.plusDays(
            1,
        )}","startTime":"18:00","endTime":null,"durationHours":null,"location":null,"city":null,"amount":null,"specialty":null}

                        Mensagem: "beleza combinado, obrigado"
                        {"isShiftOffer":false,"confidence":0.99,"date":null,"startTime":null,"endTime":null,"durationHours":null,"location":null,"city":null,"amount":null,"specialty":null}

                        Agora a mensagem real. Responda apenas o JSON.
                        Mensagem: "${text.replace("\"", "'")}"
            """.trimIndent()
    }

    private fun nullable(type: String): Map<String, Any> = mapOf("type" to listOf(type, "null"))
}
