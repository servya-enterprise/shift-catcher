package br.com.shiftcatcher.identity

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * The introduction Clara Care writes and this product reads.
 *
 * AUTODEC-0012 decision 2. It carries identity and nothing else: who you are, for how long, and for
 * which product. No role, no permission and no tenant travels in it, and none would be honoured if
 * it did — what an operator may do is decided here, from this database.
 *
 * THE FORMAT IS NOT A JWT, AND THAT IS THE SECURITY DECISION, not a preference.
 *
 * A JWT announces its own algorithm in a header the attacker controls, and the family of bugs that
 * follows — `alg: none`, RS256 verified as HS256 with the public key as the secret — exists only
 * because there is a negotiation to attack. There is no negotiation here. The bytes are
 * `base64url(payload) "." base64url(signature)`, the signature is always Ed25519, and the verifier
 * has no branch in which it could be anything else. Removing the header removes the attack.
 *
 * The primitive itself is nobody's invention: Ed25519 has been in the JDK since 15, and this file
 * calls it. No dependency is added to a service that has neither Spring Security nor a JOSE library.
 */
data class HandoffAssertion(
    /** Clara Care's staff user id. Never an e-mail — see `V10__operator.sql`. */
    val subject: String,
    val displayName: String,
    val issuer: String,
    val audience: String,
    val id: String,
    val expiresAt: Instant,
)

sealed interface HandoffFailure {
    val reason: String

    data object Malformed : HandoffFailure {
        override val reason = "MALFORMED"
    }

    data object BadSignature : HandoffFailure {
        override val reason = "BAD_SIGNATURE"
    }

    data object Expired : HandoffFailure {
        override val reason = "EXPIRED"
    }

    data object WrongIssuer : HandoffFailure {
        override val reason = "WRONG_ISSUER"
    }

    data object WrongAudience : HandoffFailure {
        override val reason = "WRONG_AUDIENCE"
    }

    data object NotConfigured : HandoffFailure {
        override val reason = "NOT_CONFIGURED"
    }

    /**
     * Not produced by the verifier — the assertion was perfectly good. It is here so the one enum
     * covers every way the door can refuse, including the one that needs the database to know.
     */
    data object AlreadyUsed : HandoffFailure {
        override val reason = "ALREADY_USED"
    }
}

sealed interface HandoffResult {
    data class Accepted(val assertion: HandoffAssertion) : HandoffResult

    data class Refused(val failure: HandoffFailure) : HandoffResult
}

/**
 * Verifies an assertion against one public key, offline.
 *
 * Offline is the point rather than an optimisation: `AUTODEC-0008` decision 1 says these two
 * backends never call each other, and `AUTODEC-0010` decision 13 amended that for the frontend
 * only. A redemption call to Clara Care would be exactly the runtime dependency both decisions
 * exist to prevent, so the key travels instead of the question.
 */
class HandoffVerifier(
    publicKeyBase64: String,
    private val expectedIssuer: String,
    private val expectedAudience: String,
    private val clockSkew: java.time.Duration,
) {
    private val key: PublicKey? = decodeKey(publicKeyBase64)

    /** Whether this deployment can accept an introduction at all. Blank key means the door is shut. */
    val configured: Boolean get() = key != null

    fun verify(
        token: String,
        now: Instant,
    ): HandoffResult {
        val key = this.key ?: return HandoffResult.Refused(HandoffFailure.NotConfigured)

        val dot = token.indexOf('.')
        // One dot exactly. A second one means somebody is presenting a JWT, and answering a JWT at
        // all is how a verifier ends up parsing a header it promised itself it would never read.
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length - 1) {
            return HandoffResult.Refused(HandoffFailure.Malformed)
        }

        val payloadPart = token.substring(0, dot)
        val signaturePart = token.substring(dot + 1)

        val payloadBytes: ByteArray
        val signatureBytes: ByteArray
        try {
            payloadBytes = DECODER.decode(payloadPart)
            signatureBytes = DECODER.decode(signaturePart)
        } catch (_: IllegalArgumentException) {
            return HandoffResult.Refused(HandoffFailure.Malformed)
        }

        // The signature is checked BEFORE the payload is looked at, so nothing an unauthenticated
        // caller wrote reaches the parser. The bytes signed are the encoded payload exactly as it
        // arrived, never a re-serialisation of it: re-encoding and signing the result is how two
        // implementations end up disagreeing about whitespace and one of them accepts a forgery.
        val verifier = Signature.getInstance(ALGORITHM)
        verifier.initVerify(key)
        verifier.update(payloadPart.toByteArray(StandardCharsets.US_ASCII))
        if (!verifier.verify(signatureBytes)) return HandoffResult.Refused(HandoffFailure.BadSignature)

        val fields = parse(String(payloadBytes, StandardCharsets.UTF_8)) ?: return HandoffResult.Refused(HandoffFailure.Malformed)

        val subject = fields["sub"]?.takeIf(String::isNotBlank) ?: return HandoffResult.Refused(HandoffFailure.Malformed)
        val issuer = fields["iss"] ?: return HandoffResult.Refused(HandoffFailure.Malformed)
        val audience = fields["aud"] ?: return HandoffResult.Refused(HandoffFailure.Malformed)
        val id = fields["jti"]?.takeIf(String::isNotBlank) ?: return HandoffResult.Refused(HandoffFailure.Malformed)
        val expiresAt =
            fields["exp"]?.toLongOrNull()?.let(Instant::ofEpochSecond)
                ?: return HandoffResult.Refused(HandoffFailure.Malformed)

        if (issuer != expectedIssuer) return HandoffResult.Refused(HandoffFailure.WrongIssuer)
        // An assertion minted for another product is refused here rather than being merely useless.
        // Audience is what stops one signing key from becoming a key to every door it ever opened.
        if (audience != expectedAudience) return HandoffResult.Refused(HandoffFailure.WrongAudience)
        if (expiresAt.plus(clockSkew).isBefore(now)) return HandoffResult.Refused(HandoffFailure.Expired)

        return HandoffResult.Accepted(
            HandoffAssertion(
                subject = subject,
                displayName = fields["name"]?.takeIf(String::isNotBlank) ?: subject,
                issuer = issuer,
                audience = audience,
                id = id,
                expiresAt = expiresAt,
            ),
        )
    }

    /**
     * A deliberately small reader for a deliberately small document.
     *
     * The payload is a flat object of string and number values that this project's counterpart
     * writes. It is parsed by hand rather than by Jackson for one reason: a verified payload is
     * still attacker-influenced if the signature check is ever reordered, and a parser that cannot
     * construct types, resolve references or recurse cannot be the interesting half of that
     * mistake. Anything it does not understand is malformed, which is the safe answer.
     */
    private fun parse(json: String): Map<String, String>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val fields = mutableMapOf<String, String>()
        var index = 1
        val body = trimmed
        while (index < body.length) {
            while (index < body.length && (body[index].isWhitespace() || body[index] == ',')) index++
            if (index >= body.length || body[index] == '}') break
            if (body[index] != '"') return null
            val keyEnd = body.indexOf('"', index + 1)
            if (keyEnd < 0) return null
            val key = body.substring(index + 1, keyEnd)
            index = keyEnd + 1
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length || body[index] != ':') return null
            index++
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length) return null
            if (body[index] == '"') {
                val builder = StringBuilder()
                index++
                while (index < body.length && body[index] != '"') {
                    // One escape, because the writer emits one: a quoted quote. Anything else in a
                    // display name is rejected rather than half-understood.
                    if (body[index] == '\\') {
                        index++
                        if (index >= body.length) return null
                    }
                    builder.append(body[index])
                    index++
                }
                if (index >= body.length) return null
                fields[key] = builder.toString()
                index++
            } else {
                val start = index
                while (index < body.length && body[index] != ',' && body[index] != '}') index++
                fields[key] = body.substring(start, index).trim()
            }
        }
        return fields
    }

    private companion object {
        const val ALGORITHM = "Ed25519"
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()

        fun decodeKey(base64: String): PublicKey? {
            if (base64.isBlank()) return null
            return try {
                val bytes = Base64.getDecoder().decode(base64.trim())
                KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
            } catch (_: Exception) {
                // A key that does not parse is a deployment that cannot accept an introduction, and
                // it says so through `configured` rather than throwing on the first visitor.
                null
            }
        }
    }
}
