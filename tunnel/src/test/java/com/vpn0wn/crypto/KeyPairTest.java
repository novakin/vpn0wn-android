package com.vpn0wn.crypto;

import org.junit.Test;

import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class KeyPairTest {
    private static final String PRIVATE_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String OTHER_PRIVATE_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    @Test
    public void equivalentPrivateKeyBytesGiveValueEqualityAndHashMembership() throws Exception {
        final KeyPair first = new KeyPair(Key.fromBase64(PRIVATE_KEY));
        final KeyPair second = new KeyPair(Key.fromBase64(PRIVATE_KEY));
        final KeyPair third = new KeyPair(Key.fromBytes(first.getPrivateKey().getBytes()));

        assertEquals(first, first);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(second, third);
        assertEquals(first, third);
        assertEquals(first.hashCode(), second.hashCode());
        final HashSet<KeyPair> pairs = new HashSet<>();
        pairs.add(first);
        assertTrue(pairs.contains(second));
        assertFalse(pairs.add(third));
    }

    @Test
    public void differentPrivateAndDerivedPublicKeysRemainUnequal() throws Exception {
        final KeyPair first = new KeyPair(Key.fromBase64(PRIVATE_KEY));
        final KeyPair other = new KeyPair(Key.fromBase64(OTHER_PRIVATE_KEY));

        assertNotEquals(first.getPublicKey(), other.getPublicKey());
        assertNotEquals(first, other);
        assertNotEquals(other, first);
        assertNotEquals(first, null);
        assertNotEquals(first, first.getPrivateKey());
    }

    @Test
    public void privateByteDifferencesRemainUnequalWhenClampingKeepsThePublicKey() throws Exception {
        final KeyPair first = new KeyPair(Key.fromBase64(PRIVATE_KEY));
        final byte[] changed = first.getPrivateKey().getBytes();
        changed[0] = 1;
        final KeyPair other = new KeyPair(Key.fromBytes(changed));

        assertEquals(first.getPublicKey(), other.getPublicKey());
        assertNotEquals(first, other);
    }
}
