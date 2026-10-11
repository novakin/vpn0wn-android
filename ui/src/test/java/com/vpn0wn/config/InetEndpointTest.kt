package com.vpn0wn.config

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(manifest = RobolectricConfig.NONE, sdk = [28, 36], shadows = [InetEndpointTest.NumericAddressShadow::class])
class InetEndpointTest {
    @After fun resetNumericParser() {
        NumericAddressShadow.unavailableAddress = null
    }

    @Test fun bracketedNumericIpv6ExposesBareHostAndPreservesSerialization() {
        for (host in listOf("::1", "2001:db8:0:0:0:0:0:1", "fe80::1%1")) {
            val endpoint = InetEndpoint.parse("[$host]:51891")
            assertEquals(host, endpoint.host)
            assertEquals("[$host]:51891", endpoint.toString())
            assertSame(endpoint, endpoint.getResolved().get())
        }
    }

    @Test fun numericIpv4RetainsHostPortAndDirectResolution() {
        val endpoint = InetEndpoint.parse("127.0.0.1:51891")
        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(51891, endpoint.port)
        assertEquals("127.0.0.1:51891", endpoint.toString())
        assertSame(endpoint, endpoint.getResolved().get())
    }

    @Test fun dnsEndpointParsingRetainsOriginalHostWithoutResolvingIt() {
        val endpoint = InetEndpoint.parse("Vpn.Example.invalid.:51891")
        assertEquals("Vpn.Example.invalid.", endpoint.host)
        assertEquals(51891, endpoint.port)
        assertEquals("Vpn.Example.invalid.:51891", endpoint.toString())
    }

    @Test
    @RobolectricConfig(sdk = [36])
    fun rejectedScopedNumericCandidateKeepsOriginalUnresolvedSerialization() {
        for (host in listOf("fe80::1%99", "fe80::1%25lo")) {
            NumericAddressShadow.unavailableAddress = host
            val endpoint = InetEndpoint.parse("[$host]:51891")
            assertEquals("[$host]", endpoint.host)
            assertEquals("[$host]:51891", endpoint.toString())
        }
    }

    @Test fun parserKeepsBothPortBoundaries() {
        for (port in listOf(0, 65535)) {
            for (host in listOf("127.0.0.1", "[::1]", "vpn.example.invalid")) {
                val endpoint = InetEndpoint.parse("$host:$port")
                assertEquals(port, endpoint.port)
                assertEquals("$host:$port", endpoint.toString())
            }
        }
    }

    @Test fun malformedEndpointFailuresKeepTheirInputAndParsingOwner() {
        for (input in listOf("[::1]", "::1:51891", "127.0.0.1:-1", "127.0.0.1:65536",
                "host:51891/path", "host:51891?query", "host:51891#fragment", "[::gg]:51891")) {
            val failure = assertThrows(ParseException::class.java) { InetEndpoint.parse(input) }
            assertEquals(InetEndpoint::class.java, failure.parsingClass)
            assertEquals(input, failure.text)
        }
    }

    @Test fun configAndUserspaceEndpointRoundTripsKeepBracketedIpv6() {
        val profile = Config.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Endpoint = [2001:db8::1]:51891
        """.trimIndent().byteInputStream())
        val serialized = profile.toWgQuickString()
        val imported = Config.parse(serialized.byteInputStream())
        assertEquals("2001:db8::1", imported.peers.single().endpoint.get().host)
        assertEquals(serialized, imported.toWgQuickString())
        assertEquals("[2001:db8::1]:51891", imported.peers.single().endpoint.get().toString())
        assertTrue(imported.toWgUserspaceString().contains("endpoint=[2001:db8::1]:51891\n"))
    }

    // Robolectric's JDK parser accepts brackets that Android's numeric-only parser rejects.
    @Implements(className = "libcore.net.InetAddressUtils", isInAndroidSdk = false, minSdk = 29)
    // Robolectric instantiates this static API shadow through a public constructor.
    @Suppress("UtilityClassWithPublicConstructor")
    class NumericAddressShadow {
        companion object {
            var unavailableAddress: String? = null

            @JvmStatic
            @Implementation
            fun parseNumericAddressNoThrow(address: String): InetAddress? {
                if ('[' in address || ']' in address || address == unavailableAddress)
                    return null
                if (':' !in address && address.any { !it.isDigit() && it != '.' })
                    return null
                return try {
                    InetAddress.getByName(address)
                } catch (_: UnknownHostException) {
                    null
                }
            }
        }
    }
}
