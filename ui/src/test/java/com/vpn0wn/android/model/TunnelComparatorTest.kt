/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.model

import com.vpn0wn.android.databinding.Keyed
import com.vpn0wn.android.databinding.ObservableSortedKeyedArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sign

class TunnelComparatorTest {
    @Test
    fun equivalentNaturalTokensRetainDistinctKeysAndSymmetricOrder() {
        val names = listOf("a", "A", "a1", "a01", "a 1", "a\t1")
        val tunnels = ObservableSortedKeyedArrayList<String, NamedTunnel>(TunnelComparator)
        names.forEach { tunnels.add(NamedTunnel(it)) }
        assertEquals(names.size, tunnels.size)
        names.forEach { assertTrue(tunnels.indexOfKey(it) >= 0) }
        for (left in names) {
            assertEquals(0, TunnelComparator.compare(left, left))
            for (right in names) {
                assertEquals(-TunnelComparator.compare(right, left).sign, TunnelComparator.compare(left, right).sign)
            }
        }
    }

    @Test
    fun numericAndPrefixOrderRemainsTransitive() {
        val names = listOf("a", "a1", "a2", "a10", "a10b", "b")
        assertEquals(names, names.reversed().sortedWith(TunnelComparator))
        for (left in names.indices) {
            for (right in left + 1 until names.size) {
                assertTrue(TunnelComparator.compare(names[left], names[right]) < 0)
            }
        }
    }

    @Test
    fun overflowingNumericTokensKeepTextFallback() {
        assertTrue(TunnelComparator.compare("a2147483648", "a2147483649") < 0)
        assertTrue(TunnelComparator.compare("a2", "a2147483648") < 0)
    }

    private data class NamedTunnel(override val key: String) : Keyed<String>
}
