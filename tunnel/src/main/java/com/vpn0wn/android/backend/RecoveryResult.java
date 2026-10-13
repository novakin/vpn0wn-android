/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.backend;

/** Settlement of one exhausted-cycle request; transport readiness is not authenticated recovery. */
public record RecoveryResult(Outcome outcome, boolean contextReusable, boolean currentActivation) {
    public enum Outcome {
        BUSY,
        OBSOLETE,
        UNAVAILABLE,
        TRANSPORT_FAILED,
        INITIATION_STARTED,
        INITIATION_SKIPPED,
        INITIATION_FAILED,
        INITIATION_SEND_FAILED
    }

    public boolean admitted() {
        return outcome == Outcome.TRANSPORT_FAILED || outcome == Outcome.INITIATION_STARTED ||
                outcome == Outcome.INITIATION_SKIPPED || outcome == Outcome.INITIATION_FAILED || outcome == Outcome.INITIATION_SEND_FAILED;
    }

    public boolean transportReady() {
        return contextReusable && currentActivation && (outcome == Outcome.INITIATION_STARTED || outcome == Outcome.INITIATION_SKIPPED ||
                outcome == Outcome.INITIATION_FAILED || outcome == Outcome.INITIATION_SEND_FAILED);
    }

    public boolean initiationStarted() {
        return outcome == Outcome.INITIATION_STARTED || outcome == Outcome.INITIATION_SEND_FAILED;
    }
}
