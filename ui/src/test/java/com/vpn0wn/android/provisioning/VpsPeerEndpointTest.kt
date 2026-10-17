/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import com.vpn0wn.config.InetEndpoint
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [VpsCredentialFileTest.NativeFiles::class])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsPeerEndpointTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore
    private lateinit var profileFile: File
    private lateinit var profiles: VpsUserProfileStore
    private val peer = VpsPlanningTestPeer()
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(StandardTestDispatcher())
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        profileFile = File(folder.root, "user-profiles").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        profiles = VpsUserProfileStore(VpsUserProfileFile(profileFile, { error("Endpoint access must not open the profile vault") }))
        store.upsert(VpsProfileFixture.server)
        store.saveCredential(VpsProfileFixture.server, PIN, VpsCredential.Password(PASSWORD.toCharArray()))
        peer.statusOutput = status().toString()
    }

    @After fun tearDown() = runTest {
        withContext(NonCancellable) {
            peer.blockStatus?.complete(Unit)
            owners.forEach(ViewModelStore::clear)
            try {
                jobs.forEach { it.cancelAndJoin() }
            } finally {
                try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally { Dispatchers.resetMain() }
            }
        }
    }

    @Test fun freshPinnedStatusWorksWithoutCacheOrVaultAndDoesNotWriteFleetMetadata() = runTest {
        val model = model()
        val original = server()
        val metadata = preferences.data.first()
        val received = mutableListOf<Pair<VpsServerStatus, VpsPeerStatus>>()
        requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { status, user, fingerprint ->
            assertEquals(peer.connections.size, peer.closes.get())
            assertEquals(PIN, fingerprint)
            assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
            received += status to user
        }).join()
        assertEquals(1, received.size)
        assertEquals(CLIENT_KEY, received.single().second.publicKey)
        assertEquals(SERVER_KEY, received.single().first.serverPublicKey)
        assertTrue(model.state.value.statusSnapshots.isEmpty())
        assertEquals(metadata, preferences.data.first())
        assertEquals(original, server())
        assertArrayEquals(byteArrayOf(1, 2, 3), profileFile.readBytes())
        assertEquals(PIN, peer.connections.single().hostKeyFingerprint)
        assertEquals(VpsEndpoint(original.host, original.sshPort, original.sshUser), peer.connections.single().endpoint)
        assertNull(model.state.value.problem)
        assertFalse(model.state.value.busy)
        assertTrue(peer.commands.none { command -> listOf("restore.sh", "add-peer.sh", "remove-peer.sh", "revoke-peer.sh", "rotate-peer.sh", "install.sh")
            .any { command.contains("./'$it'") } })
        assertFalse(model.state.value.toString().contains(PASSWORD))
    }

    @Test fun everyRequestReadsCurrentEndpointAndPortIncludingNumericIpv6() = runTest {
        val model = model()
        val original = server()
        val received = mutableListOf<String>()
        for ((endpoint, port) in listOf("203.0.113.25" to "51821", "2001:db8::25" to "51822", "[2001:db8::26]" to "51823")) {
            peer.statusOutput = status().put("endpoint", endpoint).put("listenPort", port).toString()
            requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { status, _, _ ->
                received += "${status.endpoint}:${status.listenPort}"
            }).join()
            assertNull(model.state.value.problem)
        }
        assertEquals(listOf("203.0.113.25:51821", "2001:db8::25:51822", "[2001:db8::26]:51823"), received)
        assertEquals(3, statusCommands().size)
        assertEquals(original, server())
    }

    @Test fun stoppedServerRequiresKnownEmptyRuntimeMembershipWithoutClaimingHealth() = runTest {
        val model = model()
        peer.statusOutput = status().put("running", false).put("runtimePeerPublicKeys", JSONArray()).toString()
        var received: VpsServerStatus? = null
        requireNotNull(model.usePeerEndpoint(server(), SERVER_KEY, CLIENT_KEY) { status, _, _ -> received = status }).join()
        assertEquals(false, received?.running)
        assertNull(model.state.value.problem)
        peer.statusOutput = status().put("running", false).put("runtimePeerPublicKeys", JSONObject.NULL).toString()
        requireNotNull(model.usePeerEndpoint(server(), SERVER_KEY, CLIENT_KEY) { _, _, _ -> fail("Unknown runtime inventory was consumed") }).join()
        assertEquals(Problem.PEER_CHANGED, model.state.value.problem)
    }

    @Test fun changedServerClientConfiguredRuntimeOrRevokedMembershipNeverConsumesCachedAuthority() = runTest {
        val changes = listOf<Pair<String, (JSONObject) -> Unit>>(
            "server identity" to { it.put("serverPublicKey", OTHER_KEY) },
            "unknown server identity" to { it.put("serverPublicKey", JSONObject.NULL) },
            "missing client" to { it.put("peers", JSONArray()) },
            "different client" to { it.getJSONArray("peers").getJSONObject(0).put("publicKey", OTHER_KEY) },
            "missing configured client" to { it.put("configuredPeerPublicKeys", JSONArray()) },
            "unknown configured inventory" to { it.put("configuredPeerPublicKeys", JSONObject.NULL) },
            "missing live client" to { it.put("runtimePeerPublicKeys", JSONArray()) },
            "unknown live inventory" to { it.put("runtimePeerPublicKeys", JSONObject.NULL) },
            "unknown revocations" to { it.put("revokedArchives", JSONObject.NULL) },
            "revoked client" to { it.put("revokedArchives", JSONArray().put(JSONObject().put("archiveName", "phone.conf.ABC123")
                .put("name", "phone").put("publicKey", CLIENT_KEY).put("operationId", JSONObject.NULL))) },
            "stopped live client conflict" to { it.put("running", false) },
            "different interface" to { it.put("interface", "wg1") },
        )
        val model = model()
        val original = server()
        requireNotNull(model.syncStatus(original)).join()
        val current = server()
        for ((label, change) in changes) {
            peer.statusOutput = status().also(change).toString()
            var consumed = false
            requireNotNull(model.usePeerEndpoint(current, SERVER_KEY, CLIENT_KEY) { _, _, _ -> consumed = true }).join()
            assertFalse(label, consumed)
            assertEquals(label, Problem.PEER_CHANGED, model.state.value.problem)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), profileFile.readBytes())
    }

    @Test fun unknownRunningStateHostnameAndInvalidPortsAreSafeInvalidResponses() = runTest {
        val changes = listOf<Pair<String, (JSONObject) -> Unit>>(
            "unknown running" to { it.put("running", JSONObject.NULL) },
            "hostname" to { it.put("endpoint", "vpn.example") },
            "invalid endpoint" to { it.put("endpoint", "not an address") },
            "zero port" to { it.put("listenPort", "0") },
            "large port" to { it.put("listenPort", "65536") },
            "noncanonical port" to { it.put("listenPort", "051820") },
        )
        val model = model()
        for ((label, change) in changes) {
            peer.statusOutput = status().also(change).toString()
            requireNotNull(model.usePeerEndpoint(server(), SERVER_KEY, CLIENT_KEY) { _, _, _ -> fail(label) }).join()
            assertEquals(label, Problem.INVALID_RESPONSE, model.state.value.problem)
        }
    }

    @Test fun malformedEmptyAndUriLikeRemoteEndpointsRemainFixedInvalidResponses() = runTest {
        val model = model()
        for (endpoint in listOf("", "bad..host", "wg://203.0.113.10", "https://[2001:db8::25]", "[203.0.113.10]",
            "[2001:db8::25", "2001:db8::25]", "[[2001:db8::25]]")) {
            peer.statusOutput = status().put("endpoint", endpoint).toString()
            var consumed = false
            val operation = requireNotNull(model.usePeerEndpoint(server(), SERVER_KEY, CLIENT_KEY) { _, _, _ -> consumed = true })
            operation.join()
            assertFalse(endpoint, consumed)
            assertFalse(endpoint, operation.isCancelled)
            assertEquals(endpoint, Problem.INVALID_RESPONSE, model.state.value.problem)
            assertEquals(peer.connections.size, peer.closes.get())
            assertArrayEquals(byteArrayOf(1, 2, 3), profileFile.readBytes())
            if (endpoint.isNotEmpty()) assertFalse(model.state.value.toString().contains(endpoint))
        }
    }

    @Test fun fullSavedRecordAndPinChangesDuringReadRejectConsumption() = runTest {
        for (changePin in listOf(false, true)) {
            val original = server()
            val model = model()
            blockFreshStatus()
            var consumed = false
            val operation = requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { _, _, _ -> consumed = true })
            peer.statusEntered.await()
            if (changePin) store.saveCredential(original, OTHER_PIN, VpsCredential.Password("replacement".toCharArray()))
            else store.upsert(original.copy(name = "Changed while status is read"))
            peer.blockStatus!!.complete(Unit)
            operation.join()
            assertFalse(consumed)
            assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
            assertEquals(peer.connections.size, peer.closes.get())
            assertArrayEquals(byteArrayOf(1, 2, 3), profileFile.readBytes())
        }
    }

    @Test fun everyPendingOwnerAndSshAliasIsRejectedBeforeRemoteAccess() = runTest {
        val original = server()
        for (alias in listOf(false, true)) for (pending in pendingCases(original, alias)) {
            preferences.edit { it[stringPreferencesKey(pending.key)] = pending.value }
            val metadata = preferences.data.first()
            val model = model()
            requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { _, _, _ -> fail(pending.key) }).join()
            assertEquals(pending.key, pending.problem, model.state.value.problem)
            assertEquals(metadata, preferences.data.first())
            assertTrue(peer.connections.isEmpty())
            preferences.edit { it.remove(stringPreferencesKey(pending.key)) }
        }
    }

    @Test fun changedReviewedFingerprintIsRejectedBeforeAnotherSshRead() = runTest {
        val model = model()
        val original = server()
        var reviewedFingerprint: String? = null
        requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { _, _, fingerprint ->
            reviewedFingerprint = fingerprint
        }).join()
        assertEquals(PIN, reviewedFingerprint)
        store.saveCredential(original, OTHER_PIN, VpsCredential.Password("replacement".toCharArray()))
        requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY, reviewedFingerprint) { _, _, _ ->
            fail("Changed reviewed pin was consumed")
        }).join()
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertEquals(1, statusCommands().size)
        assertEquals(1, peer.connections.size)
        assertEquals(original, server())
    }

    @Test fun queuedLocalAdmissionRechecksPinAndPendingIntentWithoutFleetRecordChange() = runTest {
        val original = server()
        for (changePin in listOf(false, true)) {
            val env = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
            val model = model()
            val config = com.vpn0wn.config.Config.parse(VpsProfileFixture.text().byteInputStream())
            val selected = env.manager.create("selected", config)
            val blocker = env.manager.create("blocker", TunnelTestEnvironment.profile())
            val update = VpsLocalEndpointUpdate.create(selected, config, SERVER_KEY, CLIENT_KEY,
                InetEndpoint.parse("198.51.100.25:51821"))
            val atApply = CompletableDeferred<Unit>()
            env.backend.blockUp = true
            val blocking = launch { blocker.setStateAsync(Tunnel.State.UP) }
            var operation: Job? = null
            try {
                withContext(Dispatchers.IO) { assertTrue(env.backend.upEntered.await(5, TimeUnit.SECONDS)) }
                val admitted = requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY, PIN) { _, _, fingerprint ->
                    atApply.complete(Unit)
                    update.apply(env.manager) { model.checkPeerEndpointOwner(original, fingerprint) }
                })
                operation = admitted
                atApply.await()
                if (changePin) store.saveCredential(original, OTHER_PIN, VpsCredential.Password("replacement".toCharArray()))
                else store.admitInstall(original, VpsEndpoint(original.host, original.sshPort, original.sshUser),
                    VpsInstallRequest(original.endpoint, original.cidr, original.dns))
                assertEquals(original, server())
                env.backend.releaseUp.countDown()
                blocking.join()
                admitted.join()
                assertEquals(if (changePin) Problem.CONNECTION_CHANGED else Problem.INSTALL_PENDING, model.state.value.problem)
                assertSame(config, selected.peekConfig())
                assertSame(config, env.store.configs["selected"])
                assertEquals(Tunnel.State.DOWN, selected.state)
                assertEquals(Tunnel.State.DOWN, env.backend.getState(selected))
                assertArrayEquals(byteArrayOf(1, 2, 3), profileFile.readBytes())
            } finally {
                env.backend.releaseUp.countDown()
                blocking.join()
                operation?.cancelAndJoin()
                env.close()
                preferences.edit { it.remove(stringPreferencesKey("vps_pending_installs")) }
                store.saveCredential(original, PIN, VpsCredential.Password(PASSWORD.toCharArray()))
            }
        }
    }

    @Test fun everyPendingOwnerAppearingDuringFreshReadRejectsConsumptionAndRemainsDurable() = runTest {
        val original = server()
        for (pending in pendingCases(original, alias = false)) {
            val model = model()
            blockFreshStatus()
            var consumed = false
            val operation = requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { _, _, _ -> consumed = true })
            peer.statusEntered.await()
            preferences.edit { it[stringPreferencesKey(pending.key)] = pending.value }
            val metadata = preferences.data.first()
            peer.blockStatus!!.complete(Unit)
            operation.join()
            assertFalse(pending.key, consumed)
            assertEquals(pending.key, pending.problem, model.state.value.problem)
            assertEquals(metadata, preferences.data.first())
            assertEquals(peer.connections.size, peer.closes.get())
            preferences.edit { it.remove(stringPreferencesKey(pending.key)) }
        }
    }

    @Test fun failedFreshReadHasNoCachedFallbackAndReleasesCredentialsAndTransport() = runTest {
        val model = model()
        requireNotNull(model.syncStatus(server())).join()
        peer.statusFailure = ProvisioningException("synthetic offline")
        var consumed = false
        requireNotNull(model.usePeerEndpoint(server(), SERVER_KEY, CLIENT_KEY) { _, _, _ -> consumed = true }).join()
        assertFalse(consumed)
        assertEquals(Problem.CONNECTION_FAILED, model.state.value.problem)
        assertEquals(peer.connections.size, peer.closes.get())
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
        assertArrayEquals(byteArrayOf(1, 2, 3), profileFile.readBytes())
    }

    @Test fun cancellationDuringFreshReadNeverConsumesAndCanRetryExplicitly() = runTest {
        val model = model()
        blockFreshStatus()
        var consumed = false
        val operation = requireNotNull(model.usePeerEndpoint(server(), SERVER_KEY, CLIENT_KEY) { _, _, _ -> consumed = true })
        peer.statusEntered.await()
        operation.cancelAndJoin()
        assertFalse(consumed)
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertFalse(model.state.value.busy)
        assertEquals(peer.connections.size, peer.closes.get())
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
        peer.blockStatus!!.complete(Unit)
        requireNotNull(model.usePeerEndpoint(server(), SERVER_KEY, CLIENT_KEY) { _, _, _ -> consumed = true }).join()
        assertTrue(consumed)
        assertNull(model.state.value.problem)
    }

    @Test fun consumerOwnsAdmissionUntilSettlementAndCancellationPropagates() = runTest {
        val model = model()
        val original = server()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val operation = requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { _, _, _ ->
            calls++
            entered.complete(Unit)
            release.await()
        })
        try {
            entered.await()
            assertTrue(model.state.value.busy)
            assertNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { _, _, _ -> calls++ })
            assertNull(model.syncStatus(original))
            operation.cancelAndJoin()
            assertEquals(Problem.CANCELLED, model.state.value.problem)
            assertFalse(model.state.value.busy)
        } finally {
            release.complete(Unit)
            operation.join()
        }
        assertEquals(1, calls)
        assertArrayEquals(byteArrayOf(1, 2, 3), profileFile.readBytes())
    }

    @Test fun missingSavedAccessNeverCreatesRemoteSession() = runTest {
        val original = server()
        store.clearCredential(original.id)
        val model = model()
        requireNotNull(model.usePeerEndpoint(original, SERVER_KEY, CLIENT_KEY) { _, _, _ -> fail("Missing access was consumed") }).join()
        assertEquals(Problem.ACCESS_MISSING, model.state.value.problem)
        assertTrue(peer.connections.isEmpty())
    }

    private fun model() = VpsProvisioningViewModel(ApplicationProvider.getApplicationContext(), store, peer, { OBSERVED_AT }, profiles).also {
        jobs.add(requireNotNull(it.viewModelScope.coroutineContext[Job]))
        owners.add(ViewModelStore().apply { put("owner", it) })
    }

    private suspend fun server() = store.servers.first().single()
    private fun statusCommands() = peer.commands.filter { it.contains("./'sync-status.sh'") }

    private fun blockFreshStatus() {
        peer.statusEntered = CompletableDeferred()
        peer.blockStatus = CompletableDeferred()
    }

    private fun status(): JSONObject = JSONObject().put("ok", true).put("interface", "wg0").put("running", true)
        .put("endpoint", "203.0.113.10").put("listenPort", "51820").put("cidr", "10.8.0.0/24").put("dns", "1.1.1.1")
        .put("serverPublicKey", SERVER_KEY).put("configuredPeerPublicKeys", JSONArray().put(CLIENT_KEY))
        .put("runtimePeerPublicKeys", JSONArray().put(CLIENT_KEY)).put("revokedArchives", JSONArray())
        .put("peers", JSONArray().put(JSONObject().put("name", "phone").put("publicKey", CLIENT_KEY).put("allowedIps", "10.8.0.2/32")
            .put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL)))

    private data class PendingCase(val key: String, val value: String, val problem: Problem)

    private fun pendingCases(server: StoredVpsServer, alias: Boolean): List<PendingCase> {
        val id = if (alias) "original-owner" else server.id
        val ssh = VpsEndpoint(server.host, server.sshPort, server.sshUser)
        val remove = PendingVpsRemove(id, ssh, PIN, SERVER_KEY, "phone", CLIENT_KEY)
        val plan = VpsRestoreCheckPlan("wg0", "203.0.113.10", "51820", "10.8.0.0/24", "1.1.1.1", SERVER_KEY, 1, 0)
        return listOf(
            PendingCase("vps_pending_installs", PendingVpsInstall.encode(listOf(PendingVpsInstall(id, ssh,
                VpsInstallRequest("203.0.113.10", "10.8.0.0/24", "1.1.1.1")))), Problem.INSTALL_PENDING),
            PendingCase("vps_pending_adds", PendingVpsAdd.encode(listOf(PendingVpsAdd(id, ssh, PIN, SERVER_KEY,
                VpsAddPeerRequest("phone", "0.0.0.0/0", "1.1.1.1")))), Problem.ADD_PENDING),
            PendingCase("vps_pending_removals", PendingVpsRemove.encode(listOf(remove)), Problem.REMOVE_PENDING),
            PendingCase("vps_pending_removals", PendingVpsRemove.encode(listOf(remove.copy(action = PendingVpsRemove.Action.REVOKE,
                operationId = OPERATION_ID))), Problem.REMOVE_PENDING),
            PendingCase("vps_pending_rotations", PendingVpsRotate.encode(listOf(PendingVpsRotate(id, ssh, PIN, SERVER_KEY,
                "phone", CLIENT_KEY, OTHER_KEY, OPERATION_ID))), Problem.ROTATE_PENDING),
            PendingCase("vps_pending_restores", PendingVpsRestore.encode(listOf(PendingVpsRestore(OPERATION_ID, id, ssh, PIN,
                "a".repeat(64), plan))), Problem.RESTORE_PENDING),
        )
    }

    private companion object {
        const val PASSWORD = "synthetic-password"
        const val OBSERVED_AT = 1_710_000_012L
        const val OPERATION_ID = "12345678-1234-4234-8234-123456789abc"
        val SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        val OTHER_KEY = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val OTHER_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
    }
}
