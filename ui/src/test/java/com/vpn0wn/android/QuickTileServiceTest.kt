package com.vpn0wn.android

import android.app.Application
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.util.UserKnobs
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], shadows = [QuickTileServiceTest.TileLifecycle::class])
@OptIn(ExperimentalCoroutinesApi::class)
class QuickTileServiceTest {
    @get:Rule val folder = TemporaryFolder()
    private val controllers = mutableListOf<ServiceController<QuickTileService>>()
    private val environments = mutableListOf<TunnelTestEnvironment>()
    private val membershipKey = booleanPreferencesKey("quick_tile_added")
    private var membershipPhase: String? = null
    private var membershipDetails: (() -> String)? = null
    @get:Rule val membershipFailure = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val phase = membershipPhase ?: return
            failure.addSuppressed(AssertionError(
                "Quick tile service phase: $phase\n${membershipDetails?.invoke().orEmpty()}"
            ))
        }
    }

    @Test fun serviceAndListeningLifetimeNeverConfirmUnknownMembership() = tileTest {
        environment()
        val controller = createService()
        controller.get().onStartListening()
        controller.get().onStopListening()
        destroyService(controller)
        assertNull(UserKnobs.quickTileAdded.first())
    }

    @Test fun addNotificationSurvivesListeningAndServiceRecreationUntilActualRemoval() = tileTest {
        val env = environment()
        val commits = observeMembershipTransactions(env)
        val first = createService()
        membershipPhase = "initial add"
        first.get().onTileAdded()
        assertCommitted(commits, true)
        membershipPhase = "service recreation"
        first.get().onStartListening()
        first.get().onStopListening()
        destroyService(first)
        assertEquals(true, UserKnobs.quickTileAdded.first())
        val replacement = createService()
        replacement.get().onStartListening()
        replacement.get().onStopListening()
        assertEquals(true, UserKnobs.quickTileAdded.first())
        membershipPhase = "first removal"
        replacement.get().onTileRemoved()
        assertCommitted(commits, false)
        membershipPhase = "replacement add"
        replacement.get().onTileAdded()
        assertCommitted(commits, true)
        membershipPhase = "final removal"
        replacement.get().onTileRemoved()
        assertCommitted(commits, false)
        membershipPhase = "final service teardown"
        destroyService(replacement)
        assertEquals(false, UserKnobs.quickTileAdded.first())
    }

    @Test fun committedConfirmationAndMissingStateSurviveApplicationAndStoreRecreation() = tileTest {
        for ((index, confirmation) in listOf(true, false, null).withIndex()) {
            val directory = folder.newFolder("state-$index")
            val original = environment(directory)
            if (confirmation != null) UserKnobs.setQuickTileAdded(confirmation)
            assertEquals(confirmation, UserKnobs.quickTileAdded.first())
            original.close()
            environment(directory)
            assertEquals(confirmation, UserKnobs.quickTileAdded.first())
            assertEquals(emptySet<String>(), UserKnobs.runningTunnels.first())
        }
    }

    @Test fun removeQueuedDuringAddPersistenceWinsAfterBothNotificationsSettle() = tileTest {
        val env = environment()
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
        assertNull(UserKnobs.quickTileAdded.first())
        val writingAdd = CompletableDeferred<Unit>()
        val finishAdd = CompletableDeferred<Unit>()
        val delayedStore = object : DataStore<Preferences> by dataStore {
            private var writes = 0
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                dataStore.updateData { current ->
                    writes++
                    if (writes == 1) {
                        writingAdd.complete(Unit)
                        finishAdd.await()
                    }
                    val next = transform(current)
                    if (writes == 1) assertEquals(current, next)
                    next
                }
        }
        setDataStore(env, delayedStore)
        val commits = observeMembershipTransactions(env)
        val service = createService().get()
        val removalRevision = UserKnobs.quickTileRemovalRevision
        membershipPhase = "held add"
        service.onTileAdded()
        runCurrent()
        writingAdd.await()
        val addCommit = commits.receive()
        assertFalse(addCommit.isCompleted)
        service.onTileRemoved()
        assertEquals(removalRevision + 1, UserKnobs.quickTileRemovalRevision)
        assertNull(UserKnobs.quickTileAdded.first())
        finishAdd.complete(Unit)
        membershipPhase = "invalidated add commit"
        assertNull(addCommit.await()[membershipKey])
        membershipPhase = "queued removal"
        assertCommitted(commits, false)
    }

    @Test fun failedNotificationPersistenceDoesNotInventAnAbsentConfirmation() = tileTest {
        val env = environment()
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
        var attempts = 0
        val unavailableStore = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                attempts++
                throw IOException("fixture unavailable")
            }
        }
        setDataStore(env, unavailableStore)
        val commits = observeMembershipTransactions(env)
        val service = createService().get()
        membershipPhase = "failed add and removal"
        service.onTileAdded()
        service.onTileRemoved()
        runCurrent()
        assertEquals(2, attempts)
        repeat(2) {
            val failure = runCatching { commits.receive().await() }.exceptionOrNull()
            assertTrue(failure is IOException)
        }
        assertNull(UserKnobs.quickTileAdded.first())
    }

    private fun tileTest(body: suspend TestScope.() -> Unit) = runTest {
        val result = runCatching { body() }
        if (result.isFailure && membershipPhase != null) {
            val details = membershipDetails?.invoke().orEmpty()
            membershipDetails = { details }
        }
        val cleanup = runCatching { closeFixtures() }
        Dispatchers.resetMain()
        val failure = result.exceptionOrNull()
        if (failure != null) {
            cleanup.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
        cleanup.getOrThrow()
    }

    private suspend fun closeFixtures() = withContext(NonCancellable) {
        val destruction = controllers.map { runCatching { it.destroy() } }
        controllers.clear()
        val closing = environments.map { runCatching { it.close() } }
        environments.clear()
        (destruction + closing).forEach { it.getOrThrow() }
    }

    private fun observeMembershipTransactions(env: TunnelTestEnvironment): ReceiveChannel<CompletableDeferred<Preferences>> {
        val dataStore = com.vpn0wn.android.Application.getPreferencesDataStore()
        val commits = Channel<CompletableDeferred<Preferences>>(Channel.UNLIMITED)
        val writes = AtomicInteger()
        val stages = StringBuffer("not entered")
        val observingStore = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val operation = writes.incrementAndGet()
                val committed = CompletableDeferred<Preferences>()
                commits.trySend(committed).getOrThrow()
                stages.append("; update=$operation entered")
                try {
                    return dataStore.updateData { previous ->
                        stages.append("; update=$operation transform revision=${UserKnobs.quickTileRemovalRevision}")
                        transform(previous)
                    }.also {
                        stages.append("; update=$operation committed=${it[membershipKey]}")
                        committed.complete(it)
                    }
                } catch (failure: Throwable) {
                    stages.append("; update=$operation failed=${failure.javaClass.name}")
                    committed.completeExceptionally(failure)
                    throw failure
                }
            }
        }
        env.replacePreferences(observingStore)
        membershipDetails = {
            "appMatches=${com.vpn0wn.android.Application.get() === env.application} " +
                "scopeActive=${env.applicationScope.coroutineContext[Job]?.isActive} " +
                "storeMatches=${com.vpn0wn.android.Application.getPreferencesDataStore() === observingStore} " +
                "revision=${UserKnobs.quickTileRemovalRevision} writes=${writes.get()} stages=[$stages]"
        }
        return commits
    }

    private suspend fun TestScope.assertCommitted(commits: ReceiveChannel<CompletableDeferred<Preferences>>, expected: Boolean) {
        val phase = membershipPhase
        membershipPhase = "$phase transaction"
        runCurrent()
        assertEquals(expected, commits.receive().await()[membershipKey])
        membershipPhase = "$phase persisted readback"
        assertEquals(expected, UserKnobs.quickTileAdded.first())
    }

    private fun TestScope.environment(directory: File = folder.root): TunnelTestEnvironment {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return TunnelTestEnvironment(directory, backgroundScope).also { environments += it }
    }

    private fun createService(): ServiceController<QuickTileService> =
        Robolectric.buildService(QuickTileService::class.java).create().also { controllers += it }

    private fun destroyService(controller: ServiceController<QuickTileService>) {
        controller.destroy()
        controllers.remove(controller)
    }

    private fun setDataStore(env: TunnelTestEnvironment, dataStore: DataStore<Preferences>) {
        com.vpn0wn.android.Application::class.java.getDeclaredField("preferencesDataStore")
            .apply { isAccessible = true }.set(env.application, dataStore)
    }

    // Robolectric 4.16's tile shadow lacks the Service parent required by Service.onDestroy.
    @Implements(TileService::class)
    class TileLifecycle : ShadowService() {
        private val tile: Tile by lazy { Shadow.newInstanceOf(Tile::class.java) }

        @Implementation fun getQsTile(): Tile = tile
    }
}
