/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.backend;

import java.util.concurrent.atomic.AtomicLong;

public final class HandshakeSession {
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private final long id;
    private volatile boolean active;

    HandshakeSession() {
        id = NEXT_ID.getAndUpdate(value -> value == Long.MAX_VALUE ? value : value + 1);
        if (id == Long.MAX_VALUE)
            throw new IllegalStateException("Handshake activation identities exhausted");
    }

    public long getId() {
        return id;
    }

    public boolean isActive() {
        return active;
    }

    void activate() {
        active = true;
    }

    void retire() {
        active = false;
    }
}
