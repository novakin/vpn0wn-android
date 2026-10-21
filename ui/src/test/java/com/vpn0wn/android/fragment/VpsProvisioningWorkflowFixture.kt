/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.fragment

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.activity.AppDataTransferSettingsTest
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsAddPeerResult
import com.vpn0wn.android.provisioning.VpsCredential
import com.vpn0wn.android.provisioning.VpsPlanningTestPeer
import com.vpn0wn.android.provisioning.VpsProfileFixture
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsUserProfileFile
import com.vpn0wn.android.provisioning.VpsUserProfileStore
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class VpsProvisioningWorkflowFixture {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() { Dispatchers.resetMain() }

    @Before fun resetAuthentication() {
        AppDataTransferSettingsTest.Authentication.calls = 0
        AppDataTransferSettingsTest.Authentication.autoCancel = false
        AppDataTransferSettingsTest.Authentication.expectedTitleRes = R.string.vpn0wn_profile_authenticate
        AppDataTransferSettingsTest.Authentication.callbacks.clear()
    }

    protected suspend fun TestScope.openProfileUser(fixture: Fixture, profiles: VpsUserProfileStore,
        stored: StoredVpsServer = VpsProfileFixture.server, result: VpsAddPeerResult = VpsProfileFixture.result(),
        serverPublicKey: String = PROFILE_SERVER_KEY) {
        val store = Vpn0wnApplication.getVpsServerStore()
        store.upsert(stored)
        store.saveCredential(stored, PROFILE_PIN, VpsCredential.Password(PASSWORD.toCharArray()))
        profiles.saveGenerated(stored, serverPublicKey, VpsProfileFixture.request.copy(name = result.name), result)
        fixture.peer.statusOutput = JSONObject(profileStatus(stored, withPeer = true)).apply {
            put("serverPublicKey", serverPublicKey)
            getJSONArray("peers").getJSONObject(0).put("name", result.name).put("publicKey", result.publicKey)
        }.toString()
        requireNotNull(fixture.model.syncStatus(stored)).join()
        val current = store.servers.first().single()
        awaitRenderedServer(fixture, current)
        openServer(fixture, current)
        clickableAncestor(text(fixture.root, result.name)).performClick()
        settle()
        assertNotNull(fixture.root.findViewById<View>(R.id.vpn0wn_vps_profile_import))
    }

    protected fun profileStatusCommands(fixture: Fixture) = fixture.peer.commands.count { it.contains("./'sync-status.sh'") }

    protected suspend fun TestScope.awaitRenderedServer(fixture: Fixture, expected: StoredVpsServer) {
        val root = fixture.root as ViewGroup
        val projection = VpsProvisioningFragment::class.java.getDeclaredField("storedServers").apply { isAccessible = true }
        val published = CompletableDeferred<Unit>()
        fun observe() {
            val records = projection.get(fixture.fragment) as List<*>
            if (records.singleOrNull { (it as StoredVpsServer).id == expected.id } == expected &&
                descendants(root).filterIsInstance<TextView>().any { it.text.toString() == expected.name }) published.complete(Unit)
        }
        root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) = observe()
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        try {
            observe()
            settle()
            withContext(Dispatchers.Default) { withTimeout(10_000) { published.await() } }
            settle()
        } finally { root.setOnHierarchyChangeListener(null) }
    }

    protected fun setProfileExportRestriction(fixture: Fixture, disabled: Boolean) {
        val restrictions = fixture.environment.application.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        shadowOf(restrictions).setApplicationRestrictions(Bundle().apply { putBoolean("disable_config_export", disabled) })
    }

    protected suspend fun TestScope.finishProfileAction(fixture: Fixture) {
        settle()
        val job = VpsProvisioningFragment::class.java.getDeclaredField("profileJob").apply { isAccessible = true }.get(fixture.fragment) as? Job
        job?.join()
        settle()
    }

    protected suspend fun TestScope.openServer(fixture: Fixture, server: StoredVpsServer) {
        clickableAncestor(text(fixture.root, server.name)).performClick()
        settle()
        fixture.model.state.first { !it.busy }
        settle()
        assertTrue(fixture.root.findViewById<View>(R.id.vpn0wn_vps_refresh_status).isEnabled)
    }

    protected fun profileStore(beforeSync: () -> Unit): VpsUserProfileStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return VpsUserProfileStore(VpsUserProfileFile(File(folder.newFolder(), "profiles"), { key }, sync = {
            beforeSync()
            it.fd.sync()
        }))
    }

    protected fun profileStatus(server: StoredVpsServer, withPeer: Boolean = false): String = JSONObject()
        .put("ok", true).put("interface", "wg0").put("running", true)
        .put("endpoint", server.endpoint).put("listenPort", server.listenPort).put("cidr", server.cidr)
        .put("dns", server.dns).put("serverPublicKey", PROFILE_SERVER_KEY)
        .put("revokedArchives", JSONArray())
        .put("peers", JSONArray().apply {
            if (withPeer) put(JSONObject().put("name", "phone").put("publicKey", PROFILE_CLIENT_KEY).put("allowedIps", "10.8.0.2/32")
                .put("latestHandshake", JSONObject.NULL).put("rxBytes", JSONObject.NULL).put("txBytes", JSONObject.NULL))
        }).toString()

    // A failed setup must drain Android, ViewModel and DataStore owners before resetting Main.
    @Suppress("TooGenericExceptionCaught")
    protected suspend fun TestScope.fixture(immediateMain: Boolean = false, profiles: VpsUserProfileStore? = null): Fixture {
        val scheduled = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(if (immediateMain) ThreadBoundMainDispatcher(Thread.currentThread(), scheduled) else scheduled)
        val environment = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val peer = VpsPlanningTestPeer()
        var modelJob: Job? = null
        try {
            Vpn0wnApplication.getVpsServerStore().servers.first()
            controller.get().setTheme(R.style.AppTheme)
            controller.setup()
            val model = VpsProvisioningViewModel(controller.get().application, Vpn0wnApplication.getVpsServerStore(), peer,
                profiles = profiles ?: VpsUserProfileStore(controller.get().application))
            val createdModelJob = requireNotNull(model.viewModelScope.coroutineContext[Job])
            modelJob = createdModelJob
            controller.get().supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, savedInstanceState: Bundle?) {
                    if (fragment is VpsProvisioningFragment) ViewModelProvider(fragment, object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(model)!!
                    })[VpsProvisioningViewModel::class.java]
                }
            }, false)
            val fragment = VpsProvisioningFragment()
            controller.get().supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            settle()
            return Fixture(environment, controller, fragment, peer, model, createdModelJob)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                peer.blockDetect?.complete(Unit)
                peer.blockStatus?.complete(Unit)
                peer.blockInstall?.complete(Unit)
                peer.blockRemove?.complete(Unit)
                peer.blockRevoke?.complete(Unit)
                try { controller.pause().stop().destroy() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                try { modelJob?.cancelAndJoin() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                try { environment.close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    protected fun TestScope.click(fixture: Fixture, resource: Int) {
        clickableAncestor(text(fixture.root, fixture.activity.getString(resource))).performClick()
        settle()
    }

    protected fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    protected fun text(root: View, value: String, clickable: Boolean = false): TextView = descendants(root).filterIsInstance<TextView>().first {
        it.text.toString() == value && (!clickable || it.isClickable)
    }
    protected fun clickableAncestor(view: View): View = if (view.isClickable) view else clickableAncestor(view.parent as View)
    protected fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    protected class Fixture(
        val environment: TunnelTestEnvironment,
        val controller: ActivityController<HostActivity>,
        val fragment: VpsProvisioningFragment,
        val peer: VpsPlanningTestPeer,
        val model: VpsProvisioningViewModel,
        private val modelJob: Job,
    ) {
        val activity get() = controller.get()
        val root get() = activity.supportFragmentManager.fragments.filterIsInstance<VpsProvisioningFragment>().single().requireView()
        suspend fun close() = withContext(NonCancellable) {
            peer.blockDetect?.complete(Unit)
            peer.blockStatus?.complete(Unit)
            peer.blockInstall?.complete(Unit)
            peer.blockRemove?.complete(Unit)
            peer.blockRevoke?.complete(Unit)
            peer.blockRotate?.complete(Unit)
            peer.blockVerifyRotation?.complete(Unit)
            try { controller.pause().stop().destroy() } finally {
                try { modelJob.cancelAndJoin() } finally { environment.close() }
            }
        }
    }

    class HostActivity : AppCompatActivity()

    // Match Android thread affinity; an always-unconfined Main conflates the nested error emissions.
    private class ThreadBoundMainDispatcher(
        private val mainThread: Thread,
        private val scheduled: CoroutineDispatcher,
    ) : MainCoroutineDispatcher() {
        override val immediate get() = this
        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== mainThread
        override fun dispatch(context: CoroutineContext, block: Runnable) = scheduled.dispatch(context, block)
    }

    protected companion object {
        const val PASSWORD = "synthetic-rotation-password"
        val PROFILE_SERVER_KEY = VpsProfileFixture.serverKey.publicKey.toBase64()
        val PROFILE_CLIENT_KEY = VpsProfileFixture.client.publicKey.toBase64()
        val PROFILE_PIN = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    }
}
