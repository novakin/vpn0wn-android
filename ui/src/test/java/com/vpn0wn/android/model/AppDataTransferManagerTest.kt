package com.vpn0wn.android.model

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.configStore.ConfigStore
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsServerStore
import com.vpn0wn.android.util.AppDataTransfer
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.config.Config as TunnelConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppDataTransferManagerTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun exportAndImportRoundTripProfilesSettingsSelectionAndFleetWithoutActivating() = runTest {
        val source = environment(folder.newFolder("source"))
        val first = source.manager.create("first", TunnelTestEnvironment.profile())
        source.manager.create("second", TunnelTestEnvironment.profile(51821))
        first.setStateAsync(Tunnel.State.UP)
        val sourcePreferences = preferencesStore()
        sourcePreferences.edit {
            it[booleanPreferencesKey("dark_theme")] = true
            it[booleanPreferencesKey("multiple_tunnels")] = false
            it[booleanPreferencesKey("allow_remote_control_intents")] = true
            it[booleanPreferencesKey("restore_on_boot")] = true
            it[booleanPreferencesKey("enable_kernel_module")] = true
            it[booleanPreferencesKey("quick_tile_added")] = true
            it[stringPreferencesKey("updater_newer_version_consented")] = "source-consent"
        }
        val server = server("source-server")
        VpsServerStore(sourcePreferences).upsert(server)
        val archive = source.manager.exportAppData()

        val destination = environment(folder.newFolder("destination"))
        preferencesStore().edit {
            it[booleanPreferencesKey("enable_kernel_module")] = false
            it[booleanPreferencesKey("quick_tile_added")] = false
            it[stringPreferencesKey("updater_newer_version_consented")] = "destination-consent"
        }
        assertEquals(2, destination.manager.importAppData(archive))

        val result = destination.manager.exportAppData()
        assertEquals(archive.profiles.keys, result.profiles.keys)
        for ((name, config) in archive.profiles) {
            assertEquals(config.toWgQuickString(), result.profiles.getValue(name).toWgQuickString())
        }
        assertEquals(archive.settings, result.settings)
        assertEquals("first", result.lastUsedTunnel)
        assertEquals(listOf(server), result.servers)
        assertSame(destination.manager.getTunnels()["first"], destination.manager.lastUsedTunnel)
        assertTrue(destination.manager.getTunnels().all { it.state == Tunnel.State.DOWN })
        assertTrue(destination.backend.getRunningTunnelNames().isEmpty())
        assertTrue(destination.backend.appliedConfigs.isEmpty())
        val persisted = preferencesStore().data.first()
        assertEquals(false, persisted[booleanPreferencesKey("enable_kernel_module")])
        assertEquals(false, persisted[booleanPreferencesKey("quick_tile_added")])
        assertEquals("destination-consent", persisted[stringPreferencesKey("updater_newer_version_consented")])
        assertTrue(UserKnobs.runningTunnels.first().isEmpty())
    }

    @Test
    fun conflictingProfileRejectsWholeImportBeforeCreatingEarlierNewProfile() = runTest {
        val env = environment()
        val existing = env.manager.create("existing", TunnelTestEnvironment.profile())
        UserKnobs.setDarkTheme(false)
        val before = preferencesStore().data.first()
        val input = archive(
            linkedMapOf("new" to TunnelTestEnvironment.profile(), "existing" to TunnelTestEnvironment.profile(51821)),
            settings = mapOf("dark_theme" to true),
        )

        expectFailure<AppDataTransfer.ConflictException> { env.manager.importAppData(input) }

        assertEquals(setOf("existing"), env.store.configs.keys)
        assertSame(existing, env.manager.getTunnels()["existing"])
        assertEquals(before.asMap(), preferencesStore().data.first().asMap())
        assertTrue(env.store.deleteAttempts.isEmpty())
    }

    @Test
    fun invalidNameAndMissingSelectionRejectBeforeAnyProfileOrMetadataWrite() = runTest {
        val env = environment()
        UserKnobs.setDarkTheme(false)
        val before = preferencesStore().data.first()
        val profile = TunnelTestEnvironment.profile()
        val inputs = listOf(
            archive(linkedMapOf("new" to profile, "invalid/name" to profile), settings = mapOf("dark_theme" to true)),
            archive(mapOf("new" to profile), settings = mapOf("dark_theme" to true), selection = "absent"),
        )
        for (input in inputs) {
            expectFailure<AppDataTransfer.InvalidArchiveException> { env.manager.importAppData(input) }
            assertTrue(env.store.configs.isEmpty())
            assertTrue(env.manager.getTunnels().isEmpty())
            assertEquals(before.asMap(), preferencesStore().data.first().asMap())
        }
    }

    @Test
    fun equivalentProfileReusesManagedIdentityAndRepeatedImportCreatesNothing() = runTest {
        val env = environment()
        val original = TunnelTestEnvironment.profile()
        val existing = env.manager.create("existing", original)
        existing.setStateAsync(Tunnel.State.UP)
        val reparsed = TunnelConfig.parse(original.toWgQuickString().byteInputStream())
        val input = archive(
            linkedMapOf("existing" to reparsed, "new" to TunnelTestEnvironment.profile(51821)),
            servers = listOf(server("one")),
        )

        assertEquals(1, env.manager.importAppData(input))
        val imported = env.manager.getTunnels()["new"]
        assertEquals(0, env.manager.importAppData(input))

        assertSame(existing, env.manager.getTunnels()["existing"])
        assertSame(imported, env.manager.getTunnels()["new"])
        assertSame(original, env.store.configs["existing"])
        assertEquals(Tunnel.State.UP, existing.state)
        assertEquals(Tunnel.State.DOWN, imported!!.state)
        assertEquals(listOf(server("one")), VpsServerStore(preferencesStore()).servers.first())
        assertTrue(env.store.deleteAttempts.isEmpty())
    }

    @Test
    fun corruptDestinationFleetRejectsImportWithoutReplacingCorruptMetadata() = runTest {
        val env = environment()
        val raw = "{invalid-fleet"
        preferencesStore().edit { it[VpsServerStore.SERVERS] = raw }
        UserKnobs.setRunningTunnels(setOf("new", "orphan"))
        val before = preferencesStore().data.first()

        expectFailure<IllegalArgumentException> {
            env.manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile())))
        }

        assertTrue(env.store.configs.isEmpty())
        assertTrue(env.manager.getTunnels().isEmpty())
        assertEquals(before.asMap(), preferencesStore().data.first().asMap())
    }

    @Test
    fun conflictingFleetRecordRejectsBeforeCreatingProfilesAndPreservesUnrelatedServers() = runTest {
        val env = environment()
        val existing = server("same")
        val unrelated = server("unrelated")
        VpsServerStore(preferencesStore()).upsert(existing)
        VpsServerStore(preferencesStore()).upsert(unrelated)
        UserKnobs.setRunningTunnels(setOf("new", "orphan"))
        val before = preferencesStore().data.first()

        expectFailure<AppDataTransfer.ConflictException> {
            env.manager.importAppData(archive(
                mapOf("new" to TunnelTestEnvironment.profile()),
                servers = listOf(existing.copy(host = "198.51.100.12")),
            ))
        }

        assertTrue(env.store.configs.isEmpty())
        assertTrue(env.manager.getTunnels().isEmpty())
        assertEquals(before.asMap(), preferencesStore().data.first().asMap())
    }

    @Test
    fun successfulFleetMergeRetainsDestinationRecordsAndReusesIdenticalIds() = runTest {
        val env = environment()
        val existing = server("existing")
        val shared = server("shared")
        val imported = server("imported")
        val store = VpsServerStore(preferencesStore())
        store.upsert(existing)
        store.upsert(shared)

        assertEquals(0, env.manager.importAppData(archive(servers = listOf(shared, imported))))

        assertEquals(listOf(existing, shared, imported), store.servers.first())
    }

    @Test
    fun failedSecondCreateRollsBackOnlyNewOwnedProfileAndPreservesMetadata() = runTest {
        val env = environment()
        env.store.configs["kept"] = TunnelTestEnvironment.profile()
        val manager = managerWithStore(env, object : ConfigStore by env.store {
            override fun create(name: String, config: TunnelConfig): TunnelConfig {
                if (name == "second") throw IOException("Injected create failure")
                return env.store.create(name, config)
            }
        })
        UserKnobs.setDarkTheme(false)
        UserKnobs.setLastUsedTunnel("kept")
        val before = preferencesStore().data.first()
        val input = archive(
            linkedMapOf("first" to TunnelTestEnvironment.profile(), "second" to TunnelTestEnvironment.profile()),
            settings = mapOf("dark_theme" to true),
            selection = "first",
            servers = listOf(server("new")),
        )

        expectFailure<IOException> { manager.importAppData(input) }

        assertEquals(setOf("kept"), env.store.configs.keys)
        assertEquals(listOf("first"), env.store.deleteAttempts)
        assertEquals(setOf("kept"), manager.getTunnels().map { it.name }.toSet())
        assertSame(manager.getTunnels()["kept"], manager.lastUsedTunnel)
        assertEquals(before.asMap(), preferencesStore().data.first().asMap())
    }

    @Test
    fun failedMetadataCommitRollsBackProfilesBeforeReportingFailure() = runTest {
        val env = environment()
        env.manager.getTunnels()
        UserKnobs.setDarkTheme(false)
        val dataStore = preferencesStore()
        val before = dataStore.data.first()
        val failure = IOException("Injected metadata commit failure")
        setApplicationField(env, "preferencesDataStore", rejectingCommit(dataStore, failure))

        val observed = expectFailure<IOException> {
            env.manager.importAppData(archive(
                mapOf("new" to TunnelTestEnvironment.profile()),
                settings = mapOf("dark_theme" to true),
                selection = "new",
                servers = listOf(server("new")),
            ))
        }

        assertPreservedFailure(failure, observed)
        assertEquals(listOf("new"), env.store.deleteAttempts)
        assertTrue(env.store.configs.isEmpty())
        assertTrue(env.manager.getTunnels().isEmpty())
        assertEquals(before.asMap(), dataStore.data.first().asMap())
    }

    @Test
    fun failedRollbackPublishesSurvivingProfileDownAndReportsIncompleteRecovery() = runTest {
        val env = environment()
        env.manager.getTunnels()
        env.store.failDeleteNames += "new"
        val dataStore = preferencesStore()
        val before = dataStore.data.first()
        val failure = IOException("Injected metadata commit failure")
        setApplicationField(env, "preferencesDataStore", rejectingCommit(dataStore, failure))

        val observed = expectFailure<AppDataTransfer.IncompleteRecoveryException> {
            env.manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile()), selection = "new"))
        }

        assertOriginalCause(failure, observed)
        assertSuppressedRecovery(failure, observed)
        assertEquals(setOf("new"), env.store.configs.keys)
        val survivor = env.manager.getTunnels()["new"]
        assertNotNull(survivor)
        assertEquals(Tunnel.State.DOWN, survivor!!.state)
        assertEquals(Tunnel.State.DOWN, env.backend.getState(survivor))
        assertTrue(env.backend.appliedConfigs.isEmpty())
        assertEquals(before.asMap(), dataStore.data.first().asMap())
    }

    @Test
    fun failedCommitAndRollbackCannotRestoreStaleActivationMarkerAcrossManagerRestart() = runTest {
        val env = environment()
        env.manager.getTunnels()
        UserKnobs.setRunningTunnels(setOf("new", "orphan"))
        UserKnobs.setDarkTheme(false)
        env.store.failDeleteNames += "new"
        val dataStore = preferencesStore()
        val before = dataStore.data.first()
        val failure = IOException("Injected final metadata commit failure")
        var edits = 0
        val failedFinalCommit = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                dataStore.updateData { current ->
                    edits++
                    val result = transform(current)
                    if (edits == 2) throw failure
                    result
                }
        }
        setApplicationField(env, "preferencesDataStore", failedFinalCommit)

        val observed = expectFailure<AppDataTransfer.IncompleteRecoveryException> {
            env.manager.importAppData(archive(
                mapOf("new" to TunnelTestEnvironment.profile()),
                settings = mapOf("dark_theme" to true),
                selection = "new",
                servers = listOf(server("new")),
            ))
        }

        assertOriginalCause(failure, observed)
        assertSuppressedRecovery(failure, observed)
        assertEquals(2, edits)
        assertEquals(listOf("new"), env.store.deleteAttempts)
        assertEquals(Tunnel.State.DOWN, env.manager.getTunnels()["new"]!!.state)
        val retained = dataStore.data.first()
        assertEquals(setOf("orphan"), retained[UserKnobs.RUNNING_TUNNELS])
        assertEquals(before.asMap().filterKeys { it != UserKnobs.RUNNING_TUNNELS },
            retained.asMap().filterKeys { it != UserKnobs.RUNNING_TUNNELS })

        setApplicationField(env, "preferencesDataStore", dataStore)
        val restarted = managerWithStore(env, env.store)
        restarted.getTunnels()
        loadingJob(restarted).join()
        restarted.restoreState(true)

        assertEquals(Tunnel.State.DOWN, restarted.getTunnels()["new"]!!.state)
        assertTrue(env.backend.getRunningTunnelNames().isEmpty())
        assertTrue(env.backend.appliedConfigs.isEmpty())
        assertEquals(setOf("orphan"), UserKnobs.runningTunnels.first())
    }

    @Test
    fun failedRestoreMarkerPreparationCreatesNoProfilesAndPreservesAllMetadata() = runTest {
        val env = environment()
        var creates = 0
        val manager = managerWithStore(env, object : ConfigStore by env.store {
            override fun create(name: String, config: TunnelConfig): TunnelConfig {
                creates++
                return env.store.create(name, config)
            }
        })
        manager.getTunnels()
        UserKnobs.setRunningTunnels(setOf("new", "orphan"))
        val dataStore = preferencesStore()
        val before = dataStore.data.first()
        val failure = IOException("Injected restore marker commit failure")
        setApplicationField(env, "preferencesDataStore", rejectingCommit(dataStore, failure))

        val observed = expectFailure<IOException> {
            manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile())))
        }

        assertPreservedFailure(failure, observed)
        assertEquals(0, creates)
        assertTrue(env.store.configs.isEmpty())
        assertTrue(env.store.deleteAttempts.isEmpty())
        assertTrue(manager.getTunnels().isEmpty())
        assertEquals(before.asMap(), dataStore.data.first().asMap())
    }

    @Test
    fun metadataProgrammingDefectRemainsOriginalDefectWhenRollbackFails() = runTest {
        val env = environment()
        env.manager.getTunnels()
        env.store.failDeleteNames += "new"
        val dataStore = preferencesStore()
        val before = dataStore.data.first()
        val failure = IllegalStateException("Injected metadata programming defect")
        setApplicationField(env, "preferencesDataStore", rejectingCommit(dataStore, failure))

        val observed = expectFailure<IllegalStateException> {
            env.manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile())))
        }

        assertPreservedFailure(failure, observed)
        assertSuppressedRecovery(failure, observed)
        assertEquals(listOf("new"), env.store.deleteAttempts)
        assertEquals(setOf("new"), env.store.configs.keys)
        assertEquals(Tunnel.State.DOWN, env.manager.getTunnels()["new"]!!.state)
        assertEquals(before.asMap(), dataStore.data.first().asMap())
    }

    @Test
    fun metadataErrorRemainsOriginalErrorEvenWhenRollbackFails() = runTest {
        val env = environment()
        env.manager.getTunnels()
        env.store.failDeleteNames += "new"
        val failure = AssertionError("Injected metadata defect")
        setApplicationField(env, "preferencesDataStore", rejectingCommit(preferencesStore(), failure))

        val observed = expectFailure<AssertionError> {
            env.manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile())))
        }

        assertPreservedFailure(failure, observed)
        assertEquals(listOf("new"), env.store.deleteAttempts)
        assertSuppressedRecovery(failure, observed)
        assertEquals(setOf("new"), env.store.configs.keys)
        assertEquals(Tunnel.State.DOWN, env.manager.getTunnels()["new"]!!.state)
    }

    @Test
    fun cancellationWhileWaitingForAdmissionWritesNoTransferData() = runTest {
        val env = environment()
        val blocker = env.manager.create("blocker", TunnelTestEnvironment.profile())
        env.backend.blockUp = true
        val activation = async { blocker.setStateAsync(Tunnel.State.UP) }
        try {
            assertTrue(withContext(Dispatchers.IO) { env.backend.upEntered.await(5, TimeUnit.SECONDS) })
            val before = preferencesStore().data.first()
            val importing = launch {
                env.manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile()), settings = mapOf("dark_theme" to true)))
            }
            runCurrent()
            assertFalse(importing.isCompleted)
            importing.cancel()
            importing.join()

            assertTrue(importing.isCancelled)
            assertEquals(setOf("blocker"), env.store.configs.keys)
            assertEquals(before.asMap(), preferencesStore().data.first().asMap())
            env.backend.releaseUp.countDown()
            activation.await()
        } finally {
            env.backend.releaseUp.countDown()
        }
    }

    @Test
    fun cancellationAfterAdmissionFinishesCommitAndSettlesModelBeforeReleasingCaller() = runTest {
        val env = environment()
        val createEntered = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        val manager = managerWithStore(env, object : ConfigStore by env.store {
            override fun create(name: String, config: TunnelConfig): TunnelConfig {
                createEntered.countDown()
                if (!releaseCreate.await(5, TimeUnit.SECONDS)) throw IOException("Create fixture timed out")
                return env.store.create(name, config)
            }
        })
        val input = archive(mapOf("new" to TunnelTestEnvironment.profile()), settings = mapOf("dark_theme" to true), selection = "new")
        val importing = launch { manager.importAppData(input) }
        try {
            assertTrue(withContext(Dispatchers.IO) { createEntered.await(5, TimeUnit.SECONDS) })
            importing.cancel()
            runCurrent()
            assertFalse(importing.isCompleted)
            releaseCreate.countDown()
            importing.join()

            assertTrue(importing.isCancelled)
            assertEquals(setOf("new"), env.store.configs.keys)
            assertEquals(Tunnel.State.DOWN, manager.getTunnels()["new"]!!.state)
            assertSame(manager.getTunnels()["new"], manager.lastUsedTunnel)
            assertEquals("new", UserKnobs.lastUsedTunnel.first())
            assertTrue(UserKnobs.darkTheme.first())
            assertEquals(0, manager.importAppData(input))
        } finally {
            releaseCreate.countDown()
        }
    }

    @Test
    fun importClearsOnlyNewProfileRestoreMarkersAndPreservesExistingActiveTunnel() = runTest {
        val env = environment()
        val existing = env.manager.create("existing", TunnelTestEnvironment.profile())
        existing.setStateAsync(Tunnel.State.UP)
        UserKnobs.setRunningTunnels(setOf("existing", "new", "orphan"))

        env.manager.importAppData(archive(
            mapOf("new" to TunnelTestEnvironment.profile()),
            settings = mapOf("restore_on_boot" to true),
            selection = "new",
        ))

        assertEquals(setOf("existing", "orphan"), UserKnobs.runningTunnels.first())
        assertEquals(Tunnel.State.UP, existing.state)
        assertEquals(setOf("existing"), env.backend.getRunningTunnelNames())
        assertEquals(Tunnel.State.DOWN, env.manager.getTunnels()["new"]!!.state)
        assertSame(env.manager.getTunnels()["new"], env.manager.lastUsedTunnel)
        assertEquals(setOf(existing), env.backend.appliedConfigs.keys)
    }

    @Test
    fun startupRestoreSnapshotCannotActivateProfileImportedDuringSavedStateRead() = runTest {
        val env = environment()
        val dataStore = preferencesStore()
        UserKnobs.setRunningTunnels(setOf("new", "orphan"))
        val readingRestore = CompletableDeferred<Unit>()
        val finishRestoreRead = CompletableDeferred<Unit>()
        val delayedStore = object : DataStore<Preferences> by dataStore {
            private var reads = 0
            override val data: Flow<Preferences> = flow {
                val snapshot = dataStore.data.first()
                reads++
                if (reads == 2) {
                    readingRestore.complete(Unit)
                    finishRestoreRead.await()
                }
                emit(snapshot)
            }
        }
        setApplicationField(env, "preferencesDataStore", delayedStore)
        env.manager.getTunnels()
        readingRestore.await()
        try {
            assertEquals(1, env.manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile()))))
            finishRestoreRead.complete(Unit)
            loadingJob(env.manager).join()

            assertEquals(Tunnel.State.DOWN, env.manager.getTunnels()["new"]!!.state)
            assertTrue(env.backend.getRunningTunnelNames().isEmpty())
            assertTrue(env.backend.appliedConfigs.isEmpty())
            assertEquals(setOf("orphan"), UserKnobs.runningTunnels.first())
        } finally {
            finishRestoreRead.complete(Unit)
        }
    }

    @Test
    fun importedSelectionReconcilesExistingCachedSelectionAndPersistsThroughNextExport() = runTest {
        val env = environment()
        val old = env.manager.create("old", TunnelTestEnvironment.profile())
        old.setStateAsync(Tunnel.State.UP)
        assertSame(old, env.manager.lastUsedTunnel)

        env.manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile()), selection = "new"))

        assertSame(env.manager.getTunnels()["new"], env.manager.lastUsedTunnel)
        assertEquals("new", UserKnobs.lastUsedTunnel.first())
        assertEquals("new", env.manager.exportAppData().lastUsedTunnel)
        assertEquals(Tunnel.State.UP, old.state)
        assertEquals(Tunnel.State.DOWN, env.manager.lastUsedTunnel!!.state)
    }

    @Test
    fun importWaitsForEarlierSelectionWritesBeforeCommittingImportedSelection() = runTest {
        val env = environment()
        val old = env.manager.create("old", TunnelTestEnvironment.profile())
        old.setStateAsync(Tunnel.State.UP)
        old.setStateAsync(Tunnel.State.DOWN)
        env.manager.exportAppData()
        val dataStore = preferencesStore()
        val writingSelection = CompletableDeferred<Unit>()
        val finishSelectionWrite = CompletableDeferred<Unit>()
        val delayedStore = object : DataStore<Preferences> by dataStore {
            private var writes = 0
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes++
                if (writes == 1) {
                    writingSelection.complete(Unit)
                    finishSelectionWrite.await()
                }
                return dataStore.updateData(transform)
            }
        }
        setApplicationField(env, "preferencesDataStore", delayedStore)
        old.setNameAsync("renamed")
        writingSelection.await()
        val importing = async {
            env.manager.importAppData(archive(mapOf("new" to TunnelTestEnvironment.profile()), selection = "new"))
        }
        try {
            runCurrent()
            assertFalse(importing.isCompleted)
            assertEquals(setOf("renamed"), env.store.configs.keys)
            finishSelectionWrite.complete(Unit)
            assertEquals(1, importing.await())
            runCurrent()

            assertEquals("new", UserKnobs.lastUsedTunnel.first())
            assertSame(env.manager.getTunnels()["new"], env.manager.lastUsedTunnel)
            assertEquals("new", env.manager.exportAppData().lastUsedTunnel)
        } finally {
            finishSelectionWrite.complete(Unit)
        }
    }

    private fun TestScope.environment(directory: File = folder.root): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(directory, backgroundScope)
    }

    private fun preferencesStore() = com.vpn0wn.android.Application.getPreferencesDataStore()

    private fun setApplicationField(env: TunnelTestEnvironment, name: String, value: Any) {
        com.vpn0wn.android.Application::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(env.application, value)
    }

    private fun managerWithStore(env: TunnelTestEnvironment, store: ConfigStore): TunnelManager =
        TunnelManager(store).also { setApplicationField(env, "tunnelManager", it) }

    private fun loadingJob(manager: TunnelManager): Job =
        TunnelManager::class.java.getDeclaredField("loadingJob").apply { isAccessible = true }.get(manager) as Job

    private fun rejectingCommit(delegate: DataStore<Preferences>, failure: Throwable): DataStore<Preferences> =
        object : DataStore<Preferences> by delegate {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                delegate.updateData { current ->
                    transform(current)
                    throw failure
                }
        }

    private fun assertPreservedFailure(expected: Throwable, observed: Throwable) {
        assertEquals(expected.javaClass, observed.javaClass)
        assertEquals(expected.message, observed.message)
        assertOriginalCause(expected, observed)
    }

    private fun assertOriginalCause(expected: Throwable, observed: Throwable) {
        assertTrue("Original failure must remain in the cause chain",
            generateSequence(observed) { it.cause }.any { it === expected })
    }

    private fun assertSuppressedRecovery(expected: Throwable, observed: Throwable) {
        val primary = generateSequence(observed) { it.cause }.firstOrNull {
            it.javaClass == expected.javaClass && it.message == expected.message && it.suppressed.isNotEmpty()
        }
        assertNotNull("Primary failure must retain rollback failure", primary)
        val recovery = primary!!.suppressed.single()
        assertEquals(IOException::class.java, recovery.javaClass)
        assertEquals("Injected delete failure", recovery.message)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        fail("Expected ${T::class.java.simpleName}")
        error("Expected failure")
    }

    private fun archive(
        profiles: Map<String, TunnelConfig> = emptyMap(),
        settings: Map<String, Boolean> = emptyMap(),
        selection: String? = null,
        servers: List<StoredVpsServer> = emptyList(),
    ) = AppDataTransfer.Archive(profiles, settings, selection, servers)

    private fun server(id: String) = StoredVpsServer(
        id = id,
        name = "Fixture $id",
        host = "198.51.100.10",
        sshPort = 2222,
        sshUser = "fixture",
        endpoint = "203.0.113.10",
        listenPort = "51820",
        cidr = "192.0.2.0/24",
        dns = "192.0.2.53",
        os = "Debian 12",
        version = "fixture",
        provider = "fixture-provider",
        region = "fixture-region",
        engine = "kernel",
        lastSyncEpochSeconds = 123456,
        running = true,
    )
}
