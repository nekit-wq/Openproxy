package com.openproxy.client.core.singbox

import com.openproxy.client.core.model.AppSettings
import com.openproxy.client.core.model.Protocol
import com.openproxy.client.core.model.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigBuilderTest {

    @Test
    fun buildsRealityConfigWithTunAndClashApi() {
        val profile = ServerProfile(
            name = "Reality",
            protocol = Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            flow = "xtls-rprx-vision",
            security = "reality",
            sni = "www.google.com",
            publicKey = "PUBLIC_KEY",
            shortId = "abcd",
            fingerprint = "chrome",
            network = "tcp",
        )
        val json = SingBoxConfigBuilder.build(profile, AppSettings(), clashPort = 19090, clashSecret = "secret")
        assertTrue(json.contains("\"type\":\"tun\""))
        assertTrue(json.contains("\"type\":\"vless\""))
        assertTrue(json.contains("\"reality\""))
        assertTrue(json.contains("\"public_key\":\"PUBLIC_KEY\""))
        assertTrue(json.contains("\"flow\":\"xtls-rprx-vision\""))
        assertTrue(json.contains("\"clash_api\""))
        assertTrue(json.contains("127.0.0.1:19090"))
    }

    @Test
    fun buildsHysteria2AndWireGuard() {
        val hysteria = ServerProfile(
            protocol = Protocol.HYSTERIA2,
            address = "hy.example.com",
            port = 443,
            password = "pass",
            security = "tls",
            sni = "hy.example.com",
            obfs = "salamander",
            obfsPassword = "obfs",
        )
        val hysteriaJson = SingBoxConfigBuilder.build(hysteria, AppSettings())
        assertTrue(hysteriaJson.contains("\"type\":\"hysteria2\""))
        assertTrue(hysteriaJson.contains("\"salamander\""))

        val wireGuard = ServerProfile(
            protocol = Protocol.WIREGUARD,
            address = "wg.example.com",
            port = 51820,
            privateKey = "PRIVATE",
            peerPublicKey = "PUBLIC",
            localAddress = "10.0.0.2/32",
        )
        val wireGuardJson = SingBoxConfigBuilder.build(wireGuard, AppSettings())
        assertTrue(wireGuardJson.contains("\"type\":\"wireguard\""))
        assertTrue(wireGuardJson.contains("\"private_key\":\"PRIVATE\""))
        assertTrue(wireGuardJson.contains("\"peers\""))
    }

    @Test
    fun parsesCustomRoutingRules() {
        val rules = SingBoxConfigBuilder.parseCustomRules(
            """
            domain:example.com=direct
            ip:10.0.0.0/8=direct
            port:25=block
            # комментарий
            nonsense line
            """.trimIndent(),
        )
        assertEquals(3, rules.size)
        assertTrue(rules[0].toString().contains("example.com"))
        assertTrue(rules[2].toString().contains("\"action\":\"reject\""))
    }
}
