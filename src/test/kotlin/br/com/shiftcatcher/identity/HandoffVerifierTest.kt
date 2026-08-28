package br.com.shiftcatcher.identity

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The door Clara Care opens, tested against the ways somebody would try to open it without a key.
 *
 * AUTODEC-0012 decision 2. The format carries no algorithm field, so the whole JWT confusion family
 * — `alg: none`, an RS256 token verified as HS256 with the public key as the secret — has no place
 * to live. What is left to test is that every other lie is caught: a changed payload, another
 * product's audience, an issuer nobody trusts, a minute that already passed, and the shape of a JWT
 * itself arriving at a verifier that must never look inside one.
 */
class HandoffVerifierTest {
    private val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val otherKeys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val now: Instant = Instant.parse("2026-08-27T12:00:00Z")

    private fun verifier(
        publicKey: java.security.PublicKey = keys.public,
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
    ) = HandoffVerifier(
        publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded),
        expectedIssuer = issuer,
        expectedAudience = audience,
        clockSkew = Duration.ofSeconds(15),
    )

    /** What Clara Care will do, written here first so the verifier is tested against a real signer. */
    private fun mint(
        subject: String = "9f1c0f7a-6c1e-4a2a-9a1a-2b3c4d5e6f70",
        name: String = "Dra. Godinho",
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
        jti: String = "01J8Z4Q2K7",
        expiresAt: Instant = now.plusSeconds(60),
        signWith: PrivateKey = keys.private,
    ): String {
        val payload =
            """{"sub":"$subject","name":"$name","iss":"$issuer","aud":"$audience","jti":"$jti","exp":${expiresAt.epochSecond}}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(signWith)
        signer.update(encoded.toByteArray(StandardCharsets.US_ASCII))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        return "$encoded.$signature"
    }

    @Test
    fun `accepts an introduction Clara Care actually wrote`() {
        val result = verifier().verify(mint(), now)

        val accepted = assertIs<HandoffResult.Accepted>(result)
        assertEquals("9f1c0f7a-6c1e-4a2a-9a1a-2b3c4d5e6f70", accepted.assertion.subject)
        assertEquals("Dra. Godinho", accepted.assertion.displayName)
        assertEquals("01J8Z4Q2K7", accepted.assertion.id)
    }

    @Test
    fun `refuses a payload that was changed after it was signed`() {
        // The subject is the only field worth forging: it is who you become.
        val token = mint()
        val forged =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"sub":"somebody-else","name":"x","iss":"$ISSUER","aud":"$AUDIENCE","jti":"j","exp":${now.plusSeconds(60).epochSecond}}"""
                    .toByteArray(StandardCharsets.UTF_8),
            ) + "." + token.substringAfter('.')

        assertEquals(
            HandoffFailure.BadSignature,
            assertIs<HandoffResult.Refused>(verifier().verify(forged, now)).failure,
        )
    }

    @Test
    fun `refuses a signature from a key it was not given`() {
        assertEquals(
            HandoffFailure.BadSignature,
            assertIs<HandoffResult.Refused>(verifier().verify(mint(signWith = otherKeys.private), now)).failure,
        )
    }

    @Test
    fun `refuses an assertion minted for another product`() {
        // Audience is what stops one signing key from becoming a key to every door it ever opened.
        assertEquals(
            HandoffFailure.WrongAudience,
            assertIs<HandoffResult.Refused>(verifier().verify(mint(audience = "portal"), now)).failure,
        )
    }

    @Test
    fun `refuses an issuer it does not trust`() {
        assertEquals(
            HandoffFailure.WrongIssuer,
            assertIs<HandoffResult.Refused>(verifier().verify(mint(issuer = "https://evil.test"), now)).failure,
        )
    }

    @Test
    fun `refuses a minute that already passed, and allows only the stated skew`() {
        val expired = mint(expiresAt = now.minusSeconds(30))
        assertEquals(
            HandoffFailure.Expired,
            assertIs<HandoffResult.Refused>(verifier().verify(expired, now)).failure,
        )

        // Ten seconds past, inside the fifteen the deployment allows for two clocks disagreeing.
        val barelyPast = mint(expiresAt = now.minusSeconds(10))
        assertIs<HandoffResult.Accepted>(verifier().verify(barelyPast, now))
    }

    @Test
    fun `refuses anything shaped like a JWT`() {
        // Three parts means somebody is presenting a token with a header, and a verifier that reads
        // a header at all is a verifier that can be talked into `alg: none`. There is no branch
        // here that could reach one.
        val jwtish =
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray()) +
                "." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"x"}""".toByteArray()) +
                "."

        assertEquals(
            HandoffFailure.Malformed,
            assertIs<HandoffResult.Refused>(verifier().verify(jwtish, now)).failure,
        )
    }

    @Test
    fun `refuses rubbish without reading it`() {
        for (nonsense in listOf("", ".", "abc", "abc.", ".abc", "not-base64!.also-not!")) {
            assertIs<HandoffResult.Refused>(verifier().verify(nonsense, now))
        }
    }

    @Test
    fun `a deployment with no key accepts nothing and says so`() {
        // Blank key is the default, and the right one: a deployment nobody gave a key to has not
        // been told who is allowed to vouch for anybody.
        val shut =
            HandoffVerifier(
                publicKeyBase64 = "",
                expectedIssuer = ISSUER,
                expectedAudience = AUDIENCE,
                clockSkew = Duration.ofSeconds(15),
            )

        assertFalse(shut.configured)
        assertEquals(
            HandoffFailure.NotConfigured,
            assertIs<HandoffResult.Refused>(shut.verify(mint(), now)).failure,
        )
        assertTrue(verifier().configured)
    }

    private companion object {
        const val ISSUER = "https://claracare.com.br"
        const val AUDIENCE = "plantoes"
    }
}
