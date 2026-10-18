package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.state.ServerState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnAddressTest {
    private var oldPort = 0

    @BeforeTest
    fun setUp() {
        oldPort = ServerState.serverPort
        ServerState.serverPort = 8080
        ServerState.ownHosts.add("192.168.29.182")
        ServerState.ownHosts.add("bridge.example.com")
    }

    @AfterTest
    fun tearDown() {
        ServerState.serverPort = oldPort
        ServerState.ownHosts.remove("192.168.29.182")
        ServerState.ownHosts.remove("bridge.example.com")
        ServerState.ownPorts.remove(12345)
    }

    @Test
    fun `the bridge on its own port is own`() {
        assertTrue(ServerState.isOwnUrl("http://192.168.29.182:8080/media/abc/file/video.mp4"))
    }

    @Test
    fun `another service on the same device is not the bridge`() {
        // a self-hosted add-on (the Telegram-backed server) next to the bridge: same host, other port
        assertFalse(ServerState.isOwnUrl("http://192.168.29.182:8088/token/api/download/payload/file.mkv"))
    }

    @Test
    fun `a domain without a port (reverse proxy) and a port clients used are own`() {
        assertTrue(ServerState.isOwnUrl("https://bridge.example.com/media/abc/file/video.mp4"))
        assertFalse(ServerState.isOwnUrl("http://192.168.29.182:12345/x"))
        ServerState.ownPorts.add(12345)
        assertTrue(ServerState.isOwnUrl("http://192.168.29.182:12345/x"))
    }

    @Test
    fun `other hosts are never own`() {
        assertFalse(ServerState.isOwnUrl("http://192.168.29.99:8080/x"))
        assertFalse(ServerState.isOwnUrl("not a url"))
    }
}
