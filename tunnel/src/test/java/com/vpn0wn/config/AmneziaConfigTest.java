/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.config;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.Assert.*;

public class AmneziaConfigTest {
    private static final String PRIVATE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PUBLIC = "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private Config parse(final String attributes, final String peer) throws Exception {
        return Config.parse(new BufferedReader(new StringReader("[Interface]\nPrivateKey = " + PRIVATE
                + "\n" + attributes + "\n[Peer]\nPublicKey = " + PUBLIC + "\n" + peer)));
    }

    @Test public void allAdmittedFamiliesRetainValuesAndExplicitZero() throws Exception {
        final String fields = "Protocol = AmneziaWG\nJc = 2\nJmin = 16\nJmax = 32\n"
                + "I1 = <b 0x0102><t><r 8><rc 2><rd 3><d><ds><dz 2>\nI2 = <b 00>\nI3 = <r 0>\nI4 = <t>\nI5 = <b ff>\n"
                + "S1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\nH1 = 0\nH2 = 10-20\nH3 = 30\nH4 = 40\n"
                + "HeaderProtectionKey = " + PUBLIC + "\nContentPaddingAddition = 1-10\nRekeyAfterTime = 60-120\n"
                + "RekeyTimeout = 5\nRejectAfterTime = 180\nKeepaliveTimeout = 10\nMaxHandshakeAttempts = 18\n"
                + "RandomTrailers = false\nDisableCookies = 0";
        final Config original = parse(fields, "PersistentKeepalive = 20-30");
        final Config reloaded = Config.parse(new BufferedReader(new StringReader(original.toWgQuickString())));
        assertEquals(original, reloaded);
        assertEquals(original.hashCode(), reloaded.hashCode());
        assertTrue(original.getInterface().getAmneziaSettings().isPresent());
        assertEquals("20-30", original.getPeers().get(0).getAmneziaKeepalive().get());
        assertTrue(original.toWgUserspaceString().contains("h1=0\n"));
        assertTrue(original.toWgUserspaceString().contains("disable_cookies=0\n"));
        assertTrue(original.toWgUserspaceString().contains("persistent_keepalive_interval=20-30\n"));
        assertFalse(original.toWgUserspaceString().contains("Protocol"));
        assertFalse(original.getInterface().getAmneziaSettings().get().toString().contains(PUBLIC));
    }

    @Test public void emptyAmneziaIntentSurvivesRoundTrip() throws Exception {
        final Config config = parse("Protocol = AmneziaWG", "PersistentKeepalive = 0");
        final Config restored = Config.parse(new BufferedReader(new StringReader(config.toWgQuickString())));
        assertEquals(config, restored);
        assertTrue(restored.toWgQuickString().contains("# vpn0wn:protocol=AmneziaWG\n"));
        assertTrue(restored.toWgQuickString().contains("PersistentKeepalive = 0\n"));
    }

    @Test public void importedAmneziaFieldsEstablishExplicitSavedIntent() throws Exception {
        assertTrue(parse("Jc = 0", "").toWgQuickString().contains("# vpn0wn:protocol=AmneziaWG\n"));
    }

    @Test public void emptyPacketAttributesRoundTripWithoutRelaxingWireGuardValues() throws Exception {
        final Config original = parse("I1 =\ni2= \nI3 =\nI4 =\nI5 =", "");
        assertEquals(5, original.getInterface().getAmneziaSettings().get().getValues().size());
        for (final String value : original.getInterface().getAmneziaSettings().get().getValues().values())
            assertEquals("", value);
        assertTrue(original.toWgUserspaceString().contains("i1=\n"));
        assertEquals(original, Config.parse(new BufferedReader(new StringReader(original.toWgQuickString()))));
        for (final String empty : new String[]{"PrivateKey =", "Address =", "DNS =", "ListenPort =", "MTU =", "Jc =", "I6 ="}) {
            try {
                parse(empty, "");
                fail("Accepted unsupported empty attribute: " + empty);
            } catch (final BadConfigException expected) {
                assertEquals(BadConfigException.Reason.SYNTAX_ERROR, expected.getReason());
            }
        }
    }

    @Test public void ordinaryWireGuardKeepsItsSerialization() throws Exception {
        final Config original = parse("ListenPort = 0", "PersistentKeepalive = 25");
        assertFalse(original.getInterface().getAmneziaSettings().isPresent());
        assertFalse(original.toWgQuickString().contains("Protocol"));
        assertFalse(original.toWgQuickString().contains("ListenPort"));
        assertEquals(Integer.valueOf(25), original.getPeers().get(0).getPersistentKeepalive().get());
        assertEquals(original, Config.parse(new BufferedReader(new StringReader(original.toWgQuickString()))));
    }

    @Test public void malformedAndUnsafeValuesFailBeforeActivation() throws Exception {
        final String[] invalid = {
                "Protocol = WireGuard\nJc = 1", "Protocol = Other", "Protocol = AmneziaWG\nProtocol = WireGuard",
                "Jc = 129", "Jc = 128\nJmax = 65507", "Jc = 1\nJc = 2", "Jmin = 50\nJmax = 49",
                "Jmax = 65508", "S1 = 65400", "S4 = 65000", "S1 = 1-2", "H1 = 2", "H1 = 10-9",
                "H1 = 4294967296", "H1 = 0-4294967295", "RandomTrailers = yes", "DisableCookies = nonsense",
                "HeaderProtectionKey = " + PUBLIC, "HeaderProtectionKey = private-value-must-not-leak",
                "I1 = prefix<b 00>", "I1 = <r -1>", "I1 = <r 65508>", "I1 = <t extra>", "I1 = <b 0>",
                "I1 = <r 18446744073709551616>", "I1 = <r 1><r 9223372036854775807>", "I1 = <b ff>trailer", "I1 = <d ignored>", "I1 = <unknown>",
                "RejectAfterTime = 4294967295", "MaxHandshakeAttempts = 4294967295", "ContentPaddingAddition = 2147483648", "S5 = 1"
        };
        for (final String input : invalid) {
            try {
                parse(input, "");
                fail("Accepted invalid attribute: " + input.split("=")[0]);
            } catch (final BadConfigException expected) {
                assertFalse(String.valueOf(expected.getText()).contains("private-value-must-not-leak"));
            }
        }
    }

    @Test(expected = BadConfigException.class)
    public void ordinaryKeepaliveRejectsRanges() throws Exception {
        parse("", "PersistentKeepalive = 20-30");
    }

    @Test public void interfaceAfterPeerStillSelectsAmneziaKeepalive() throws Exception {
        final String text = "[Peer]\nPublicKey = " + PUBLIC + "\nPersistentKeepalive = 1-2\n"
                + "[Interface]\nPrivateKey = " + PRIVATE + "\nProtocol = AmneziaWG\n";
        final Config config = Config.parse(new BufferedReader(new StringReader(text)));
        assertEquals("1-2", config.getPeers().get(0).getAmneziaKeepalive().get());
    }
}
