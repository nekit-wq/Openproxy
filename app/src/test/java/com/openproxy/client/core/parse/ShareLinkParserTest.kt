package com.openproxy.client.core.parse

import com.openproxy.client.core.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShareLinkParserTest {

    @Test
    fun parsesVlessReality() {
        val link = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?security=reality&sni=www.google.com&fp=chrome&pbk=PUBLIC_KEY&sid=ab12cd" +
            "&flow=xtls-rprx-vision&type=tcp&headerType=none#My%20Reality"
        val profile = ShareLinkParser.parseLink(link)
        assertNotNull(profile)
        requireNotNull(profile)
        assertEquals(Protocol.VLESS, profile.protocol)
        assertEquals("example.com", profile.address)
        assertEquals(443, profile.port)
        assertEquals("reality", profile.security)
        assertEquals("PUBLIC_KEY", profile.publicKey)
        assertEquals("ab12cd", profile.shortId)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertEquals("My Reality", profile.name)
    }

    @Test
    fun parsesVmessBase64Json() {
        val json = """
            {"v":"2","ps":"VMess node","add":"203.0.113.5","port":"443",
             "id":"11111111-2222-3333-4444-555555555555","aid":"0","scy":"auto",
             "net":"ws","type":"none","host":"cdn.example.com","path":"/ws",
             "tls":"tls","sni":"cdn.example.com"}
        """.trimIndent()
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val profile = ShareLinkParser.parseLink(link)
        assertNotNull(profile)
        requireNotNull(profile)
        assertEquals(Protocol.VMESS, profile.protocol)
        assertEquals("203.0.113.5", profile.address)
        assertEquals(443, profile.port)
        assertEquals("ws", profile.network)
        assertEquals("tls", profile.security)
        assertEquals("/ws", profile.path)
        assertEquals("VMess node", profile.name)
    }

    @Test
    fun parsesShadowsocksSip002() {
        val userInfo = Base64.getEncoder()
            .encodeToString("aes-256-gcm:secret".toByteArray())
            .trimEnd('=')
        val profile = ShareLinkParser.parseLink("ss://$userInfo@198.51.100.7:8388#SS")
        assertNotNull(profile)
        requireNotNull(profile)
        assertEquals(Protocol.SHADOWSOCKS, profile.protocol)
        assertEquals("aes-256-gcm", profile.method)
        assertEquals("secret", profile.password)
        assertEquals(8388, profile.port)
    }

    @Test
    fun parsesSubscriptionMeta() {
        val meta = SubscriptionParser.parseMeta(
            mapOf(
                "subscription-userinfo" to "upload=1000; download=2000; total=10000; expire=1893456000",
                "profile-title" to "base64:" + Base64.getEncoder().encodeToString("Panel".toByteArray()),
                "profile-update-interval" to "6",
            ),
        )
        assertEquals("Panel", meta.name)
        assertEquals(1000L, meta.uploadBytes)
        assertEquals(2000L, meta.downloadBytes)
        assertEquals(10000L, meta.totalBytes)
        assertEquals(1893456000L, meta.expiresAtSeconds)
        assertEquals(6, meta.updateIntervalHours)
    }

    @Test
    fun parsesWireGuardConfig() {
        val config = """
            [Interface]
            PrivateKey = PRIVATE_KEY
            Address = 10.7.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            H1 = 12345

            [Peer]
            PublicKey = PEER_KEY
            PresharedKey = PSK
            Endpoint = wg.example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
        """.trimIndent()
        val profile = WireGuardConfigParser.parse(config)
        assertNotNull(profile)
        requireNotNull(profile)
        assertEquals(Protocol.AMNEZIA_WG, profile.protocol)
        assertEquals("wg.example.com", profile.address)
        assertEquals(51820, profile.port)
        assertEquals("10.7.0.2/32", profile.localAddress)
        assertEquals("4", profile.awg["jc"])
        assertTrue(profile.awg.containsKey("h1"))
    }

    @Test
    fun rejectsAppNotSupportedDummyResponse() {
        val dummyText = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:443?security=none#App%20not%20supported"
        val batch = SubscriptionParser.parseBody(dummyText)
        assertTrue(batch.isEmpty)
        assertTrue(batch.errors.any { it.contains("App not supported") })
    }

    @Test
    fun rejectsPlainTextAppNotSupported() {
        val plainText = "App not supported"
        val batch = SubscriptionParser.parseBody(plainText)
        assertTrue(batch.isEmpty)
        assertTrue(batch.errors.any { it.contains("App not supported") })
    }
}
