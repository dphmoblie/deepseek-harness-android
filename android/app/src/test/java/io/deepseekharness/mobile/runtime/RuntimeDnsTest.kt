package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetAddress

class RuntimeDnsTest {
    @Test
    fun formatsValidatedSystemDnsAddresses() {
        val config = RuntimeDns.formatConfig(
            listOf(
                InetAddress.getByName("192.0.2.53"),
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("192.0.2.53"),
            ),
        )
        assertEquals(
            "nameserver 192.0.2.53\n" +
                "nameserver 2606:4700:4700:0:0:0:0:1111\n" +
                "options timeout:2 attempts:3\n",
            config,
        )
    }

    @Test
    fun excludesLoopbackAndFallsBackToPublicResolvers() {
        val config = RuntimeDns.formatConfig(
            listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")),
        )
        assertEquals(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:3\n",
            config,
        )
        assertFalse(config.contains("127.0.0.1"))
    }
}
