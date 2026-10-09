package com.wireguard.android.model

import android.app.Application
import com.wireguard.android.backend.HandshakeObservation
import com.wireguard.android.backend.HandshakeSession
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.InetEndpoint
import com.wireguard.crypto.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelHandshakeTest {
    @get:Rule val folder = TemporaryFolder()
    private val first = Key.fromBytes(ByteArray(32) { 1 })
    private val second = Key.fromBytes(ByteArray(32) { 2 })

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun configuredUpAndKernelStatisticsDoNotFabricateAuthentication() = runTest {
        val tunnel = activeTunnel()
        assertEquals(Tunnel.State.UP, tunnel.state)
        assertNull(tunnel.handshakeDiagnostics)
        val session = session()
        tunnel.onHandshakeSessionChange(session)
        runCurrent()
        assertEquals(emptyMap<Key, ObservableTunnel.PeerHandshakeDiagnostics>(), tunnel.handshakeDiagnostics)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(tunnel.handshakeDiagnostics!!.isEmpty())
    }

    @Test fun peersRetainAttemptLocalSendAuthenticationAndExhaustionSeparately() = runTest {
        val tunnel = activeTunnel()
        val session = session()
        tunnel.onHandshakeSessionChange(session)
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.ATTEMPT, 1))
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.SEND_FAILED, 2))
        tunnel.onHandshakeObservation(observation(session, second, HandshakeObservation.Event.AUTHENTICATED, 3))
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.EXHAUSTED, 4))
        runCurrent()
        val snapshot = tunnel.handshakeDiagnostics!!
        assertEquals(2, snapshot.size)
        assertEquals(HandshakeObservation.Event.ATTEMPT, snapshot[first]!!.lastAttempt!!.event())
        assertEquals(HandshakeObservation.Event.SEND_FAILED, snapshot[first]!!.lastSendResult!!.event())
        assertEquals(HandshakeObservation.Event.EXHAUSTED, snapshot[first]!!.lastObservation.event())
        assertNull(snapshot[first]!!.lastAuthenticated)
        assertNotNull(snapshot[second]!!.lastAuthenticated)
        assertNull(snapshot[second]!!.lastAttempt)
        assertNull(snapshot[second]!!.lastExhausted)
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.AUTHENTICATED, 5))
        runCurrent()
        assertEquals(HandshakeObservation.Event.AUTHENTICATED, tunnel.handshakeDiagnostics!![first]!!.lastObservation.event())
        assertNotNull(tunnel.handshakeDiagnostics!![first]!!.lastExhausted)
        assertNull(snapshot[first]!!.lastAuthenticated)
    }

    @Test fun sendSuccessAndIdleAgingDoNotInferAuthenticatedOrFailedState() = runTest {
        val tunnel = activeTunnel()
        val session = session()
        tunnel.onHandshakeSessionChange(session)
        val sent = observation(session, first, HandshakeObservation.Event.SENT, 1)
        tunnel.onHandshakeObservation(sent)
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        val peer = tunnel.handshakeDiagnostics!![first]!!
        assertSame(sent, peer.lastObservation)
        assertNull(peer.lastAuthenticated)
        assertNull(peer.lastExhausted)
    }

    @Test fun retirementRejectsBeforeAndAfterQueuedMainPublication() = runTest {
        val tunnel = activeTunnel()
        val session = session()
        tunnel.onHandshakeSessionChange(session)
        runCurrent()
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.AUTHENTICATED, 1))
        retire(session)
        assertNull(tunnel.handshakeDiagnostics)
        runCurrent()
        assertNull(tunnel.handshakeDiagnostics)
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.AUTHENTICATED, 2))
        tunnel.onHandshakeSessionChange(session)
        runCurrent()
        assertNull(tunnel.handshakeDiagnostics)
    }

    @Test fun replacementDiscardsQueuedOldActivationAndClearsHistory() = runTest {
        val tunnel = activeTunnel()
        val old = session()
        tunnel.onHandshakeSessionChange(old)
        tunnel.onHandshakeObservation(observation(old, first, HandshakeObservation.Event.AUTHENTICATED, 1))
        retire(old)
        val current = session()
        tunnel.onHandshakeSessionChange(current)
        tunnel.onHandshakeObservation(observation(current, second, HandshakeObservation.Event.ATTEMPT, 1))
        tunnel.onHandshakeSessionChange(old)
        runCurrent()
        assertEquals(setOf(second), tunnel.handshakeDiagnostics!!.keys)
        assertNull(tunnel.handshakeDiagnostics!![second]!!.lastAuthenticated)
    }

    @Test fun downRejectsOldObservationWhileStatisticsInvalidationPreservesSession() = runTest {
        val tunnel = activeTunnel()
        val session = session()
        tunnel.onHandshakeSessionChange(session)
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.AUTHENTICATED, 1))
        runCurrent()
        val before = tunnel.handshakeDiagnostics
        tunnel.invalidateStatistics()
        assertSame(before, tunnel.handshakeDiagnostics)
        tunnel.setStateAsync(Tunnel.State.DOWN)
        assertNull(tunnel.handshakeDiagnostics)
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.AUTHENTICATED, 2))
        runCurrent()
        assertNull(tunnel.handshakeDiagnostics)
    }

    @Test fun obsoleteSequenceAndUnavailableEndpointRemainHonest() = runTest {
        val tunnel = activeTunnel()
        val session = session()
        tunnel.onHandshakeSessionChange(session)
        val newer = observation(session, first, HandshakeObservation.Event.AUTHENTICATED, 2)
        tunnel.onHandshakeObservation(newer)
        tunnel.onHandshakeObservation(observation(session, first, HandshakeObservation.Event.EXHAUSTED, 1))
        tunnel.onHandshakeObservation(HandshakeObservation(session, second, HandshakeObservation.Event.SEND_FAILED,
            false, null, HandshakeObservation.AddressFamily.UNKNOWN, 3, 100))
        runCurrent()
        assertSame(newer, tunnel.handshakeDiagnostics!![first]!!.lastObservation)
        val unavailable = tunnel.handshakeDiagnostics!![second]!!.lastObservation
        assertNull(unavailable.endpoint())
        assertEquals(HandshakeObservation.AddressFamily.UNKNOWN, unavailable.addressFamily())
    }

    private suspend fun TestScope.activeTunnel(): ObservableTunnel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        return env.manager.create("one", TunnelTestEnvironment.profile()).apply { setStateAsync(Tunnel.State.UP) }
    }

    private fun session(): HandshakeSession = HandshakeSession::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }.newInstance().also {
            HandshakeSession::class.java.getDeclaredMethod("activate").apply { isAccessible = true }.invoke(it)
        }

    private fun retire(session: HandshakeSession) {
        HandshakeSession::class.java.getDeclaredMethod("retire").apply { isAccessible = true }.invoke(session)
    }

    private fun observation(session: HandshakeSession, peer: Key, event: HandshakeObservation.Event, sequence: Long) =
        HandshakeObservation(session, peer, event, event != HandshakeObservation.Event.AUTHENTICATED,
            InetEndpoint.parse("127.0.0.1:51820"), HandshakeObservation.AddressFamily.IPV4, sequence, sequence)
}
