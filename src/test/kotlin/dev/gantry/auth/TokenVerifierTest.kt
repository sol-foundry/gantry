package dev.gantry.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.gantry.gateway.Authorizer
import dev.gantry.gateway.parseGatewayConfig
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TokenVerifierTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
    private val otherKey: RSAKey = RSAKeyGenerator(2048).keyID("k2").generate()
    private val hsSecret = "a-very-long-shared-secret-of-at-least-32-bytes"

    private val firebaseUrl = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
    private val firebase = Authorizer(type = "firebase", projectId = "demo")

    private fun jwks(vararg keys: com.nimbusds.jose.jwk.JWK) = JWKSet(keys.map { it.toPublicJWK() }).toString()

    private fun at(seconds: Long) = Date.from(now.plusSeconds(seconds))

    private fun claims(
        configure: JWTClaimsSet.Builder.() -> Unit = {},
        issuer: String = "https://securetoken.google.com/demo",
        audience: String = "demo",
    ) = JWTClaimsSet
        .Builder()
        .issuer(issuer)
        .audience(audience)
        .subject("user-1")
        .issueTime(at(-10))
        .expirationTime(at(3600))
        .apply(configure)
        .build()

    private fun sign(
        claims: JWTClaimsSet,
        signingKey: RSAKey = key,
    ): String =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
            .apply { sign(RSASSASigner(signingKey)) }
            .serialize()

    private fun verifier(
        body: String,
        counter: IntArray = IntArray(1),
        clock: () -> Instant = { now },
    ) = TokenVerifier(fetch = {
        counter[0]++
        Fetched(body, null)
    }, clock = clock)

    private fun TokenVerifier.check(
        authorizer: Authorizer,
        token: String?,
        secrets: Map<String, String> = emptyMap(),
    ) = verify(authorizer, token) { secrets[it] }

    @Test
    fun `valid firebase token`() {
        val urls = mutableListOf<String>()
        val v =
            TokenVerifier(fetch = {
                urls += it
                Fetched(jwks(key), null)
            }, clock = { now })
        val verdict = v.check(firebase, sign(claims()))
        val valid = assertIs<Verdict.Valid>(verdict)
        assertEquals("user-1", valid.claims["sub"])
        assertEquals(now.epochSecond + 3600, valid.claims["exp"])
        assertEquals(listOf(firebaseUrl), urls)
    }

    @Test
    fun `bearer prefix is stripped case-insensitively`() {
        val v = verifier(jwks(key))
        assertIs<Verdict.Valid>(v.check(firebase, "bearer " + sign(claims())))
        assertIs<Verdict.Valid>(v.check(firebase, "Bearer  " + sign(claims())))
    }

    @Test
    fun `firebase wrong audience and issuer name the claim`() {
        val v = verifier(jwks(key))
        val aud = assertIs<Verdict.Invalid>(v.check(firebase, sign(claims(audience = "other"))))
        assertTrue("aud" in aud.reason, aud.reason)
        val iss = assertIs<Verdict.Invalid>(v.check(firebase, sign(claims(issuer = "https://evil.example"))))
        assertTrue("iss" in iss.reason, iss.reason)
    }

    @Test
    fun `firebase requires a subject`() {
        val v = verifier(jwks(key))
        val verdict = v.check(firebase, sign(claims({ subject("") })))
        assertIs<Verdict.Invalid>(verdict)
        assertIs<Verdict.Invalid>(v.check(firebase, sign(claims({ subject(null) }))))
    }

    @Test
    fun `firebase auth_time in the future is rejected`() {
        val v = verifier(jwks(key))
        assertIs<Verdict.Invalid>(v.check(firebase, sign(claims({ claim("auth_time", at(3600)) }))))
        assertIs<Verdict.Valid>(v.check(firebase, sign(claims({ claim("auth_time", at(-5)) }))))
    }

    @Test
    fun `expired beyond skew is invalid and within skew is valid`() {
        val v = verifier(jwks(key))
        val expired = assertIs<Verdict.Invalid>(v.check(firebase, sign(claims({ expirationTime(at(-301)) }))))
        assertEquals("token expired", expired.reason)
        assertIs<Verdict.Valid>(v.check(firebase, sign(claims({ expirationTime(at(-299)) }))))
    }

    @Test
    fun `token not yet valid beyond skew is invalid`() {
        val v = verifier(jwks(key))
        assertIs<Verdict.Invalid>(v.check(firebase, sign(claims({ notBeforeTime(at(400)) }))))
    }

    @Test
    fun `token without exp is invalid`() {
        val v = verifier(jwks(key))
        assertIs<Verdict.Invalid>(v.check(firebase, sign(claims({ expirationTime(null) }))))
    }

    @Test
    fun `bad signature is invalid`() {
        val forged = RSAKeyGenerator(2048).keyID("k1").generate()
        val v = verifier(jwks(key))
        val verdict = assertIs<Verdict.Invalid>(v.check(firebase, sign(claims(), forged)))
        assertTrue("signature" in verdict.reason, verdict.reason)
    }

    @Test
    fun `alg none and garbage are invalid`() {
        val v = verifier(jwks(key))
        val payload =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(claims().toString().toByteArray())
        val none = "eyJhbGciOiJub25lIn0.$payload."
        assertIs<Verdict.Invalid>(v.check(firebase, none))
        assertIs<Verdict.Invalid>(v.check(firebase, "not-a-jwt"))
    }

    @Test
    fun `hs256 token is rejected for firebase`() {
        val hs =
            SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), claims())
                .apply { sign(MACSigner(hsSecret.toByteArray())) }
                .serialize()
        assertIs<Verdict.Invalid>(verifier(jwks(key)).check(firebase, hs))
    }

    @Test
    fun `cognito access token checks client_id`() {
        val cognito =
            Authorizer(
                type = "cognito",
                region = "eu-west-1",
                userPoolId = "eu-west-1_abc",
                clientIds = listOf("client-1"),
                tokenUse = "access",
            )
        val issuer = "https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_abc"
        val urls = mutableListOf<String>()
        val v =
            TokenVerifier(fetch = {
                urls += it
                Fetched(jwks(key), null)
            }, clock = { now })

        fun token(
            clientId: String,
            use: String = "access",
        ) = sign(claims({ claim("client_id", clientId).claim("token_use", use) }, issuer = issuer, audience = "ignored"))
        assertIs<Verdict.Valid>(v.check(cognito, token("client-1")))
        assertEquals(listOf("$issuer/.well-known/jwks.json"), urls)
        val wrongClient = assertIs<Verdict.Invalid>(v.check(cognito, token("client-2")))
        assertTrue("client_id" in wrongClient.reason, wrongClient.reason)
        val wrongUse = assertIs<Verdict.Invalid>(v.check(cognito, token("client-1", use = "id")))
        assertTrue("token_use" in wrongUse.reason, wrongUse.reason)
    }

    @Test
    fun `cognito id token checks aud`() {
        val cognito = Authorizer(type = "cognito", region = "eu-west-1", userPoolId = "p", clientIds = listOf("client-1"))
        val issuer = "https://cognito-idp.eu-west-1.amazonaws.com/p"
        val v = verifier(jwks(key))
        assertIs<Verdict.Valid>(v.check(cognito, sign(claims({ claim("token_use", "id") }, issuer, "client-1"))))
        assertIs<Verdict.Invalid>(v.check(cognito, sign(claims({ claim("token_use", "id") }, issuer, "client-9"))))
    }

    @Test
    fun `supabase hs256 secret`() {
        val supabase = Authorizer(type = "supabase", url = "https://abc.supabase.co/", jwtSecret = "{{secret:SB}}")
        val issuer = "https://abc.supabase.co/auth/v1"

        fun hs(secret: String) =
            SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims(issuer = issuer))
                .apply { sign(MACSigner(secret.toByteArray())) }
                .serialize()
        val counter = IntArray(1)
        val v = verifier("{}", counter)
        assertIs<Verdict.Valid>(v.check(supabase, hs(hsSecret), mapOf("SB" to hsSecret)))
        assertIs<Verdict.Invalid>(v.check(supabase, hs(hsSecret + "x"), mapOf("SB" to hsSecret)))
        val unset = assertIs<Verdict.Unavailable>(v.check(supabase, hs(hsSecret)))
        assertEquals("secret SB is not set", unset.reason)
        assertEquals(0, counter[0])
        val literal = supabase.copy(jwtSecret = hsSecret)
        assertIs<Verdict.Valid>(v.check(literal, hs(hsSecret)))
    }

    @Test
    fun `supabase jwks with rs256 and es256`() {
        val supabase = Authorizer(type = "supabase", url = "https://abc.supabase.co")
        val issuer = "https://abc.supabase.co/auth/v1"
        val ec: ECKey = ECKeyGenerator(Curve.P_256).keyID("ec1").generate()
        val urls = mutableListOf<String>()
        val v =
            TokenVerifier(fetch = {
                urls += it
                Fetched(jwks(key, ec), null)
            }, clock = { now })
        assertIs<Verdict.Valid>(v.check(supabase, sign(claims(issuer = issuer))))
        val es =
            SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("ec1").build(), claims(issuer = issuer))
                .apply { sign(ECDSASigner(ec)) }
                .serialize()
        assertIs<Verdict.Valid>(v.check(supabase, es))
        assertEquals(listOf("https://abc.supabase.co/auth/v1/.well-known/jwks.json"), urls.distinct())
    }

    @Test
    fun `jwt inline jwks with issuer and audiences`() {
        val inline =
            Authorizer(
                type = "jwt",
                jwks =
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(jwks(key)) as kotlinx.serialization.json.JsonObject,
                issuer = "https://issuer.example",
                audiences = listOf("api"),
            )
        val counter = IntArray(1)
        val v = verifier("unused", counter)
        assertIs<Verdict.Valid>(v.check(inline, sign(claims(issuer = "https://issuer.example", audience = "api"))))
        assertIs<Verdict.Invalid>(v.check(inline, sign(claims(issuer = "https://issuer.example", audience = "web"))))
        assertIs<Verdict.Invalid>(v.check(inline, sign(claims(issuer = "https://other.example", audience = "api"))))
        assertEquals(0, counter[0])
    }

    @Test
    fun `jwt algorithms list is enforced`() {
        val inline = Authorizer(type = "jwt", jwksUrl = "https://idp.example/jwks", algorithms = listOf("ES256"))
        assertIs<Verdict.Invalid>(verifier(jwks(key)).check(inline, sign(claims())))
    }

    @Test
    fun `inline jwks parsed from gateway yaml verifies a real token`() {
        val jwk = key.toPublicJWK().toJSONObject()
        val yaml =
            """
            authorizers:
              inline:
                type: jwt
                jwks:
                  keys:
                    - kty: "${jwk["kty"]}"
                      kid: "${jwk["kid"]}"
                      n: "${jwk["n"]}"
                      e: "${jwk["e"]}"
            """.trimIndent()
        val authorizer = parseGatewayConfig(yaml).authorizers.getValue("inline")
        val v = verifier("unused")
        assertIs<Verdict.Valid>(v.check(authorizer, sign(claims())))
    }

    @Test
    fun `unknown kid refetches at most once per thirty seconds`() {
        var current = now
        val counter = IntArray(1)
        var body = jwks(key)
        val v =
            TokenVerifier(fetch = {
                counter[0]++
                Fetched(body, null)
            }, clock = { current })
        assertIs<Verdict.Valid>(v.check(firebase, sign(claims())))
        assertEquals(1, counter[0])

        // key rotation: a token signed by k2 arrives; the set is too fresh to refetch
        val rotated = sign(claims(), otherKey)
        val early = assertIs<Verdict.Invalid>(v.check(firebase, rotated))
        assertEquals("unknown signing key", early.reason)
        assertEquals(1, counter[0])

        current = now.plusSeconds(31)
        body = jwks(key, otherKey)
        assertIs<Verdict.Valid>(v.check(firebase, sign(claims({ expirationTime(Date.from(current.plusSeconds(60))) }), otherKey)))
        assertEquals(2, counter[0])

        val unknown = RSAKeyGenerator(2048).keyID("k3").generate()
        val third = sign(claims({ expirationTime(Date.from(current.plusSeconds(60))) }), unknown)
        assertEquals("unknown signing key", assertIs<Verdict.Invalid>(v.check(firebase, third)).reason)
        assertEquals("unknown signing key", assertIs<Verdict.Invalid>(v.check(firebase, third)).reason)
        assertEquals(2, counter[0])
    }

    @Test
    fun `jwks is cached per url until max-age`() {
        var current = now
        val counter = IntArray(1)
        val v =
            TokenVerifier(fetch = {
                counter[0]++
                Fetched(jwks(key), 120)
            }, clock = { current })
        val token = sign(claims({ expirationTime(Date.from(now.plusSeconds(100000))) }))
        assertIs<Verdict.Valid>(v.check(firebase, token))
        current = now.plusSeconds(119)
        assertIs<Verdict.Valid>(v.check(firebase, token))
        assertEquals(1, counter[0])
        current = now.plusSeconds(121)
        assertIs<Verdict.Valid>(v.check(firebase, token))
        assertEquals(2, counter[0])
    }

    @Test
    fun `unreachable jwks fails closed`() {
        val v = TokenVerifier(fetch = { error("connection refused") }, clock = { now })
        val verdict = assertIs<Verdict.Unavailable>(v.check(firebase, sign(claims())))
        assertTrue("connection refused" in verdict.reason, verdict.reason)
    }

    @Test
    fun `missing token is invalid`() {
        val v = verifier(jwks(key))
        assertIs<Verdict.Invalid>(v.check(firebase, null))
        assertIs<Verdict.Invalid>(v.check(firebase, "  "))
        assertIs<Verdict.Invalid>(v.check(firebase, "Bearer "))
    }

    @Test
    fun `claims are plain values`() {
        val v = verifier(jwks(key))
        val valid =
            assertIs<Verdict.Valid>(
                v.check(
                    firebase,
                    sign(claims({ claim("firebase", mapOf("sign_in_provider" to "password")).claim("email_verified", true) })),
                ),
            )
        assertEquals(true, valid.claims["email_verified"])
        assertEquals(listOf("demo"), valid.claims["aud"])
        assertEquals(mapOf("sign_in_provider" to "password"), valid.claims["firebase"])
        assertEquals(now.epochSecond - 10, valid.claims["iat"])
    }

    private fun hsToken(
        secret: String,
        issuer: String,
    ) = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims(issuer = issuer))
        .apply { sign(MACSigner(secret.toByteArray())) }
        .serialize()

    @Test
    fun `secret references with hyphens and dots resolve through the callback`() {
        val issuer = "https://abc.supabase.co/auth/v1"
        val v = verifier("{}")
        for (name in listOf("supabase-jwt-secret", "jwt.secret")) {
            val a = Authorizer(type = "supabase", url = "https://abc.supabase.co", jwtSecret = "{{secret:$name}}")
            assertIs<Verdict.Valid>(v.check(a, hsToken(hsSecret, issuer), mapOf(name to hsSecret)))
        }
    }

    @Test
    fun `malformed secret reference is unavailable and never used as a key`() {
        val issuer = "https://abc.supabase.co/auth/v1"
        val v = verifier("{}")
        for (ref in listOf("{{secret:}}", "{{secret:a b}}", "prefix-{{secret:A}}-suffix-padding-padding")) {
            val a = Authorizer(type = "supabase", url = "https://abc.supabase.co", jwtSecret = ref)
            assertIs<Verdict.Unavailable>(v.check(a, hsToken(ref.padEnd(40, 'x'), issuer), mapOf("A" to hsSecret)), ref)
            assertIs<Verdict.Unavailable>(v.check(a, hsToken(ref.padEnd(40, 'x'), issuer)), ref)
        }
    }

    @Test
    fun `inline jwks without keys is unavailable`() {
        val a = Authorizer(type = "jwt", jwks = kotlinx.serialization.json.JsonObject(emptyMap()))
        assertIs<Verdict.Unavailable>(verifier("x").check(a, sign(claims())))
    }

    @Test
    fun `secret callback that throws is unavailable`() {
        val a = Authorizer(type = "supabase", url = "https://abc.supabase.co", jwtSecret = "{{secret:A}}")
        val verdict = verifier("{}").verify(a, hsToken(hsSecret, "https://abc.supabase.co/auth/v1")) { error("vault down") }
        assertIs<Verdict.Unavailable>(verdict)
    }

    @Test
    fun `a failing jwks endpoint is hit at most once per thirty seconds`() {
        var current = now
        var calls = 0
        var failing = false
        val v =
            TokenVerifier(fetch = {
                calls++
                if (failing) error("down")
                Fetched(jwks(key), null)
            }, clock = { current })
        assertIs<Verdict.Valid>(v.check(firebase, sign(claims())))
        failing = true
        current = now.plusSeconds(31)
        val unknown = sign(claims({ expirationTime(Date.from(current.plusSeconds(60))) }), otherKey)
        repeat(10) { assertIs<Verdict.Unavailable>(v.check(firebase, unknown)) }
        assertEquals(2, calls)
        current = now.plusSeconds(62)
        assertIs<Verdict.Unavailable>(v.check(firebase, unknown))
        assertEquals(3, calls)
    }

    @Test
    fun `cognito without client ids is unavailable`() {
        val cognito = Authorizer(type = "cognito", region = "eu-west-1", userPoolId = "p")
        val issuer = "https://cognito-idp.eu-west-1.amazonaws.com/p"
        val verdict = verifier(jwks(key)).check(cognito, sign(claims({ claim("token_use", "id") }, issuer, "x")))
        assertIs<Verdict.Unavailable>(verdict)
    }
}
