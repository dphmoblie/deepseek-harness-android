package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class RuntimeValidationTest {
    @Test
    fun acceptsEmptySourceForBundledRuntime() {
        val source = RuntimeValidation.source("", "")
        assertEquals(true, source.isBundled)
        assertEquals(null, source.manifestUrl)
        assertEquals(null, source.manifestSha256)
    }

    @Test
    fun normalizesDigestAndHttpsSource() {
        val source = RuntimeValidation.source(
            "https://downloads.example.invalid/runtime.json",
            "A".repeat(64),
        )
        assertEquals("a".repeat(64), source.manifestSha256)
        assertEquals("https", source.manifestUrl?.scheme)
    }

    @Test
    fun rejectsCredentialsFragmentsAndPrivateLiteralHosts() {
        listOf(
            "https://user@example.invalid/runtime.json",
            "https://example.invalid/runtime.json#fragment",
            "https://127.0.0.1/runtime.json",
            "https://${ipv4Literal(192, 168, 1, 1)}/runtime.json",
            "https://localhost/runtime.json",
        ).forEach { value ->
            assertThrows(RuntimeFailure::class.java) {
                RuntimeValidation.source(value, "a".repeat(64))
            }
        }
    }

    @Test
    fun rejectsInvalidSettingsBounds() {
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.settings(
                "https://downloads.example.invalid/runtime.json",
                "a".repeat(64),
                keepScreenAwake = false,
                terminalFontSize = 25,
            )
        }
    }

    @Test
    fun rejectsPartiallyConfiguredSource() {
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.source("https://downloads.example.invalid/runtime.json", "")
        }
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.source("", "a".repeat(64))
        }
    }

    @Test
    fun acceptsKnownArchiveCompressionFormats() {
        assertEquals(RootfsCompression.GZIP, RootfsCompression.parse("gzip"))
        assertThrows(RuntimeFailure::class.java) {
            RootfsCompression.parse("xz")
        }
    }

    @Test
    fun rejectsEveryNonPublicAddressClass() {
        listOf(
            ipv4Address(0, 0, 0, 0),
            ipv4Address(10, 0, 0, 1),
            ipv4Address(100, 64, 0, 1),
            ipv4Address(127, 0, 0, 1),
            ipv4Address(169, 254, 1, 1),
            ipv4Address(172, 16, 0, 1),
            ipv4Address(192, 168, 1, 1),
            ipv4Address(198, 18, 0, 1),
            ipv4Address(224, 0, 0, 1),
            InetAddress.getByName("::"),
            InetAddress.getByName("::1"),
            InetAddress.getByName("fc00::1"),
            InetAddress.getByName("fd00::1"),
            InetAddress.getByName("fe80::1"),
            InetAddress.getByName("ff02::1"),
        ).forEach { literal ->
            assertThrows(RuntimeFailure::class.java) {
                RuntimeValidation.requirePublicAddresses(listOf(literal))
            }
        }
    }

    @Test
    fun acceptsPublicIpv4AndIpv6Addresses() {
        RuntimeValidation.requirePublicAddresses(
            listOf(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
            ),
        )
    }

    @Test
    fun rejectsMixedPublicAndPrivateDnsAnswer() {
        assertThrows(RuntimeFailure::class.java) {
            RuntimeValidation.requirePublicAddresses(
                listOf(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("127.0.0.1"),
                ),
            )
        }
    }

    private fun ipv4Literal(vararg octets: Int): String = octets.joinToString(".")

    private fun ipv4Address(vararg octets: Int): InetAddress = InetAddress.getByAddress(
        octets.map { it.toByte() }.toByteArray(),
    )
}
