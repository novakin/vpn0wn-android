package com.wireguard.android

import android.app.Application
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.wireguard.android.model.TunnelTestEnvironment
import com.wireguard.android.util.UserKnobs
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

    @After fun tearDown() {
        controllers.forEach { it.destroy() }
        environments.forEach { it.applicationScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun serviceAndListeningLifetimeNeverConfirmUnknownMembership() = runTest {
        environment()
        val controller = createService()
        controller.get().onStartListening()
        controller.get().onStopListening()
        destroyService(controller)
        assertNull(UserKnobs.quickTileAdded.first())
    }

    @Test fun addNotificationSurvivesListeningAndServiceRecreationUntilActualRemoval() = runTest {
        environment()
        val first = createService()
        first.get().onTileAdded()
        assertEquals(true, UserKnobs.quickTileAdded.first { it == true })
        first.get().onStartListening()
        first.get().onStopListening()
        destroyService(first)
        assertEquals(true, UserKnobs.quickTileAdded.first())
        val replacement = createService()
        replacement.get().onStartListening()
        replacement.get().onStopListening()
        assertEquals(true, UserKnobs.quickTileAdded.first())
        replacement.get().onTileRemoved()
        assertEquals(false, UserKnobs.quickTileAdded.first { it == false })
        replacement.get().onTileAdded()
        assertEquals(true, UserKnobs.quickTileAdded.first { it == true })
        replacement.get().onTileRemoved()
        assertEquals(false, UserKnobs.quickTileAdded.first { it == false })
        destroyService(replacement)
        assertEquals(false, UserKnobs.quickTileAdded.first())
    }

    @Test fun committedConfirmationAndMissingStateSurviveApplicationAndStoreRecreation() = runTest {
        for ((index, confirmation) in listOf(true, false, null).withIndex()) {
            val directory = folder.newFolder("state-$index")
            val original = environment(directory)
            if (confirmation != null) UserKnobs.setQuickTileAdded(confirmation)
            assertEquals(confirmation, UserKnobs.quickTileAdded.first())
            original.applicationScope.cancel()
            original.applicationScope.coroutineContext[Job]!!.join()
            environment(directory)
            assertEquals(confirmation, UserKnobs.quickTileAdded.first())
            assertEquals(emptySet<String>(), UserKnobs.runningTunnels.first())
        }
    }

    @Test fun removeQueuedDuringAddPersistenceWinsAfterBothNotificationsSettle() = runTest {
        val env = environment()
        val dataStore = com.wireguard.android.Application.getPreferencesDataStore()
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
                    transform(current)
                }
        }
        setDataStore(env, delayedStore)
        val service = createService().get()
        val removalRevision = UserKnobs.quickTileRemovalRevision
        service.onTileAdded()
        service.onTileRemoved()
        assertEquals(removalRevision + 1, UserKnobs.quickTileRemovalRevision)
        runCurrent()
        writingAdd.await()
        assertNull(UserKnobs.quickTileAdded.first())
        finishAdd.complete(Unit)
        assertEquals(false, UserKnobs.quickTileAdded.first { it == false })
    }

    @Test fun failedNotificationPersistenceDoesNotInventAnAbsentConfirmation() = runTest {
        val env = environment()
        val dataStore = com.wireguard.android.Application.getPreferencesDataStore()
        var attempts = 0
        val unavailableStore = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                attempts++
                throw IOException("fixture unavailable")
            }
        }
        setDataStore(env, unavailableStore)
        val service = createService().get()
        service.onTileAdded()
        service.onTileRemoved()
        runCurrent()
        assertEquals(2, attempts)
        assertNull(UserKnobs.quickTileAdded.first())
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
        com.wireguard.android.Application::class.java.getDeclaredField("preferencesDataStore")
            .apply { isAccessible = true }.set(env.application, dataStore)
    }

    // Robolectric 4.16's tile shadow lacks the Service parent required by Service.onDestroy.
    @Implements(TileService::class)
    class TileLifecycle : ShadowService() {
        private val tile: Tile by lazy { Shadow.newInstanceOf(Tile::class.java) }

        @Implementation fun getQsTile(): Tile = tile
    }
}
