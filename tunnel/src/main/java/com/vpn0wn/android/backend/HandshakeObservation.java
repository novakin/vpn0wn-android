/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.backend;

import com.vpn0wn.config.InetEndpoint;
import com.vpn0wn.crypto.Key;
import com.vpn0wn.util.NonNullForAll;

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
