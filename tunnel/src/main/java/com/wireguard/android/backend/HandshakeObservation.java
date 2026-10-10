/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.backend;

import com.wireguard.config.InetEndpoint;
import com.wireguard.crypto.Key;
import com.wireguard.util.NonNullForAll;

import androidx.annotation.Nullable;

@NonNullForAll
public record HandshakeObservation(HandshakeSession session, Key peer, Event event, boolean retry,
                                   @Nullable InetEndpoint endpoint, AddressFamily addressFamily,
                                   long sequence, long elapsedRealtimeMillis) {
    public enum Event {
        ATTEMPT,
        SENT,
        SEND_FAILED,
        AUTHENTICATED,
        EXHAUSTED
    }

    public enum AddressFamily {
        IPV4,
        IPV6,
        UNKNOWN
    }
}
