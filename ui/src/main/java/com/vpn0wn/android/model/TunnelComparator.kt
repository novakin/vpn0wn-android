/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.model

object TunnelComparator : Comparator<String> {
    private class NaturalSortString(originalString: String) {
        class NaturalSortToken(val maybeString: String?, val maybeNumber: Int?) : Comparable<NaturalSortToken> {
            override fun compareTo(other: NaturalSortToken): Int {
                return when {
                    maybeString == null && other.maybeString == null -> maybeNumber!!.compareTo(other.maybeNumber!!)
                    maybeString == null -> -1
                    other.maybeString == null -> 1
                    else -> maybeString.compareTo(other.maybeString)
                }
            }
        }

        val tokens: MutableList<NaturalSortToken> = ArrayList()

        init {
            for (s in NATURAL_SORT_DIGIT_FINDER.findAll(originalString.split(WHITESPACE_FINDER).joinToString(" ").lowercase())) {
                try {
                    val n = s.value.toInt()
                    tokens.add(NaturalSortToken(null, n))
                } catch (_: NumberFormatException) {
                    tokens.add(NaturalSortToken(s.value, null))
                }
            }
        }

        private companion object {
            private val NATURAL_SORT_DIGIT_FINDER = Regex("""\d+|\D+""")
            private val WHITESPACE_FINDER = Regex("""\s""")
        }
    }

    override fun compare(a: String, b: String): Int {
        if (a == b)
            return 0
        val na = NaturalSortString(a)
        val nb = NaturalSortString(b)
        for (i in 0 until minOf(na.tokens.size, nb.tokens.size)) {
            val c = na.tokens[i].compareTo(nb.tokens[i])
            if (c != 0)
                return c
        }
        val lengthOrder = na.tokens.size.compareTo(nb.tokens.size)
        return if (lengthOrder != 0) lengthOrder else a.compareTo(b)
    }
}
