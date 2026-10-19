/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import android.app.Application
import com.vpn0wn.android.util.UserKnobs
import com.vpn0wn.android.util.createPreferencesDataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import com.vpn0wn.android.Application as VpnApplication

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NetworkAutomationDisablePersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun resetMain() = Dispatchers.resetMain()

    @Test fun crashAfterOptOutCannotReplayPendingOrFailedAutomationOrigins() = verifyFirstCommitAfterReopen(
        NetworkAutomationState(enabled = true, episode = 1, defaultName = "default", resolved = false, serial = 2,
            manual = receipt("pending", "UP", 2, NetworkAutomationState.Outcome.PENDING),
            attempt = receipt("default", "UP", 1, NetworkAutomationState.Outcome.FAILED)),
        setOf("default", "pending", "unrelated"), emptySet(), setOf("unrelated"),
    )

    @Test fun completedUpWithoutCurrentLiveProofCannotBecomeOrdinaryRestoreAuthority() = verifyFirstCommitAfterReopen(
        NetworkAutomationState(enabled = true, episode = 1, defaultName = "held", resolved = true, serial = 1,
            manual = receipt("held", "UP", 1, NetworkAutomationState.Outcome.UP)),
        setOf("held", "unrelated"), emptySet(), setOf("unrelated"),
    )

    @Test fun failedNewerDownCannotBeRehabilitatedByOldReadyVpn() = verifyFirstCommitAfterReopen(
        NetworkAutomationState(enabled = true, episode = 1, defaultName = "held", resolved = true, serial = 2,
            manual = receipt("held", "DOWN", 2, NetworkAutomationState.Outcome.FAILED),
            attempt = receipt("held", "UP", 1, NetworkAutomationState.Outcome.UP)),
        setOf("held", "unrelated"), setOf("held"), setOf("unrelated"),
    )

    @Test fun stillResolvedSuccessfulLiveManualOwnerSurvivesUnusedDefaultFence() = verifyFirstCommitAfterReopen(
        NetworkAutomationState(enabled = true, episode = 1, defaultName = "default", resolved = false, serial = 1,
            manual = receipt("held", "UP", 1, NetworkAutomationState.Outcome.UP)),
        setOf("default", "held", "unrelated"), setOf("held"), setOf("held", "unrelated"),
    )

    private fun receipt(name: String, target: String, serial: Long, outcome: NetworkAutomationState.Outcome) =
        NetworkAutomationState.Receipt(name, target, target, 0, 0, 0, serial, outcome)

    private fun verifyFirstCommitAfterReopen(state: NetworkAutomationState, running: Set<String>,
        liveReadyNames: Set<String>, expected: Set<String>) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val env = TunnelTestEnvironment(folder.root, backgroundScope)
        val reopenedOwner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            NetworkAutomationState.update { state }
            UserKnobs.setRunningTunnels(running)
            NetworkAutomationState.disable(liveReadyNames)
            val firstCommit = VpnApplication.getPreferencesDataStore().data.first()
            assertEquals(expected, firstCommit[UserKnobs.RUNNING_TUNNELS].orEmpty())
            val disabled = NetworkAutomationState.read(firstCommit)
            assertFalse(disabled.enabled)
            assertNull(disabled.manual)
            assertNull(disabled.attempt)
            assertEquals(NetworkAutomationState.Exit.PENDING, disabled.exit)
            env.close()
            val reopened = createPreferencesDataStore(File(folder.root, "settings.preferences_pb"), scope = reopenedOwner).data.first()
            assertEquals(disabled, NetworkAutomationState.read(reopened))
            assertEquals(expected, reopened[UserKnobs.RUNNING_TUNNELS].orEmpty())
        } finally {
            env.close()
            reopenedOwner.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
