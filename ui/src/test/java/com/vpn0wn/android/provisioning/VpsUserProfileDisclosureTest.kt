/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class VpsUserProfileDisclosureTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var store: VpsServerStore
    private lateinit var profileFile: File
    private lateinit var profileKey: SecretKey
    private lateinit var profiles: VpsUserProfileStore
    private val peer = VpsPlanningTestPeer()
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(StandardTestDispatcher())
        VpsUserProfileFileTest.resetNativeFiles()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        store = VpsServerStore(preferences, credentialFileForTest(File(folder.root, "credentials")))
        profileFile = File(folder.root, "user-profiles")
        profileKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        profiles = VpsUserProfileStore(VpsUserProfileFile(profileFile, { profileKey }))
        store.upsert(VpsProfileFixture.server)
        store.saveCredential(VpsProfileFixture.server, PIN, VpsCredential.Password(PASSWORD.toCharArray()))
        peer.statusOutput = status().toString()
    }

    @After
    fun tearDown() = runTest {
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

    @Test
    fun freshStatusProducesCanonicalProfileOnlyAfterPasswordAndRemoteSessionCleanup() = runTest {
        retainProfile()
        val model = ready()
        val originalSnapshot = model.state.value.statusSnapshots
        val emissions = mutableListOf<String>()
        val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            model.state.collect { emissions.add(it.toString()) }
        }
        var received: String? = null
        var calls = 0
        requireNotNull(model.useUserProfile(server(), CLIENT_KEY) { profile ->
            calls++
            assertTrue(model.state.value.busy)
            assertEquals(peer.connections.size, peer.closes.get())
            assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
            received = profile.payload()
            assertFalse(profile.toString().contains(VpsProfileFixture.client.privateKey.toBase64()))
        }).join()
        observer.cancelAndJoin()
        assertEquals(1, calls)
        assertEquals(VpsProfileFixture.profile().payload(), received)
        assertEquals(2, statusCommands().size)
        assertEquals(originalSnapshot, model.state.value.statusSnapshots)
        assertNull(model.state.value.problem)
        assertFalse(model.state.value.busy)
        assertTrue(emissions.isNotEmpty())
        for (state in emissions + model.state.value.toString()) {
            assertFalse(state.contains(VpsProfileFixture.client.privateKey.toBase64()))
            assertFalse(state.contains(VpsProfileFixture.psk.toBase64()))
            assertFalse(state.contains(PASSWORD))
            assertFalse(state.contains("[Interface]"))
        }
    }

    @Test
    fun cachedSelectionAndServerIdentityAreRequiredBeforeRemoteAccess() = runTest {
        retainProfile()
        val model = model()
        var consumed = false
        requireNotNull(model.useUserProfile(server(), CLIENT_KEY) { consumed = true }).join()
        assertEquals(Problem.PROFILE_UNAVAILABLE, model.state.value.problem)
        assertFalse(consumed)
        assertTrue(peer.connections.isEmpty())
        requireNotNull(model.syncStatus(server())).join()
        requireNotNull(model.useUserProfile(server(), SERVER_KEY) { consumed = true }).join()
        assertEquals(Problem.PROFILE_UNAVAILABLE, model.state.value.problem)
        assertFalse(consumed)
        assertEquals(1, statusCommands().size)
    }

    @Test
    fun missingRetainedProfileCannotBeReconstructedFromPublicStatus() = runTest {
        val model = ready()
        var consumed = false
        requireNotNull(model.useUserProfile(server(), CLIENT_KEY) { consumed = true }).join()
        assertFalse(consumed)
        assertEquals(Problem.PROFILE_UNAVAILABLE, model.state.value.problem)
        assertEquals(2, statusCommands().size)
        assertFalse(profileFile.exists())
    }

    @Test
    fun unreadableVaultIsPreservedAndNeverDisclosed() = runTest {
        retainProfile()
        val model = ready()
        val damaged = profileFile.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        profileFile.writeBytes(damaged)
        var consumed = false
        requireNotNull(model.useUserProfile(server(), CLIENT_KEY) { consumed = true }).join()
        assertFalse(consumed)
        assertEquals(Problem.STORAGE_FAILED, model.state.value.problem)
        assertTrue(damaged.contentEquals(profileFile.readBytes()))
    }

    @Test
    fun freshServerPeerAndPublicProfileDriftRejectDisclosure() = runTest {
        retainProfile()
        val changes = listOf<Pair<String, (JSONObject) -> Unit>>(
            "server key" to { it.put("serverPublicKey", CLIENT_KEY) },
            "missing server key" to { it.remove("serverPublicKey") },
            "missing client" to { it.put("peers", JSONArray()) },
            "client key" to { it.getJSONArray("peers").getJSONObject(0).put("publicKey", SERVER_KEY) },
            "name" to { it.getJSONArray("peers").getJSONObject(0).put("name", "replacement") },
            "address" to { it.getJSONArray("peers").getJSONObject(0).put("allowedIps", "10.8.0.3/32") },
            "DNS" to { it.put("dns", "9.9.9.9") },
            "endpoint" to { it.put("endpoint", "203.0.113.11") },
            "port" to { it.put("listenPort", "51821") },
            "interface" to { it.put("interface", "wg1") },
            "network" to { it.put("cidr", "10.9.0.0/24") },
            "unavailable status" to { it.put("running", JSONObject.NULL) },
        )
        for ((label, change) in changes) {
            peer.statusOutput = status().toString()
            val model = ready()
            peer.statusOutput = status().also(change).toString()
            var consumed = false
            requireNotNull(model.useUserProfile(server(), CLIENT_KEY) { consumed = true }).join()
            assertFalse(label, consumed)
            assertEquals(label, Problem.PROFILE_UNAVAILABLE, model.state.value.problem)
        }
    }

    @Test
    fun failedFreshReadHasNoCachedOrOfflineDisclosureFallback() = runTest {
        retainProfile()
        val model = ready()
        peer.statusFailure = ProvisioningException("synthetic offline")
        var consumed = false
        requireNotNull(model.useUserProfile(server(), CLIENT_KEY) { consumed = true }).join()
        assertFalse(consumed)
        assertEquals(Problem.CONNECTION_FAILED, model.state.value.problem)
        assertEquals(2, statusCommands().size)
        assertEquals(peer.connections.size, peer.closes.get())
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
        assertNotNull(profiles.load(server(), SERVER_KEY, CLIENT_KEY))
    }

    @Test
    fun changedFullServerRecordDuringFreshReadRejectsDisclosure() = runTest {
        retainProfile()
        val model = ready()
        val original = server()
        blockFreshStatus()
        var consumed = false
        val operation = requireNotNull(model.useUserProfile(original, CLIENT_KEY) { consumed = true })
        peer.statusEntered.await()
        store.upsert(original.copy(name = "Renamed while reading"))
        peer.blockStatus!!.complete(Unit)
        operation.join()
        assertFalse(consumed)
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertNotNull(profiles.load(original, SERVER_KEY, CLIENT_KEY))
        assertEquals(peer.connections.size, peer.closes.get())
    }

    @Test
    fun clearedAccessDuringFreshReadRejectsDisclosure() = runTest {
        retainProfile()
        val model = ready()
        val original = server()
        blockFreshStatus()
        var consumed = false
        val operation = requireNotNull(model.useUserProfile(original, CLIENT_KEY) { consumed = true })
        peer.statusEntered.await()
        store.clearCredential(original.id)
        assertEquals(PIN, store.trustedHostFingerprint(original.host, original.sshPort))
        peer.blockStatus!!.complete(Unit)
        operation.join()
        assertFalse(consumed)
        assertEquals(Problem.CONNECTION_CHANGED, model.state.value.problem)
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
        assertNotNull(profiles.load(original, SERVER_KEY, CLIENT_KEY))
    }

    @Test
    fun cancellationDuringFreshReadNeverConsumesProfileAndReleasesAdmission() = runTest {
        retainProfile()
        val model = ready()
        blockFreshStatus()
        var consumed = false
        val operation = requireNotNull(model.useUserProfile(server(), CLIENT_KEY) { consumed = true })
        peer.statusEntered.await()
        operation.cancelAndJoin()
        assertFalse(consumed)
        assertEquals(Problem.CANCELLED, model.state.value.problem)
        assertFalse(model.state.value.busy)
        assertEquals(peer.connections.size, peer.closes.get())
        assertTrue(peer.connections.all { (it.credential as VpsCredential.Password).value.all { value -> value == '\u0000' } })
        peer.blockStatus!!.complete(Unit)
        requireNotNull(model.useUserProfile(server(), CLIENT_KEY) { consumed = true }).join()
        assertTrue(consumed)
        assertNull(model.state.value.problem)
    }

    @Test
    fun admittedConsumerBlocksDuplicateDisclosureAndOtherOperationsUntilItSettles() = runTest {
        retainProfile()
        val model = ready()
        val expected = server()
        val consumeEntered = CompletableDeferred<Unit>()
        val releaseConsume = CompletableDeferred<Unit>()
        var calls = 0
        val operation = requireNotNull(model.useUserProfile(expected, CLIENT_KEY) {
            calls++
            consumeEntered.complete(Unit)
            releaseConsume.await()
        })
        try {
            consumeEntered.await()
            assertTrue(model.state.value.busy)
            assertNull(model.useUserProfile(expected, CLIENT_KEY) { calls++ })
            assertNull(model.syncStatus(expected))
            assertEquals(2, statusCommands().size)
        } finally {
            releaseConsume.complete(Unit)
            operation.join()
        }
        assertEquals(1, calls)
        assertFalse(model.state.value.busy)
        requireNotNull(model.useUserProfile(expected, CLIENT_KEY) { calls++ }).join()
        assertEquals(2, calls)
    }

    private suspend fun retainProfile() {
        profiles.saveGenerated(VpsProfileFixture.server, SERVER_KEY, VpsProfileFixture.request, VpsProfileFixture.result())
    }

    private fun blockFreshStatus() {
        peer.statusEntered = CompletableDeferred()
        peer.blockStatus = CompletableDeferred()
    }

    private suspend fun ready(): VpsProvisioningViewModel = model().also { requireNotNull(it.syncStatus(server())).join() }

    private fun model() = VpsProvisioningViewModel(ApplicationProvider.getApplicationContext(), store, peer, { OBSERVED_AT }, profiles).also {
        jobs.add(requireNotNull(it.viewModelScope.coroutineContext[Job]))
        owners.add(ViewModelStore().apply { put("owner", it) })
    }

    private suspend fun server() = store.servers.first().single()

    private fun statusCommands() = peer.commands.filter { it.contains("./'sync-status.sh'") }

    private fun status(): JSONObject = JSONObject().put("ok", true).put("interface", "wg0").put("running", true)
        .put("endpoint", VpsProfileFixture.server.endpoint).put("listenPort", "51820").put("cidr", "10.8.0.0/24")
        .put("dns", "1.1.1.1").put("serverPublicKey", SERVER_KEY)
        .put("peers", JSONArray().put(JSONObject().put("name", "phone").put("publicKey", CLIENT_KEY).put("allowedIps", "10.8.0.2/32")
            .put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL)))

    private companion object {
        const val PASSWORD = "synthetic-password"
        const val OBSERVED_AT = 1_710_000_012L
        val SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        val PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    }
}
