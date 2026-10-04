package com.apiguanli.app

import com.apiguanli.app.net.LoginResult
import com.apiguanli.app.net.NewApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Full username/password login against a real NewAPI. Skipped unless LOGIN_URL, LOGIN_USER and
 * LOGIN_PASS are set. Creates a personal access token on that account, so only point it at a test site.
 * LOGIN_TOTP_SECRET (base32) enables answering 2FA prompts.
 */
class LoginLiveTest {
    private val url = System.getenv("LOGIN_URL")
    private val user = System.getenv("LOGIN_USER")
    private val pass = System.getenv("LOGIN_PASS")
    private val totpSecret = System.getenv("LOGIN_TOTP_SECRET")

    @Test
    fun loginAndUseToken() = runBlocking {
        assumeTrue("live env not set", url != null && user != null && pass != null)
        val c = NewApiClient(url!!)
        var userId = when (val r = c.login(user!!, pass!!)) {
            is LoginResult.Success -> r.userId
            LoginResult.Need2FA -> {
                assumeTrue("2FA needed but no LOGIN_TOTP_SECRET", totpSecret != null)
                c.login2fa(Totp.code(totpSecret!!))
            }
        }
        println("logged in, user id = $userId")
        val token = try {
            c.generateAccessToken(pass)
        } catch (e: NewApiClient.NeedProofCode) {
            println("password not accepted for the proof, using a fresh 2FA code")
            Totp.waitForNextWindow()
            c.generateAccessToken(pass, Totp.code(totpSecret!!))
        }
        println("access token: ${token.take(8)}… (${token.length} chars)")

        val authed = NewApiClient(url, userId, token)
        val me = authed.self()
        println("self: ${me.username} quota=${me.quota} group=${me.group}")
        assertEquals(userId, me.id)
        val tokens = authed.tokens()
        println("tokens: ${tokens.total}")
        val logs = authed.logs(1, 5)
        println("logs: ${logs.total}")
        if (c.status().checkinEnabled) {
            println("checkin status: ${authed.checkinStatus()}")
            val r = runCatching { authed.checkin() }
            println("checkin: ${r.getOrNull() ?: r.exceptionOrNull()?.message}")
            println("checkin status after: ${authed.checkinStatus()}")
        }
        assertTrue(token.isNotBlank())
    }
}

/** Minimal RFC 6238 TOTP (SHA-1, 6 digits, 30 s) for tests. */
object Totp {
    fun code(base32: String, time: Long = System.currentTimeMillis() / 1000): String {
        val key = decode(base32)
        val counter = time / 30
        val msg = java.nio.ByteBuffer.allocate(8).putLong(counter).array()
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(msg)
        val o = h.last().toInt() and 0xf
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    fun waitForNextWindow() {
        val now = System.currentTimeMillis() / 1000
        Thread.sleep(((30 - now % 30) + 1) * 1000)
    }

    private fun decode(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in s.uppercase().filter { it != '=' && it != ' ' }) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
