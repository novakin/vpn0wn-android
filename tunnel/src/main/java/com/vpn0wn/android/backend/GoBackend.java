/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.backend;

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.OsConstants;
import android.util.Log;

import com.vpn0wn.android.backend.BackendException.Reason;
import com.vpn0wn.android.backend.Tunnel.State;
import com.vpn0wn.android.util.SharedLibraryLoader;
import com.vpn0wn.config.Config;
import com.vpn0wn.config.InetEndpoint;
import com.vpn0wn.config.InetAddresses;
import com.vpn0wn.config.InetNetwork;
import com.vpn0wn.config.Peer;
import com.vpn0wn.config.ParseException;
import com.vpn0wn.crypto.Key;
import com.vpn0wn.crypto.KeyFormatException;
import com.vpn0wn.util.NonNullForAll;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

/**
 * Implementation of {@link Backend} that uses the wireguard-go userspace implementation to provide
 * WireGuard tunnels.
 */
@NonNullForAll
public final class GoBackend implements Backend {
    private static final int DNS_RESOLUTION_RETRIES = 10;
    private static final int NATIVE_HANDSHAKE_EVENT_FIRST = 1;
    private static final int NATIVE_RECOVERY_CONTEXT_UNAVAILABLE = 1 << 8;
    private static final String TAG = "vpn0wn/GoBackend";
    @Nullable private static AlwaysOnCallback alwaysOnCallback;
    private static final AtomicReference<CompletableFuture<VpnService>> vpnServiceRef =
            new AtomicReference<>(new CompletableFuture<>());
    private final Context context;
    private final Object stateLock = new Object();
    private final Object transitionLock = new Object();
    @Nullable private Config currentConfig;
    @Nullable private Tunnel currentTunnel;
    @Nullable private VpnService currentService;
    private int currentTunnelHandle = -1;
    private final Handler observationHandler = new Handler(Looper.getMainLooper());
    @Nullable private volatile Activation activation;
    @Nullable private StateTransition startingTransition;

    private static final class StateTransition {
        private final Tunnel tunnel;
        private final TransportAuthority authority;
        private final CompletableFuture<Void> settlement = new CompletableFuture<>();
        private volatile boolean cancelledByDown;

        private StateTransition(final Tunnel tunnel, final TransportAuthority authority) {
            this.tunnel = tunnel;
            this.authority = authority;
        }
    }

    private final class Activation {
        private final HandshakeSession session = new HandshakeSession();
        private final Tunnel tunnel;
        private final VpnService service;
        private final Map<Key, EnumMap<HandshakeObservation.Event, HandshakeObservation>> pending = new HashMap<>();
        private final Runnable drain = this::deliver;
        private volatile boolean retired;
        private boolean committed;
        private boolean queued;
        private long sequence;
        private volatile TransportSnapshot transport;
        private final AtomicLong publication = new AtomicLong();
        private final AtomicInteger transportOperations = new AtomicInteger();

        private Activation(final Tunnel tunnel, final VpnService service, final Config config) {
            this.tunnel = tunnel;
            this.service = service;
            for (final Peer peer : config.getPeers())
                pending.put(peer.getPublicKey(), new EnumMap<>(HandshakeObservation.Event.class));
        }

        private synchronized void observe(final Key peer, final HandshakeObservation.Event event, final boolean retry,
                                          @Nullable final InetEndpoint endpoint, final HandshakeObservation.AddressFamily family, final long exhaustedEpisode) {
            final EnumMap<HandshakeObservation.Event, HandshakeObservation> events = pending.get(peer);
            if (retired || events == null)
                return;
            events.put(event, new HandshakeObservation(session, peer, event, retry, endpoint, family,
                    ++sequence, SystemClock.elapsedRealtime(), exhaustedEpisode));
            if (committed && !queued) {
                queued = true;
                observationHandler.post(drain);
            }
        }

        private synchronized void commit() {
            if (retired)
                return;
            committed = true;
            session.activate();
            queued = true;
            observationHandler.post(drain);
        }

        private void retire() {
            final boolean notify;
            synchronized (this) {
                if (retired)
                    return;
                retired = true;
                publication.getAndSet(Long.MIN_VALUE);
                session.retire();
                notify = published;
                for (final Map<HandshakeObservation.Event, HandshakeObservation> events : pending.values())
                    events.clear();
            }
            observationHandler.removeCallbacks(drain);
            if (notify)
                observationHandler.post(() -> tunnel.onHandshakeSessionChange(session));
        }

        private boolean published;

        private void deliver() {
            final List<HandshakeObservation> observations = new ArrayList<>();
            final boolean publishSession;
            synchronized (this) {
                queued = false;
                if (retired || !committed || activation != this)
                    return;
                publishSession = !published;
                published = true;
                for (final Map<HandshakeObservation.Event, HandshakeObservation> events : pending.values()) {
                    observations.addAll(events.values());
                    events.clear();
                }
            }
            if (!session.isActive() || activation != this)
                return;
            if (publishSession)
                tunnel.onHandshakeSessionChange(session);
            observations.sort(Comparator.comparingLong(HandshakeObservation::sequence));
            for (final HandshakeObservation observation : observations) {
                if (!session.isActive() || activation != this)
                    return;
                tunnel.onHandshakeObservation(observation);
            }
        }
    }

    public enum TransportMode { OWNED, LEGACY, UNAVAILABLE, UNKNOWN }

    public record TransportSnapshot(HandshakeSession session, long generation, TransportMode mode,
                                    @Nullable Network network, boolean ready) { }

    public enum TransportOutcome { UNCHANGED, BUSY, OBSOLETE, UNAVAILABLE, READY, NO_TRANSPORT }

    public record TransportResult(TransportOutcome outcome, @Nullable TransportSnapshot snapshot,
                                  boolean currentActivation, boolean contextReusable) { }

    /** A failed endpoint lookup before native recovery admission; no transport mutation occurred. */
    public static final class EndpointResolutionException extends IOException {
        private EndpointResolutionException(final UnknownHostException cause) {
            super("VPN endpoint resolution failed before native admission", cause);
        }
    }

    public static final class TransportAuthority {
        private final AtomicLong publication = new AtomicLong();

        public void retire() {
            publication.getAndSet(Long.MIN_VALUE);
        }

        private boolean claim(final long generation) {
            long previous;
            do {
                previous = publication.get();
                if (previous == Long.MIN_VALUE || previous > generation)
                    return false;
            } while (!publication.compareAndSet(previous, generation));
            return true;
        }

        private boolean isRetired() {
            return publication.get() == Long.MIN_VALUE;
        }
    }

    @Keep
    private final class SocketCapture {
        private final Activation captured;
        private final TransportMode mode;
        @Nullable private final Network network;
        private final TransportAuthority authority;

        private SocketCapture(final Activation captured, final TransportMode mode, @Nullable final Network network,
                              final TransportAuthority authority) {
            if (mode == TransportMode.OWNED && network == null)
                throw new IllegalArgumentException("Owned transport requires a Network");
            if (mode == TransportMode.UNKNOWN)
                throw new IllegalArgumentException("Unknown transport cannot be requested");
            this.captured = captured;
            this.mode = mode;
            this.network = network;
            this.authority = authority;
        }

        @Keep
        private boolean prepare(final int socket) throws IOException {
            if (!isCurrent() || authority.isRetired() || mode == TransportMode.UNAVAILABLE)
                return false;
            try (final ParcelFileDescriptor duplicate = ParcelFileDescriptor.fromFd(socket)) {
                if (mode == TransportMode.OWNED)
                    network.bindSocket(duplicate.getFileDescriptor());
                return isCurrent() && !authority.isRetired() && captured.service.protect(socket);
            }
        }

        @Keep
        private boolean report(final long generation, final boolean ready) {
            synchronized (captured.service) {
                if (!isCurrent() || !claimPublication(generation) || ready && !authority.claim(generation))
                    return false;
                final Network[] networks = !ready ? new Network[0] :
                        mode == TransportMode.LEGACY ? null : new Network[]{network};
                captured.transport = new TransportSnapshot(captured.session, generation, TransportMode.UNKNOWN, network, false);
                final boolean reported = captured.service.setUnderlyingNetworks(networks);
                if (reported)
                    captured.transport = new TransportSnapshot(captured.session, generation,
                            ready ? mode : TransportMode.UNAVAILABLE, ready ? network : null, false);
                return reported;
            }
        }

        @Keep
        private boolean confirm(final long generation) {
            if (!isCurrent() || authority.isRetired() || captured.publication.get() != generation)
                return false;
            final TransportSnapshot reported = captured.transport;
            if (reported == null || reported.generation() != generation || reported.mode() != mode)
                return false;
            captured.transport = new TransportSnapshot(captured.session, generation, mode, network, true);
            return true;
        }

        private boolean claimPublication(final long generation) {
            long previous;
            do {
                previous = captured.publication.get();
                if (previous == Long.MIN_VALUE || previous > generation)
                    return false;
            } while (!captured.publication.compareAndSet(previous, generation));
            return true;
        }

        private boolean isCurrent() {
            return activation == captured && !captured.retired && !captured.service.destroyed && captured.service.owner == GoBackend.this;
        }
    }

    /**
     * Public constructor for GoBackend.
     *
     * @param context An Android {@link Context}
     */
    public GoBackend(final Context context) {
        SharedLibraryLoader.loadSharedLibrary(context, "wg-go");
        this.context = context;
    }

    /**
     * Set a {@link AlwaysOnCallback} to be invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     *
     * @param cb Callback to be invoked
     */
    public static void setAlwaysOnCallback(final AlwaysOnCallback cb) {
        alwaysOnCallback = cb;
    }

    @Nullable private static native String wgGetConfig(int handle);

    private static native int wgRebind(int handle);

    private static native int wgRecover(int handle, byte[] peer, long episode, String endpoint,
                                        String replacement, long generation, Thread caller);

    private static native long wgTransport(int handle, long expectedGeneration, SocketCapture capture, boolean available);

    private static native long wgTransportSnapshot(int handle);

    private static native void wgTurnOff(int handle);

    private static native int wgTurnOn(String ifName, int tunFd, String settings, VpnService service, Thread activationThread, long activationId, SocketCapture capture);

    private static native int awgTurnOn(String ifName, int tunFd, String settings, VpnService service, Thread activationThread, long activationId, SocketCapture capture);

    private void captureHandshakeObservation(final VpnService service, final long activationId, final byte[] publicKey,
                                             final int eventCode, final boolean retry, final String endpoint, final long exhaustedEpisode) {
        final Activation captured = activation;
        if (captured == null || captured.service != service || captured.session.getId() != activationId)
            return;
        final HandshakeObservation.Event[] events = HandshakeObservation.Event.values();
        if (eventCode < NATIVE_HANDSHAKE_EVENT_FIRST || eventCode > events.length)
            throw new IllegalArgumentException("Invalid native handshake event");
        InetEndpoint numericEndpoint = null;
        HandshakeObservation.AddressFamily family = HandshakeObservation.AddressFamily.UNKNOWN;
        if (!endpoint.isEmpty()) {
            try {
                numericEndpoint = InetEndpoint.parse(endpoint);
                final InetAddress address = InetAddresses.parse(numericEndpoint.getHost());
                family = address instanceof Inet4Address ? HandshakeObservation.AddressFamily.IPV4 : HandshakeObservation.AddressFamily.IPV6;
            } catch (final ParseException e) {
                throw new IllegalArgumentException("Invalid native numeric endpoint");
            }
        }
        try {
            captured.observe(Key.fromBytes(publicKey), events[eventCode - NATIVE_HANDSHAKE_EVENT_FIRST], retry, numericEndpoint, family, exhaustedEpisode);
        } catch (final KeyFormatException e) {
            throw new IllegalArgumentException("Invalid native peer identity");
        }
    }

    private void retireActivation() {
        final Activation captured = activation;
        activation = null;
        if (captured != null)
            captured.retire();
    }

    private void retireServiceActivation(final VpnService service) {
        final Activation captured = activation;
        if (captured != null && captured.service == service)
            captured.retire();
    }

    private static native String wgVersion();

    private static CompletableFuture<VpnService> newVpnServiceFuture(final CompletableFuture<VpnService> previous) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return previous.newIncompleteFuture();
        return new CompletableFuture<>();
    }

    private static void resetVpnServiceFuture(final CompletableFuture<VpnService> staleFuture) {
        vpnServiceRef.compareAndSet(staleFuture, newVpnServiceFuture(staleFuture));
    }

    private static void resetVpnServiceFutureForService(final VpnService staleService) {
        final CompletableFuture<VpnService> currentFuture = vpnServiceRef.get();
        if (currentFuture.isDone() && !currentFuture.isCompletedExceptionally() && currentFuture.getNow(null) == staleService)
            resetVpnServiceFuture(currentFuture);
    }

    /**
     * Method to get the names of running tunnels.
     *
     * @return A set of string values denoting names of running tunnels.
     */
    @Override
    public Set<String> getRunningTunnelNames() {
        synchronized (stateLock) {
            if (currentTunnel != null) {
                final Set<String> runningTunnels = new ArraySet<>();
                runningTunnels.add(currentTunnel.getName());
                return runningTunnels;
            }
        }
        return Collections.emptySet();
    }

    /**
     * Get the associated {@link State} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to examine the state of.
     * @return {@link State} associated with the given tunnel.
     */
    @Override
    public State getState(final Tunnel tunnel) {
        synchronized (stateLock) {
            return currentTunnel == tunnel ? State.UP : State.DOWN;
        }
    }

    /**
     * Rebind only the captured userspace activation; a successful rebind does not prove reachability.
     * Returns false for an inactive, busy or obsolete activation without bringing a tunnel up.
     * Call off the main thread: admitted native socket replacement can block until captured I/O settles.
     * Admitted native work settles before interruption is reported; it may have replaced the sockets.
     *
     * @param tunnel the already-active tunnel whose native sockets should be replaced
     * @return true if rebind completed for the still-current activation, false if unavailable or busy
     * @throws IOException if native rebind fails; the retained activation may have no ready transport
     *                     until a later successful rebind or manual reconnection
     * @throws InterruptedException if the caller is interrupted before admission or after settlement;
     *                              the interrupt flag is preserved
     * @throws Exception if service liveness fails or JNI propagates an existing callback exception
     */
    public boolean rebind(final Tunnel tunnel) throws Exception {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN rebind interrupted");
        final int handle;
        final VpnService service;
        final Activation capturedActivation;
        synchronized (stateLock) {
            if (currentTunnel != tunnel || currentTunnelHandle == -1 || currentService == null || activation == null)
                return false;
            handle = currentTunnelHandle;
            service = currentService;
            capturedActivation = activation;
            requireActiveService(service);
        }
        final int result = (int) runNativeTransport(capturedActivation, handle, () -> (long) wgRebind(handle));
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN rebind interrupted");
        synchronized (stateLock) {
            if (currentTunnel != tunnel || currentTunnelHandle != handle || currentService != service ||
                    activation != capturedActivation || service.destroyed)
                return false;
            requireActiveService(service);
        }
        if (result == -OsConstants.EBUSY || result == -OsConstants.ENODEV)
            return false;
        if (result != 0)
            throw new IOException("Native tunnel rebind failed: " + result);
        return true;
    }

    @Nullable
    public TransportSnapshot getTransportSnapshot(final Tunnel tunnel) {
        synchronized (stateLock) {
            final Activation captured = activation;
            if (currentTunnel != tunnel || captured == null || !captured.session.isActive() ||
                    captured.retired || captured.service.destroyed || captured.service.owner != this)
                return null;
            final TransportSnapshot actual = captured.transport;
            if (actual != null && captured.transportOperations.get() != 0)
                return new TransportSnapshot(actual.session(), actual.generation(), TransportMode.UNKNOWN, actual.network(), false);
            return actual;
        }
    }

    public TransportResult replaceTransport(final Tunnel tunnel, final TransportSnapshot expected,
                                             final TransportMode mode, @Nullable final Network network,
                                             final TransportAuthority authority) throws Exception {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN transport interrupted");
        final int handle;
        final Activation captured;
        synchronized (stateLock) {
            captured = activation;
            if (captured == null || currentTunnel != tunnel || currentTunnelHandle == -1)
                return new TransportResult(TransportOutcome.UNAVAILABLE, null, false, false);
            if (captured.session != expected.session())
                return new TransportResult(TransportOutcome.OBSOLETE, captured.transport, false, true);
            handle = currentTunnelHandle;
            requireActiveService(captured.service);
        }
        final long result = runNativeTransport(captured, handle, () -> wgTransport(handle, expected.generation(),
                new SocketCapture(captured, mode, network, authority), mode != TransportMode.UNAVAILABLE));
        final int code = (int)(result & 0x7f);
        if (code >= TransportOutcome.values().length)
            throw new IllegalStateException("Invalid native transport outcome");
        final boolean current;
        synchronized (stateLock) {
            current = activation == captured && currentTunnel == tunnel && currentTunnelHandle == handle &&
                    captured.session.isActive() && !captured.service.destroyed;
        }
        final TransportResult settled = new TransportResult(TransportOutcome.values()[code], captured.transport,
                current, (result & 0x80) == 0);
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN transport interrupted");
        return settled;
    }

    private long runNativeTransport(final Activation captured, final int handle, final Callable<Long> operation) throws Exception {
        captured.transportOperations.incrementAndGet();
        Throwable originalFailure = null;
        try {
            return operation.call();
        } catch (final Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            try {
                final long nativeSnapshot = wgTransportSnapshot(handle);
                final long generation = nativeSnapshot >>> 2;
                final boolean ready = (nativeSnapshot & 1) != 0;
                final boolean healthy = (nativeSnapshot & 2) != 0;
                synchronized (captured.service) {
                    final TransportSnapshot observed = captured.transport;
                    if (activation == captured && !captured.retired) {
                        if (!healthy)
                            captured.transport = new TransportSnapshot(captured.session,
                                    observed == null ? generation : observed.generation(), TransportMode.UNKNOWN,
                                    observed == null ? null : observed.network(), false);
                        else if ((observed == null || observed.generation() <= generation) &&
                                (!ready || observed == null || observed.generation() != generation || !observed.ready()))
                            captured.transport = new TransportSnapshot(captured.session, generation,
                                    ready ? TransportMode.UNKNOWN : TransportMode.UNAVAILABLE,
                                    ready && observed != null ? observed.network() : null, false);
                    }
                }
            } catch (final Exception | Error settlementFailure) {
                synchronized (captured.service) {
                    final TransportSnapshot observed = captured.transport;
                    if (activation == captured && !captured.retired && observed != null)
                        captured.transport = new TransportSnapshot(captured.session, observed.generation(),
                                TransportMode.UNKNOWN, observed.network(), false);
                }
                if (originalFailure == null)
                    throw settlementFailure;
                originalFailure.addSuppressed(settlementFailure);
            } finally {
                captured.transportOperations.decrementAndGet();
            }
        }
    }

    /**
     * Claim a still-exhausted native episode and replace its activation's shared protected transport.
     * Call off Main. Except EndpointResolutionException, failures can follow mutation and require conservative accounting.
     * Only the triggering peer receives an ordinary initiation request; saved configuration is untouched.
     */
    public RecoveryResult recover(final Tunnel tunnel, final HandshakeObservation observation) throws Exception {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN recovery interrupted");
        if (observation.event() != HandshakeObservation.Event.EXHAUSTED)
            throw new IllegalArgumentException("Recovery requires native exhaustion");
        final int handle;
        final VpnService service;
        final Activation capturedActivation;
        final Config config;
        final TransportSnapshot transport;
        synchronized (stateLock) {
            capturedActivation = activation;
            if (currentTunnel != tunnel || currentTunnelHandle == -1 || currentService == null || capturedActivation == null)
                return new RecoveryResult(RecoveryResult.Outcome.UNAVAILABLE, false, false);
            if (capturedActivation.session != observation.session() || !observation.session().isActive() ||
                    observation.exhaustedEpisode() <= 0 || observation.endpoint() == null ||
                    observation.addressFamily() == HandshakeObservation.AddressFamily.UNKNOWN ||
                    !capturedActivation.pending.containsKey(observation.peer()))
                return new RecoveryResult(RecoveryResult.Outcome.OBSOLETE, false, false);
            handle = currentTunnelHandle;
            service = currentService;
            config = currentConfig;
            transport = capturedActivation.transport;
            requireActiveService(service);
        }
        String replacement = "";
        final InetEndpoint configured = config.getPeers().stream()
                .filter(peer -> peer.getPublicKey().equals(observation.peer()))
                .findFirst().flatMap(Peer::getEndpoint).orElse(null);
        if (configured != null && !isNumericEndpoint(configured)) {
            final Network resolvedNetwork = recoveryNetwork(transport);
            if (resolvedNetwork != null)
                replacement = resolveEndpoint(resolvedNetwork, configured, observation.endpoint()).toString();
        }
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN recovery interrupted");
        synchronized (stateLock) {
            if (activation != capturedActivation || currentConfig != config || capturedActivation.transport != transport)
                return new RecoveryResult(RecoveryResult.Outcome.OBSOLETE, false, false);
            requireActiveService(service);
        }
        final String resolved = replacement;
        final long generation = replacement.isEmpty() || transport == null ? -1 : transport.generation();
        final int result = (int) runNativeTransport(capturedActivation, handle, () -> (long) wgRecover(handle,
                observation.peer().getBytes(), observation.exhaustedEpisode(), observation.endpoint().toString(),
                resolved, generation, Thread.currentThread()));
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN recovery interrupted");
        final int outcomeCode = result & ~NATIVE_RECOVERY_CONTEXT_UNAVAILABLE;
        final RecoveryResult.Outcome[] outcomes = RecoveryResult.Outcome.values();
        if (outcomeCode < 1 || outcomeCode > outcomes.length)
            throw new IllegalStateException("Invalid native recovery outcome");
        final boolean current;
        synchronized (stateLock) {
            current = currentTunnel == tunnel && currentTunnelHandle == handle && currentService == service &&
                    activation == capturedActivation && capturedActivation.session.isActive() && !service.destroyed;
        }
        return new RecoveryResult(outcomes[outcomeCode - 1], (result & NATIVE_RECOVERY_CONTEXT_UNAVAILABLE) == 0, current);
    }

    /**
     * Get the associated {@link Statistics} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to retrieve statistics for.
     * @return {@link Statistics} associated with the given tunnel.
     */
    @Override
    public Statistics getStatistics(final Tunnel tunnel) {
        final Statistics stats = new Statistics();
        final int handle;
        synchronized (stateLock) {
            if (tunnel != currentTunnel || currentTunnelHandle == -1)
                return stats;
            handle = currentTunnelHandle;
        }
        final String config = wgGetConfig(handle);
        synchronized (stateLock) {
            if (tunnel != currentTunnel || currentTunnelHandle != handle)
                return stats;
        }
        if (config == null)
            return stats;
        Key key = null;
        long rx = 0;
        long tx = 0;
        long latestHandshakeMSec = 0;
        for (final String line : config.split("\\n")) {
            if (line.startsWith("public_key=")) {
                if (key != null)
                    stats.add(key, rx, tx, latestHandshakeMSec);
                rx = 0;
                tx = 0;
                latestHandshakeMSec = 0;
                try {
                    key = Key.fromHex(line.substring(11));
                } catch (final KeyFormatException ignored) {
                    key = null;
                }
            } else if (line.startsWith("rx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    rx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    rx = 0;
                }
            } else if (line.startsWith("tx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    tx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    tx = 0;
                }
            } else if (line.startsWith("last_handshake_time_sec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(24)) * 1000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            } else if (line.startsWith("last_handshake_time_nsec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(25)) / 1000000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            }
        }
        if (key != null)
            stats.add(key, rx, tx, latestHandshakeMSec);
        return stats;
    }

    /**
     * Get the version of the underlying wireguard-go library.
     *
     * @return {@link String} value of the version of the wireguard-go library.
     */
    @Override
    public String getVersion() {
        return wgVersion();
    }

    /**
     * Determines if the service is running in always-on VPN mode.
     * @return {@link boolean} whether the service is running in always-on VPN mode.
     */
    @Override
    public boolean isAlwaysOn() throws ExecutionException, InterruptedException, TimeoutException {
        return vpnServiceRef.get().get(0, TimeUnit.NANOSECONDS).isAlwaysOn();
    }

    /**
     * Determines if the service is running in always-on VPN lockdown mode.
     * @return {@link boolean} whether the service is running in always-on VPN lockdown mode.
     */
    @Override
    public boolean isLockdownEnabled() throws ExecutionException, InterruptedException, TimeoutException {
        return vpnServiceRef.get().get(0, TimeUnit.NANOSECONDS).isLockdownEnabled();
    }

    /**
     * Change the state of a given {@link Tunnel}, optionally applying a given {@link Config}.
     *
     * @param tunnel The tunnel to control the state of.
     * @param state  The new state for this tunnel. Must be {@code UP}, {@code DOWN}, or
     *               {@code TOGGLE}.
     * @param config The configuration for this tunnel, may be null if state is {@code DOWN}.
     * @return {@link State} of the tunnel after state changes are applied.
     * @throws Exception Exception raised while changing tunnel state.
     */
    @Override
    public State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
        return setStateWithTransport(tunnel, state, config, null);
    }

    public State setStateWithAuthority(final Tunnel tunnel, final State state, @Nullable final Config config,
                                       final TransportAuthority authority) throws Exception {
        return setStateWithTransport(tunnel, state, config, null, authority);
    }

    public State setStateOwned(final Tunnel tunnel, final State state, @Nullable final Config config,
                               final Network network, final TransportAuthority authority) throws Exception {
        return setStateWithTransport(tunnel, state, config, network, authority);
    }

    private static boolean isNumericEndpoint(final InetEndpoint endpoint) {
        try {
            InetAddresses.parse(endpoint.getHost());
            return true;
        } catch (final ParseException ignored) {
            return false;
        }
    }

    @Nullable
    private Network recoveryNetwork(@Nullable final TransportSnapshot transport) {
        return transport != null && transport.ready() && transport.mode() == TransportMode.OWNED ? transport.network() : null;
    }

    private static InetEndpoint resolveEndpoint(final Network network, final InetEndpoint endpoint,
                                                @Nullable final InetEndpoint previous) throws Exception {
        if (isNumericEndpoint(endpoint))
            return endpoint;
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN endpoint resolution interrupted");
        final InetAddress[] addresses;
        try {
            addresses = network.getAllByName(endpoint.getHost());
            if (addresses.length == 0)
                throw new UnknownHostException("VPN endpoint resolution returned no addresses");
        } catch (final UnknownHostException failure) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("VPN endpoint resolution interrupted");
            throw new EndpointResolutionException(failure);
        }
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN endpoint resolution interrupted");
        final InetAddress previousAddress = previous == null ? null : InetAddresses.parse(previous.getHost());
        InetEndpoint selected = null;
        for (final InetAddress address : addresses) {
            final String host = address.getHostAddress();
            final InetEndpoint candidate = InetEndpoint.parse((address instanceof Inet4Address ? host : "[" + host + "]") + ":" + endpoint.getPort());
            if (selected == null)
                selected = candidate;
            if (previous == null || endpoint.getPort() != previous.getPort() || !address.equals(previousAddress))
                return candidate;
        }
        return selected;
    }

    private String resolveOwnedConfig(final Config config, final Network network, final VpnService service,
                                      final TransportAuthority authority) throws Exception {
        final Config.Builder resolved = new Config.Builder().setInterface(config.getInterface());
        for (final Peer peer : config.getPeers()) {
            requireActiveService(service);
            if (authority.isRetired())
                throw new IOException("VPN transition superseded");
            final InetEndpoint endpoint = peer.getEndpoint().orElse(null);
            if (endpoint == null || isNumericEndpoint(endpoint)) {
                resolved.addPeer(peer);
                continue;
            }
            final Peer.Builder replacement = new Peer.Builder().setPublicKey(peer.getPublicKey()).addAllowedIps(peer.getAllowedIps())
                    .setEndpoint(resolveEndpoint(network, endpoint, null));
            if (peer.getAmneziaKeepalive().isPresent())
                replacement.parseAmneziaKeepalive(peer.getAmneziaKeepalive().get());
            else if (peer.getPersistentKeepalive().isPresent())
                replacement.setPersistentKeepalive(peer.getPersistentKeepalive().get());
            peer.getPreSharedKey().ifPresent(replacement::setPreSharedKey);
            resolved.addPeer(replacement.build());
        }
        requireActiveService(service);
        if (authority.isRetired())
            throw new IOException("VPN transition superseded");
        return resolved.build().toWgUserspaceString();
    }

    private State setStateWithTransport(final Tunnel tunnel, State state, @Nullable final Config config,
                                        @Nullable final Network network) throws Exception {
        return setStateWithTransport(tunnel, state, config, network, new TransportAuthority());
    }

    private State setStateWithTransport(final Tunnel tunnel, State state, @Nullable final Config config,
                                        @Nullable final Network network, final TransportAuthority authority) throws Exception {
        if (authority.isRetired())
            throw new IOException("VPN transition superseded");
        StateTransition cancelledStartup = null;
        if (state == State.DOWN) {
            synchronized (stateLock) {
                final StateTransition starting = startingTransition;
                if (starting != null && starting.tunnel == tunnel) {
                    cancelledStartup = starting;
                    starting.cancelledByDown = true;
                    starting.authority.retire();
                    if (activation != null && activation.tunnel == tunnel)
                        retireActivation();
                }
            }
        }
        if (cancelledStartup != null) {
            // A winning startup publication must drain before DOWN settles; no state owner is held here.
            cancelledStartup.settlement.join();
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("VPN startup retirement interrupted");
            return State.DOWN;
        }
        synchronized (transitionLock) {
            if (authority.isRetired())
                throw new IOException("VPN transition superseded");
            final State originalState = currentTunnel == tunnel ? State.UP : State.DOWN;

            if (state == State.TOGGLE)
                state = originalState == State.UP ? State.DOWN : State.UP;
            if (state == originalState && tunnel == currentTunnel && config == currentConfig)
                return originalState;
            if (state == State.UP) {
                final Config originalConfig = currentConfig;
                final Tunnel originalTunnel = currentTunnel;
                final TransportSnapshot previousTransport = activation == null ? null : activation.transport;
                final Network selectedNetwork = network != null ? network : originalTunnel == tunnel && previousTransport != null &&
                        previousTransport.mode() == TransportMode.OWNED ? previousTransport.network() : null;
                if (originalTunnel == tunnel && previousTransport != null && previousTransport.mode() != TransportMode.LEGACY &&
                        (selectedNetwork == null || config == null))
                    throw new IOException("Disconnect before replacing an unavailable owned configuration");
                final StateTransition transition = new StateTransition(tunnel, authority);
                synchronized (stateLock) {
                    startingTransition = transition;
                }
                try {
                    if (currentTunnel != null)
                        setStateInternal(currentTunnel, null, State.DOWN);
                    try {
                        setStateInternal(tunnel, config, state, selectedNetwork, authority);
                    } catch (final Exception e) {
                        if (originalTunnel != null && !transition.cancelledByDown && !authority.isRetired() &&
                                (previousTransport == null || previousTransport.mode() != TransportMode.UNAVAILABLE)) {
                            try {
                                setStateInternal(originalTunnel, originalConfig, State.UP,
                                        previousTransport == null ? null : previousTransport.network(), authority);
                            } catch (final Exception | Error rollbackFailure) {
                                e.addSuppressed(rollbackFailure);
                            }
                        }
                        throw e;
                    }
                } finally {
                    synchronized (stateLock) {
                        if (startingTransition == transition) startingTransition = null;
                    }
                    transition.settlement.complete(null);
                }
            } else if (state == State.DOWN && tunnel == currentTunnel) {
                setStateInternal(tunnel, null, State.DOWN);
            }
            return currentTunnel == tunnel ? State.UP : State.DOWN;
        }
    }

    private static void requireActiveService(final VpnService service) throws BackendException, InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("VPN activation interrupted");
        if (service.destroyed)
            throw new BackendException(Reason.UNABLE_TO_START_VPN);
    }

    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state)
            throws Exception {
        setStateInternal(tunnel, config, state, null);
    }

    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state,
                                  @Nullable final Network network) throws Exception {
        setStateInternal(tunnel, config, state, network, new TransportAuthority());
    }

    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state,
                                  @Nullable final Network network, final TransportAuthority authority) throws Exception {
        Log.i(TAG, "Bringing tunnel " + tunnel.getName() + ' ' + state);

        if (state == State.UP) {
            if (authority.isRetired())
                throw new IOException("VPN transition superseded");
            if (config == null)
                throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);

            if (VpnService.prepare(context) != null)
                throw new BackendException(Reason.VPN_NOT_AUTHORIZED);

            final VpnService service;
            final CompletableFuture<VpnService> serviceFuture = vpnServiceRef.get();
            if (!serviceFuture.isDone()) {
                Log.d(TAG, "Requesting to start VpnService");
                context.startService(new Intent(context, VpnService.class));
            } else {
                Log.d(TAG, "VpnService future already complete; reusing existing service reference");
            }

            try {
                service = serviceFuture.get(2, TimeUnit.SECONDS);
            } catch (final TimeoutException e) {
                final Exception be = new BackendException(Reason.UNABLE_TO_START_VPN);
                be.initCause(e);
                throw be;
            }
            service.setOwner(this);
            requireActiveService(service);

            if (authority.isRetired())
                throw new IOException("VPN transition superseded");

            if (currentTunnelHandle != -1) {
                Log.w(TAG, "Tunnel already up");
                return;
            }


            dnsRetry: for (int i = 0; network == null && i < DNS_RESOLUTION_RETRIES; ++i) {
                requireActiveService(service);
                // Pre-resolve IPs so they're cached when building the userspace string
                for (final Peer peer : config.getPeers()) {
                    final InetEndpoint ep = peer.getEndpoint().orElse(null);
                    if (ep == null)
                        continue;
                    if (ep.getResolved().orElse(null) == null) {
                        if (i < DNS_RESOLUTION_RETRIES - 1) {
                            Log.w(TAG, "DNS host \"" + ep.getHost() + "\" failed to resolve; trying again");
                            Thread.sleep(1000);
                            continue dnsRetry;
                        } else
                            throw new BackendException(Reason.DNS_RESOLUTION_FAILURE, ep.getHost());
                    }
                }
                break;
            }

            requireActiveService(service);

            // Build config
            final String goConfig = network == null ? config.toWgUserspaceString() : resolveOwnedConfig(config, network, service, authority);

            // Create the vpn tunnel with android API
            final VpnService.Builder builder = service.getBuilder();
            builder.setSession(tunnel.getName());

            for (final String excludedApplication : config.getInterface().getExcludedApplications())
                builder.addDisallowedApplication(excludedApplication);

            for (final String includedApplication : config.getInterface().getIncludedApplications())
                builder.addAllowedApplication(includedApplication);

            for (final InetNetwork addr : config.getInterface().getAddresses())
                builder.addAddress(addr.getAddress(), addr.getMask());

            for (final InetAddress addr : config.getInterface().getDnsServers())
                builder.addDnsServer(addr.getHostAddress());

            for (final String dnsSearchDomain : config.getInterface().getDnsSearchDomains())
                builder.addSearchDomain(dnsSearchDomain);

            boolean sawDefaultRoute = false;
            for (final Peer peer : config.getPeers()) {
                for (final InetNetwork addr : peer.getAllowedIps()) {
                    if (addr.getMask() == 0)
                        sawDefaultRoute = true;
                    builder.addRoute(addr.getAddress(), addr.getMask());
                }
            }

            // "Kill-switch" semantics
            if (!(sawDefaultRoute && config.getPeers().size() == 1)) {
                builder.allowFamily(OsConstants.AF_INET);
                builder.allowFamily(OsConstants.AF_INET6);
            }

            builder.setMtu(config.getInterface().getMtu().orElse(1280));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                builder.setMetered(false);
            builder.setUnderlyingNetworks(network == null ? null : new Network[0]);

            builder.setBlocking(true);
            try (final ParcelFileDescriptor tun = builder.establish()) {
                if (tun == null) {
                    Log.e(TAG, "establish() returned null; resetting stale VpnService reference");
                    resetVpnServiceFuture(serviceFuture);
                    context.startService(new Intent(context, VpnService.class));
                    throw new BackendException(Reason.TUN_CREATION_ERROR);
                }
                Log.d(TAG, "Go backend " + wgVersion());
                final Activation starting = new Activation(tunnel, service, config);
                synchronized (stateLock) {
                    activation = starting;
                }
                {
                    final int tunnelHandle;
                    try {
                        final SocketCapture capture = new SocketCapture(starting, network == null ? TransportMode.LEGACY : TransportMode.OWNED, network, authority);
                        if (config.getInterface().getAmneziaSettings().isPresent())
                            tunnelHandle = awgTurnOn(tunnel.getName(), tun.detachFd(), goConfig, service, Thread.currentThread(), starting.session.getId(), capture);
                        else
                            tunnelHandle = wgTurnOn(tunnel.getName(), tun.detachFd(), goConfig, service, Thread.currentThread(), starting.session.getId(), capture);
                        if (tunnelHandle < 0)
                            throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, tunnelHandle);
                    } catch (final Exception | Error e) {
                        retireActivation();
                        throw e;
                    }
                    try {
                        requireActiveService(service);
                        synchronized (stateLock) {
                            if (activation != starting || starting.retired || authority.isRetired())
                                throw new BackendException(Reason.UNABLE_TO_START_VPN);
                            currentTunnelHandle = tunnelHandle;
                            currentService = service;
                            currentTunnel = tunnel;
                            currentConfig = config;
                            if (startingTransition != null && startingTransition.authority == authority)
                                startingTransition = null;
                        }
                    } catch (final Exception | Error e) {
                        retireActivation();
                        try {
                            wgTurnOff(tunnelHandle);
                        } catch (final Exception | Error cleanupFailure) {
                            e.addSuppressed(cleanupFailure);
                        }
                        throw e;
                    }
                    starting.commit();
                }
            }

        } else {
            if (currentTunnelHandle == -1) {
                Log.w(TAG, "Tunnel already down");
                return;
            }
            final int handleToClose;
            final VpnService service;
            synchronized (stateLock) {
                handleToClose = currentTunnelHandle;
                retireActivation();
                service = currentService;
                currentTunnel = null;
                currentTunnelHandle = -1;
                currentConfig = null;
                currentService = null;
            }
            try {
                wgTurnOff(handleToClose);
            } finally {
                if (service != null) {
                    resetVpnServiceFutureForService(service);
                    service.stopSelf();
                }
                tunnel.onStateChange(State.DOWN);
            }
            return;
        }

        tunnel.onStateChange(state);
    }

    private void handleServiceDestroyed(final VpnService service) {
        final Tunnel tunnel;
        try {
            synchronized (transitionLock) {
                if (currentService != service)
                    return;
                Log.i(TAG, "VpnService.onDestroy(); tunnel active=" + (currentTunnelHandle != -1));
                tunnel = currentTunnel;
                final int handle;
                synchronized (stateLock) {
                    handle = currentTunnelHandle;
                    retireActivation();
                    currentTunnel = null;
                    currentTunnelHandle = -1;
                    currentConfig = null;
                    currentService = null;
                }
                try {
                    if (handle != -1)
                        wgTurnOff(handle);
                } finally {
                    if (tunnel != null)
                        tunnel.onStateChange(State.DOWN);
                }
            }
        } finally {
            resetVpnServiceFutureForService(service);
        }
    }

    /**
     * Callback for {@link GoBackend} that is invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     */
    public interface AlwaysOnCallback {
        void alwaysOnTriggered();
    }

    /**
     * {@link android.net.VpnService} implementation for {@link GoBackend}
     */
    public static class VpnService extends android.net.VpnService {
        @Nullable private volatile GoBackend owner;
        private volatile boolean destroyed;

        @Keep
        private boolean protectNativeSocket(final int socket) {
            return protect(socket);
        }

        @Keep
        private void onNativeHandshakeObservation(final long activationId, final byte[] publicKey, final int event,
                                                  final boolean retry, final String endpoint, final long exhaustedEpisode) {
            final GoBackend currentOwner = owner;
            if (currentOwner != null)
                currentOwner.captureHandshakeObservation(this, activationId, publicKey, event, retry, endpoint, exhaustedEpisode);
        }

        @Keep
        private boolean isNativeServiceActive(@Nullable final Thread activationThread)
                throws BackendException, InterruptedException {
            if (activationThread != null && activationThread.isInterrupted())
                throw new InterruptedException("VPN activation interrupted");
            if (destroyed)
                throw new BackendException(Reason.UNABLE_TO_START_VPN);
            return true;
        }

        public Builder getBuilder() {
            return new Builder();
        }

        @Override
        public void onCreate() {
            vpnServiceRef.get().complete(this);
            super.onCreate();
        }

        @Override
        public void onDestroy() {
            final GoBackend currentOwner;
            synchronized (this) {
                destroyed = true;
                currentOwner = owner;
                owner = null;
            }
            resetVpnServiceFutureForService(this);
            if (currentOwner != null)
                currentOwner.retireServiceActivation(this);
            if (currentOwner != null)
                CompletableFuture.runAsync(() -> currentOwner.handleServiceDestroyed(this))
                        .whenComplete((ignored, failure) -> {
                            if (failure != null)
                                Log.e(TAG, "Failed to release destroyed VPN service", failure);
                        });
            super.onDestroy();
        }

        @Override
        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
            if (destroyed)
                return START_NOT_STICKY;
            vpnServiceRef.get().complete(this);
            if (intent == null || intent.getComponent() == null || !intent.getComponent().getPackageName().equals(getPackageName())) {
                Log.d(TAG, "Service started by Always-on VPN feature");
                if (alwaysOnCallback != null)
                    alwaysOnCallback.alwaysOnTriggered();
            }
            return super.onStartCommand(intent, flags, startId);
        }

        public synchronized void setOwner(final GoBackend owner) throws BackendException {
            if (destroyed)
                throw new BackendException(Reason.UNABLE_TO_START_VPN);
            this.owner = owner;
        }
    }
}
