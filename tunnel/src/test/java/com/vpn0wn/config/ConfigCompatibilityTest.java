/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.config;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConfigCompatibilityTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    public void independentlyParsedEquivalentProfilesHaveEqualValuesAndHashes() throws Exception {
        final Config first = profile("DNS = 192.0.2.53, corp.example\n");
        final Config second = parse(first.toWgQuickString());

        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.getInterface(), second.getInterface());
        assertEquals(first.getInterface().hashCode(), second.getInterface().hashCode());
    }

    @Test
    public void changedKeysAndMeaningfulProfileFieldsRemainUnequal() throws Exception {
        final Config original = profile("DNS = 192.0.2.53\n");
        final String serialized = original.toWgQuickString();
        final String otherKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

        assertNotEquals(original, parse(serialized.replace("PrivateKey = " + KEY, "PrivateKey = " + otherKey)));
        assertNotEquals(original, parse(serialized.replace("PublicKey = " + KEY, "PublicKey = " + otherKey)));
        assertNotEquals(original, profile("DNS = 192.0.2.54\n"));
        assertNotEquals(original, profile("DNS = 192.0.2.53\nListenPort = 51820\n"));
        assertNotEquals(original, profile("DNS = 192.0.2.53\nMTU = 1280\n"));
        assertNotEquals(original, parse(serialized.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 192.0.2.0/24")));
    }

    @Test
    public void negativeDirectAndParsedMtuUseTheMtuDiagnostic() {
        final BadConfigException direct = assertThrows(BadConfigException.class, () -> new Interface.Builder().setMtu(-1));
        final BadConfigException parsed = assertThrows(BadConfigException.class, () -> profile("MTU = -1\n"));

        for (final BadConfigException failure : Arrays.asList(direct, parsed)) {
            assertEquals(BadConfigException.Section.INTERFACE, failure.getSection());
            assertEquals(BadConfigException.Location.MTU, failure.getLocation());
            assertEquals(BadConfigException.Reason.INVALID_VALUE, failure.getReason());
            assertEquals("-1", failure.getText());
        }
    }

    @Test
    public void automaticAndPositiveMtuPreserveListenPortValidation() throws Exception {
        assertFalse(profile("MTU = 0\n").getInterface().getMtu().isPresent());
        assertEquals(Integer.valueOf(1280), profile("MTU = 1280\n").getInterface().getMtu().get());
        for (final int invalidPort : new int[]{-1, 65536}) {
            final BadConfigException failure = assertThrows(BadConfigException.class,
                    () -> new Interface.Builder().setListenPort(invalidPort));
            assertEquals(BadConfigException.Location.LISTEN_PORT, failure.getLocation());
            assertEquals(BadConfigException.Reason.INVALID_VALUE, failure.getReason());
        }
    }

    @Test
    public void domainOnlyDnsSurvivesRepeatedSaveExportRoundTrips() throws Exception {
        Config config = profile("DNS = example.test\nDNS = corp.example.test\n");
        for (int pass = 0; pass < 3; ++pass) {
            final String serialized = config.toWgQuickString();
            assertTrue(serialized.contains("DNS = example.test, corp.example.test\n"));
            config = parse(serialized);
            assertTrue(config.getInterface().getDnsServers().isEmpty());
            assertEquals(new LinkedHashSet<>(Arrays.asList("example.test", "corp.example.test")),
                    config.getInterface().getDnsSearchDomains());
            assertEquals(2, config.getPeers().size());
        }
    }

    @Test
    public void mixedIpv4Ipv6AndSearchDomainsRemainCompatible() throws Exception {
        final Config original = profile("DNS = 192.0.2.53, example.test\nDNS = 2001:db8::53, corp.example.test\n");
        final Config restored = parse(original.toWgQuickString());
        assertEquals(original.toWgQuickString(), restored.toWgQuickString());
        assertEquals(original.getPeers(), restored.getPeers());
        assertEquals(2, restored.getInterface().getDnsServers().size());
        assertEquals(2, restored.getInterface().getDnsSearchDomains().size());
    }

    @Test
    public void addressOnlyDnsAndEmptyDnsSerializationStayCompatible() throws Exception {
        final Config addressOnly = profile("DNS = 192.0.2.53, 2001:db8::53\n");
        assertEquals(addressOnly.toWgQuickString(), parse(addressOnly.toWgQuickString()).toWgQuickString());
        final Config noDns = profile("");
        assertFalse(noDns.toWgQuickString().contains("DNS ="));
        assertEquals(noDns.toWgQuickString(), parse(noDns.toWgQuickString()).toWgQuickString());
    }

    @Test
    public void omittedAndValidIpv4Ipv6PrefixesKeepTheirMasks() throws Exception {
        assertEquals(32, InetNetwork.parse("192.0.2.2").getMask());
        assertEquals(128, InetNetwork.parse("2001:db8::2").getMask());
        for (final int mask : new int[]{0, 24, 32})
            assertEquals(mask, InetNetwork.parse("192.0.2.2/" + mask).getMask());
        for (final int mask : new int[]{0, 64, 128})
            assertEquals(mask, InetNetwork.parse("2001:db8::2/" + mask).getMask());
    }

    @Test
    public void explicitNegativeAndOversizedPrefixesThrowTypedFailures() {
        for (final String network : Arrays.asList("192.0.2.2/-1", "192.0.2.2/-2", "192.0.2.2/33",
                "2001:db8::2/-1", "2001:db8::2/-2", "2001:db8::2/129")) {
            final ParseException failure = assertThrows(ParseException.class, () -> InetNetwork.parse(network));
            assertEquals(InetNetwork.class, failure.getParsingClass());
            assertEquals(network.substring(network.indexOf('/') + 1), failure.getText());
        }
    }

    @Test
    public void negativeInterfaceAndPeerPrefixesKeepConfigErrorLocations() {
        for (final String network : Arrays.asList("192.0.2.2/-1", "2001:db8::2/-1")) {
            final BadConfigException address = assertThrows(BadConfigException.class,
                    () -> parse("[Interface]\nPrivateKey = " + KEY + "\nAddress = " + network + "\n"));
            assertEquals(BadConfigException.Section.INTERFACE, address.getSection());
            assertEquals(BadConfigException.Location.ADDRESS, address.getLocation());
            assertTrue(address.getCause() instanceof ParseException);
            final BadConfigException allowed = assertThrows(BadConfigException.class,
                    () -> parse("[Interface]\nPrivateKey = " + KEY + "\n[Peer]\nPublicKey = " + KEY
                            + "\nAllowedIPs = " + network + "\n"));
            assertEquals(BadConfigException.Section.PEER, allowed.getSection());
            assertEquals(BadConfigException.Location.ALLOWED_IPS, allowed.getLocation());
            assertTrue(allowed.getCause() instanceof ParseException);
        }
    }

    private static Config profile(final String dns) throws Exception {
        return parse("[Interface]\nPrivateKey = " + KEY + "\nAddress = 192.0.2.2/32, 2001:db8::2/128\n" + dns
                + "[Peer]\nPublicKey = " + KEY + "\nAllowedIPs = 0.0.0.0/0\n"
                + "[Peer]\nPublicKey = " + KEY + "\nAllowedIPs = ::/0\nEndpoint = [2001:db8::1]:51820\n");
    }

    private static Config parse(final String text) throws Exception {
        return Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
