/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.AppDataTransferSettingsTest
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsCredential
import com.vpn0wn.android.provisioning.VpsCredentialFileTest
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsUserProfileFileTest
import com.vpn0wn.android.util.BiometricAuthenticator
import com.vpn0wn.config.Config as TunnelConfig
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [VpsCredentialFileTest.NativeFiles::class,
    AppDataTransferSettingsTest.Authentication::class])
@OptIn(ExperimentalCoroutinesApi::class)
class VpsLocalEndpointWorkflowTest {
    @get:Rule val folder = TemporaryFolder()

    @Before fun authentication() {
        AppDataTransferSettingsTest.Authentication.calls = 0
        AppDataTransferSettingsTest.Authentication.autoCancel = false
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_local_endpoint_authenticate
        AppDataTransferSettingsTest.Authentication.callbacks.clear()
        VpsUserProfileFileTest.resetNativeFiles()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun actualReviewListsEveryTunnelAndCancelRetiresItsPositiveAction() = runTest {
        val fixture = fixture()
        try {
            prepare(fixture)
            val manager = Vpn0wnApplication.getTunnelManager()
            val previous = VpsProfileFixture.profile().config
            val tunnel = manager.create("phone-local", previous)
            manager.create("ineligible", TunnelTestEnvironment.profile())
            openChooser(fixture)
            val chooser = requireNotNull(dialog(fixture))
            assertEquals(setOf("ineligible", "phone-local"), (0 until chooser.listView.adapter.count)
                .map { chooser.listView.adapter.getItem(it).toString() }.toSet())
            choose(fixture, "phone-local")
            val review = requireNotNull(dialog(fixture))
            val copy = texts(review.window!!.decorView).joinToString("\n")
            for (value in listOf("phone-local", SERVER_KEY, CLIENT_KEY, OLD_ENDPOINT, NEW_ENDPOINT)) assertTrue(copy.contains(value))
            assertFalse(copy.contains(VpsProfileFixture.client.privateKey.toBase64()))
            assertFalse(copy.contains(VpsProfileFixture.psk.toBase64()))
            assertEquals(0, AppDataTransferSettingsTest.Authentication.calls)
            assertSame(previous, tunnel.peekConfig())
            val stale = review.getButton(DialogInterface.BUTTON_POSITIVE)
            review.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            stale.performClick()
            settle()
            assertNull(dialog(fixture))
            assertEquals(0, AppDataTransferSettingsTest.Authentication.calls)
            assertSame(previous, tunnel.peekConfig())
            assertEquals(2, statusCommands(fixture))
        } finally { fixture.close() }
    }

    @Test fun acceptedAuthenticationRechecksAndUpdatesOnlyOneLocalTunnelWithoutVaultAccess() = runTest {
        val fixture = fixture()
        try {
            prepare(fixture)
            val manager = Vpn0wnApplication.getTunnelManager()
            val previous = VpsProfileFixture.profile().config
            val tunnel = manager.create("phone-local", previous)
            val other = manager.create("other", previous)
            review(fixture)
            confirm(fixture)
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.single()
            assertEquals(2, statusCommands(fixture))
            callback(BiometricAuthenticator.Result.Success(null))
            finish(fixture)
            assertEquals(NEW_ENDPOINT, tunnel.getConfigAsync().peers.single().endpoint.get().toString())
            assertSame(previous, other.peekConfig())
            assertEquals(Tunnel.State.DOWN, tunnel.state)
            assertEquals(3, statusCommands(fixture))
            assertNull(dialog(fixture))
            callback(BiometricAuthenticator.Result.Success(null))
            callback(BiometricAuthenticator.Result.Cancelled)
            finish(fixture)
            assertEquals(3, statusCommands(fixture))
            assertEquals(1, AppDataTransferSettingsTest.Authentication.calls)
            assertFalse(fixture.peer.commands.any { it.contains("'add-peer.sh'") || it.contains("'backup.sh'") })
        } finally { fixture.close() }
    }

    @Test fun noConfiguredAuthenticationAdmitsAnUnchangedEndpointWithoutWrites() = runTest {
        val fixture = fixture()
        try {
            prepare(fixture)
            val previous = TunnelConfig.parse(VpsProfileFixture.text().replace(OLD_ENDPOINT, NEW_ENDPOINT).byteInputStream())
            val tunnel = Vpn0wnApplication.getTunnelManager().create("phone-local", previous)
            val environment = environment(fixture)
            environment.store.failSave = true
            environment.backend.failDown = true
            review(fixture)
            confirm(fixture)
            AppDataTransferSettingsTest.Authentication.callbacks.single()(BiometricAuthenticator.Result.NoAuthenticationConfigured)
            finish(fixture)
            assertSame(previous, tunnel.peekConfig())
            assertSame(previous, environment.store.configs["phone-local"])
            assertNull(environment.backend.appliedConfigs[tunnel])
            assertEquals(fixture.activity.getString(R.string.vpn0wn_local_endpoint_unchanged), feedback(fixture))
        } finally { fixture.close() }
    }

    @Test fun terminalAuthenticationFailureCannotBeReplayedAsSuccess() = runTest {
        val fixture = fixture()
        try {
            prepare(fixture)
            val previous = VpsProfileFixture.profile().config
            val tunnel = Vpn0wnApplication.getTunnelManager().create("phone-local", previous)
            review(fixture)
            confirm(fixture)
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.single()
            callback(BiometricAuthenticator.Result.Failure(1, "Synthetic terminal failure"))
            callback(BiometricAuthenticator.Result.Success(null))
            finish(fixture)
            assertEquals(2, statusCommands(fixture))
            assertSame(previous, tunnel.peekConfig())
            assertNull(dialog(fixture))
        } finally { fixture.close() }
    }

    @Test fun changedRemoteEndpointAfterReviewCannotBeApplied() = runTest {
        val fixture = fixture()
        try {
            prepare(fixture)
            val previous = VpsProfileFixture.profile().config
            val tunnel = Vpn0wnApplication.getTunnelManager().create("phone-local", previous)
            review(fixture)
            confirm(fixture)
            fixture.peer.statusOutput = JSONObject(requireNotNull(fixture.peer.statusOutput)).put("endpoint", "203.0.113.99").toString()
            AppDataTransferSettingsTest.Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
            finish(fixture)
            assertSame(previous, tunnel.peekConfig())
            assertEquals(fixture.activity.getString(R.string.vpn0wn_local_endpoint_changed), feedback(fixture))
            assertEquals(3, statusCommands(fixture))
        } finally { fixture.close() }
    }

    @Test fun changedPinAfterReviewIsRejectedBeforeAnotherRemoteRead() = runTest {
        val fixture = fixture()
        try {
            val stored = prepare(fixture)
            val previous = VpsProfileFixture.profile().config
            val tunnel = Vpn0wnApplication.getTunnelManager().create("phone-local", previous)
            review(fixture)
            confirm(fixture)
            Vpn0wnApplication.getVpsServerStore().saveCredential(stored, fingerprint(1), VpsCredential.Password("synthetic-new".toCharArray()))
            AppDataTransferSettingsTest.Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
            finish(fixture)
            assertSame(previous, tunnel.peekConfig())
            assertEquals(2, statusCommands(fixture))
            assertNull(dialog(fixture))
        } finally { fixture.close() }
    }

    @Test fun stoppedViewRetiresAnOwnedRemoteCheckWithoutLocalWrites() = runTest {
        val fixture = fixture()
        try {
            prepare(fixture)
            val previous = VpsProfileFixture.profile().config
            val tunnel = Vpn0wnApplication.getTunnelManager().create("phone-local", previous)
            review(fixture)
            confirm(fixture)
            fixture.peer.statusEntered = CompletableDeferred()
            fixture.peer.blockStatus = CompletableDeferred()
            AppDataTransferSettingsTest.Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
            settle()
            fixture.peer.statusEntered.await()
            fixture.controller.pause().stop()
            fixture.peer.blockStatus?.complete(Unit)
            finish(fixture)
            assertSame(previous, tunnel.peekConfig())
            assertNull(dialog(fixture))
            assertNull(field(fixture, "endpointAction"))
        } finally { fixture.peer.blockStatus?.complete(Unit); fixture.close() }
    }

    @Test fun destroyedViewRejectsItsCapturedAuthenticationCallback() = runTest {
        val fixture = fixture()
        try {
            prepare(fixture)
            val previous = VpsProfileFixture.profile().config
            val tunnel = Vpn0wnApplication.getTunnelManager().create("phone-local", previous)
            review(fixture)
            confirm(fixture)
            val callback = AppDataTransferSettingsTest.Authentication.callbacks.single()
            fixture.activity.supportFragmentManager.beginTransaction().remove(fixture.fragment).commitNow()
            callback(BiometricAuthenticator.Result.Success(null))
            settle()
            assertSame(previous, tunnel.peekConfig())
            assertEquals(2, statusCommands(fixture))
            assertNull(field(fixture, "endpointAction"))
        } finally { fixture.close() }
    }

    @Test fun pendingRestartAndCheckedBackendFailureReportSafeRefusals() = runTest {
        for (restart in listOf(true, false)) {
            AppDataTransferSettingsTest.Authentication.callbacks.clear()
            val fixture = fixture()
            try {
                prepare(fixture)
                val previous = VpsProfileFixture.profile().config
                val tunnel = Vpn0wnApplication.getTunnelManager().create("phone-local", previous)
                review(fixture)
                confirm(fixture)
                val environment = environment(fixture)
                if (restart) environment.manager.prepareBackendSwitch(true)
                else environment.replaceBackend(object : Backend by environment.backend {
                    override fun getState(tunnel: Tunnel): Tunnel.State = throw CheckedObservationFailure()
                })
                AppDataTransferSettingsTest.Authentication.callbacks.single()(BiometricAuthenticator.Result.Success(null))
                finish(fixture)
                assertSame(previous, tunnel.peekConfig())
                assertSame(previous, environment.store.configs["phone-local"])
                assertNull(environment.backend.appliedConfigs[tunnel])
                assertEquals(fixture.activity.getString(if (restart) R.string.vpn0wn_local_endpoint_restart else R.string.vpn0wn_local_endpoint_failed), feedback(fixture))
            } finally { fixture.close() }
        }
    }

    private suspend fun TestScope.fixture(): VpsProvisioningLayoutFixture {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return provisioningLayoutFixture(folder, View.LAYOUT_DIRECTION_LTR)
    }

    private suspend fun TestScope.prepare(fixture: VpsProvisioningLayoutFixture): StoredVpsServer {
        val store = Vpn0wnApplication.getVpsServerStore()
        store.upsert(VpsProfileFixture.server)
        store.saveCredential(VpsProfileFixture.server, fingerprint(0), VpsCredential.Password("synthetic-endpoint".toCharArray()))
        fixture.peer.statusOutput = JSONObject().put("ok", true).put("interface", "wg0").put("running", true)
            .put("endpoint", "203.0.113.55").put("listenPort", "51821").put("cidr", "10.8.0.0/24").put("dns", "1.1.1.1")
            .put("serverPublicKey", SERVER_KEY).put("configuredPeerPublicKeys", JSONArray().put(CLIENT_KEY))
            .put("runtimePeerPublicKeys", JSONArray().put(CLIENT_KEY)).put("revokedArchives", JSONArray())
            .put("peers", JSONArray().put(JSONObject().put("name", "phone").put("publicKey", CLIENT_KEY).put("allowedIps", "10.8.0.2/32")
                .put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL))).toString()
        requireNotNull(fixture.model.syncStatus(VpsProfileFixture.server)).join()
        val stored = store.servers.first().single()
        val rendered = CompletableDeferred<Unit>()
        fun observe() {
            val current = field(fixture, "storedServers") as List<*>
            if (current.singleOrNull() == stored && texts(fixture.root).any { it == stored.name }) rendered.complete(Unit)
        }
        fixture.root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) = observe()
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        try {
            observe()
            settle()
            withContext(Dispatchers.Default) { withTimeout(10_000) { rendered.await() } }
        } finally { fixture.root.setOnHierarchyChangeListener(null) }
        clickable(text(fixture.root, stored.name)).performClick()
        settle()
        clickable(text(fixture.root, "phone")).performClick()
        settle()
        assertTrue(fixture.root.findViewById<View>(R.id.vpn0wn_vps_local_endpoint_update).isEnabled)
        return stored
    }

    private fun TestScope.openChooser(fixture: VpsProvisioningLayoutFixture) {
        fixture.root.findViewById<View>(R.id.vpn0wn_vps_local_endpoint_update).performClick()
        settle()
    }

    private suspend fun TestScope.choose(fixture: VpsProvisioningLayoutFixture, name: String) {
        val chooser = requireNotNull(dialog(fixture))
        val index = (0 until chooser.listView.adapter.count).single { chooser.listView.adapter.getItem(it).toString() == name }
        chooser.listView.performItemClick(null, index, index.toLong())
        finish(fixture)
        assertNotNull(dialog(fixture)?.findViewById<View>(R.id.vpn0wn_local_endpoint_review))
    }

    private suspend fun TestScope.review(fixture: VpsProvisioningLayoutFixture) {
        openChooser(fixture)
        choose(fixture, "phone-local")
    }

    private fun TestScope.confirm(fixture: VpsProvisioningLayoutFixture) {
        requireNotNull(dialog(fixture)).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
    }

    private suspend fun TestScope.finish(fixture: VpsProvisioningLayoutFixture) {
        settle()
        (field(fixture, "endpointJob") as? Job)?.join()
        fixture.model.state.first { !it.busy }
        settle()
    }

    private fun TestScope.settle() { runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
    private fun field(fixture: VpsProvisioningLayoutFixture, name: String): Any? =
        VpsProvisioningFragment::class.java.getDeclaredField(name).apply { isAccessible = true }.get(fixture.fragment)
    private fun dialog(fixture: VpsProvisioningLayoutFixture) = field(fixture, "endpointDialog") as? AlertDialog
    private fun environment(fixture: VpsProvisioningLayoutFixture) = VpsProvisioningLayoutFixture::class.java
        .getDeclaredField("environment").apply { isAccessible = true }.get(fixture) as TunnelTestEnvironment
    private fun statusCommands(fixture: VpsProvisioningLayoutFixture) = fixture.peer.commands.count { it.contains("./'sync-status.sh'") }
    private fun feedback(fixture: VpsProvisioningLayoutFixture): String? = fixture.activity
        .findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()
    private fun text(root: View, value: String) = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == value }
    private fun texts(root: View) = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun clickable(view: View): View = if (view.isClickable) view else clickable(view.parent as View)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun fingerprint(offset: Int) = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + offset).toByte() })
    private class CheckedObservationFailure : Exception("Synthetic checked backend failure")

    private companion object {
        val SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        const val OLD_ENDPOINT = "203.0.113.10:51820"
        const val NEW_ENDPOINT = "203.0.113.55:51821"
    }
}
