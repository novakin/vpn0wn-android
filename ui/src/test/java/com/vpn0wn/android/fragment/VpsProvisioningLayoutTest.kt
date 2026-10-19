package com.vpn0wn.android.fragment

import android.app.Application
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vpn0wn.android.Application as Vpn0wnApplication
import com.vpn0wn.android.R
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.android.provisioning.VpsDeployFixtureData
import com.vpn0wn.android.provisioning.VpsDeployMapper
import com.vpn0wn.android.provisioning.VpsDeployServer
import com.vpn0wn.android.provisioning.VpsDeployUser
import com.vpn0wn.android.provisioning.VpsDeployUserStatus
import com.vpn0wn.android.provisioning.VpsPlanningTestPeer
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.AuthMode
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Step
import com.vpn0wn.android.widget.VpsTopologyView
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class VpsProvisioningLayoutTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources

    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        Dispatchers.resetMain()
    }

    @Test fun credentialModesFitNormalText() = verifyCredentialLayout(1f)

    @Test fun credentialModesFitEnlargedText() = verifyCredentialLayout(2f)

    @Test fun editorsKeepDescendersClearOfTheRuleAndExposeFocusAndDisabledStates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (night in listOf("night", "notnight")) for (fontScale in listOf(1f, 2f)) {
            RuntimeEnvironment.setQualifiers("en-ldltr-w320dp-h640dp-port-$night-mdpi")
            RuntimeEnvironment.setFontScale(fontScale)
            val fixture = provisioningLayoutFixture(folder, View.LAYOUT_DIRECTION_LTR)
            try {
                fixture.model.begin()
                fixture.model.edit(fixture.model.draft.copy(host = "gjpqy", privateKey = "synthetic-key"))
                settle()
                measure(fixture.root, 320, 640)
                val host = fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_host)
                val key = fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_private_key)
                assertEditorBorderStates(host, key)
                assertEditorBorderStates(key, host)
                val glyphs = Rect().also { host.paint.getTextBounds(host.text.toString(), 0, host.length(), it) }
                val background = editorBackground(host)
                try {
                    val ruleColor = host.context.getColor(R.color.vpn0wn_signal_deep)
                    val ruleTop = (0 until background.height).first { matchesTint(background.getPixel(background.width / 2, it), ruleColor) }
                    val clearance = ruleTop - host.baseline - glyphs.bottom
                    assertTrue("Descenders retain 4dp clearance from the rendered underline at ${fontScale}x: $clearance",
                        clearance >= 4 * host.resources.displayMetrics.density)
                } finally { background.recycle() }
                saveCanvasPreview(host, "ssh-underline-$night-$fontScale")
                assertTrue(key.requestFocus())
                saveCanvasPreview(key, "ssh-boxed-$night-$fontScale")
                assertTrue("Editor presentation opens no SSH", fixture.peer.connections.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test fun detectForwardingUsesLocalizedObservedStatesWithoutChangingTheObservation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (locale in listOf("en", "fr")) for ((observation, expected) in listOf(
            "0" to R.string.vpn0wn_restore_service_disabled,
            "1" to R.string.vpn0wn_restore_service_enabled,
            "unexpected" to R.string.vpn0wn_unknown,
        )) {
            RuntimeEnvironment.setQualifiers("$locale-ldltr-w320dp-h640dp-port-notnight-mdpi")
            RuntimeEnvironment.setFontScale(1f)
            val fixture = provisioningLayoutFixture(folder, View.LAYOUT_DIRECTION_LTR)
            try {
                fixture.peer.detectOutput = JSONObject().put("ok", true).put("os", "debian").put("version", "12")
                    .put("supported", true).put("packageManager", "apt").put("firewall", "iptables")
                    .put("wireGuardInstalled", false).put("configExists", false).put("interface", "wg0")
                    .put("endpoint", "").put("listenPort", "").put("cidr", "").put("ipv4Forwarding", observation).toString()
                fixture.model.begin()
                fixture.model.edit(fixture.model.draft.copy(host = "198.51.100.42", authMode = AuthMode.PASSWORD,
                    password = "synthetic-layout-password".toCharArray()))
                requireNotNull(fixture.model.detect()).join()
                requireNotNull(fixture.model.confirmHostTrust(requireNotNull(fixture.model.state.value.hostTrustConfirmation))).join()
                settle()
                measure(fixture.root, 320, 640)
                assertEquals(Step.DETECT, fixture.model.state.value.step)
                assertEquals(observation, fixture.model.state.value.detect?.ipv4Forwarding)
                val label = text(fixture.root, fixture.activity.getString(R.string.vpn0wn_label_ip_forwarding).uppercase(Locale.ROOT))
                val value = descendants(label.parent as View).filterIsInstance<TextView>().single { it !== label }
                assertEquals(fixture.activity.getString(expected), value.text.toString())
                assertTextFits(value)
                if ((locale == "en" && observation == "0") || (locale == "fr" && observation == "1")) {
                    settlePreview(fixture.root, 320, 640)
                    assertPreviewConfiguration(fixture.root, locale, "notnight", 1f, View.LAYOUT_DIRECTION_LTR)
                    assertReachable(value, descendants(fixture.root).filterIsInstance<ScrollView>().single())
                    saveCanvasPreview(fixture.root, "ssh-context-detect-$locale-$observation")
                }
                assertFalse("Presentation never dispatches installation or user creation", fixture.peer.commands.any {
                    it.contains("./'install.sh'") || it.contains("./'add-peer.sh'")
                })
            } finally { fixture.close() }
        }
    }

    @Test fun sshCredentialsStayMaskedAfterNativeSingleLineSetupAndModeChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (locale in listOf("en", "fr")) for (night in listOf("night", "notnight")) {
            for ((width, height, fontScale) in listOf(Triple(320, 640, 1f), Triple(640, 320, 2f))) {
                val rtl = fontScale > 1f
                val direction = if (rtl) "ldrtl" else "ldltr"
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val fixture = provisioningLayoutFixture(folder, if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                try {
                    val password = "synthetic-ssh-password"
                    val passphrase = "synthetic-ssh-passphrase"
                    val privateKey = "synthetic-private-key-line-one\nsynthetic-private-key-line-two"
                    fixture.model.begin()
                    fixture.model.edit(fixture.model.draft.copy(privateKey = privateKey, passphrase = passphrase,
                        password = password.toCharArray()))
                    settle()
                    measure(fixture.root, width, height)
                    assertMaskedCredential(fixture.root.findViewById(R.id.vpn0wn_vps_ssh_passphrase), passphrase)
                    val key = fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_private_key)
                    assertEquals(privateKey, key.text.toString())
                    assertEquals(5, key.minLines)
                    assertTrue("Private key remains multiline", key.maxLines > 1 && key.layout.lineCount >= 2)
                    assertTrue(key.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                    assertFalse(key.transformationMethod is PasswordTransformationMethod)
                    assertEquals(ResourcesCompat.getFont(key.context, R.font.jetbrains_mono_regular), key.typeface)
                    val host = fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_host)
                    host.setText("198.51.100.42")
                    assertEquals(1, host.maxLines)
                    assertFalse(host.transformationMethod is PasswordTransformationMethod)
                    val hostNode = host.createAccessibilityNodeInfo()
                    try {
                        assertFalse("Host remains ordinary text", hostNode.isPassword)
                        assertTrue(hostNode.isEditable)
                    } finally { hostNode.recycle() }
                    clickAction(fixture, R.string.vpn0wn_auth_password)
                    measure(fixture.root, width, height)
                    assertMaskedCredential(fixture.root.findViewById(R.id.vpn0wn_vps_ssh_password), password)
                    clickAction(fixture, R.string.vpn0wn_auth_ssh_key)
                    fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_passphrase).setText(passphrase)
                    clickAction(fixture, R.string.vpn0wn_auth_ssh_key)
                    measure(fixture.root, width, height)
                    assertMaskedCredential(fixture.root.findViewById(R.id.vpn0wn_vps_ssh_passphrase), passphrase)
                    clickAction(fixture, R.string.vpn0wn_auth_password)
                    fixture.root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_password).setText(password)
                    measure(fixture.root, width, height)
                    assertMaskedCredential(fixture.root.findViewById(R.id.vpn0wn_vps_ssh_password), password)
                    assertTrue("Masking check opens no SSH transport", fixture.peer.connections.isEmpty())
                } finally { fixture.close() }
            }
        }
    }

    @Test fun allFiveTrackerStagesFitNormalTextAtTheRenderer() = verifyTrackerLayout(1f)

    @Test fun allFiveTrackerStagesFitEnlargedTextAtTheRenderer() = verifyTrackerLayout(2f)

    @Test fun peerHeadersKeepLogicalEdgesAndSpaceAtNormalText() = verifyPeerHeaders(1f)

    @Test fun peerHeadersKeepLogicalEdgesAndSpaceAtEnlargedText() = verifyPeerHeaders(2f)

    @Test fun serverDetailsKeepObservedEngineValuesAndMarkUnavailableActions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (enlarged in listOf(false, true)) {
            val locale = if (enlarged) "fr" else "en"
            val night = if (enlarged) "night" else "notnight"
            val width = if (enlarged) 640 else 320
            val height = if (enlarged) 320 else 640
            val fontScale = if (enlarged) 2f else 1f
            val direction = if (enlarged) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            RuntimeEnvironment.setQualifiers("$locale-${if (enlarged) "ldrtl" else "ldltr"}-w${width}dp-h${height}dp-${if (enlarged) "land" else "port"}-$night-mdpi")
            RuntimeEnvironment.setFontScale(fontScale)
            val fixture = provisioningLayoutFixture(folder, direction)
            try {
                val stored = VpsDeployFixtureData.storedServers().first()
                Vpn0wnApplication.getVpsServerStore().upsert(stored)
                settle()
                val server = VpsDeployMapper.fromStored(resources, stored, VpsDeployFixtureData.NOW)
                val factory = VpsProvisioningFragment::class.java.getDeclaredMethod("detailScreen", VpsDeployServer::class.java)
                    .apply { isAccessible = true }
                val detail = factory.invoke(fixture.fragment, server) as View
                fixture.root.removeAllViews()
                fixture.root.addView(detail)
                settlePreview(fixture.root, width, height)
                assertPreviewConfiguration(fixture.root, locale, night, fontScale, direction)
                val scroll = descendants(detail).filterIsInstance<ScrollView>().single()
                val engineLabels = descendants(detail).filterIsInstance<TextView>().filter {
                    it.text.toString() == fixture.activity.getString(R.string.vpn0wn_label_engine).uppercase(Locale.ROOT)
                }.toList()
                assertEquals("Health and network engine rows", 2, engineLabels.size)
                for (label in engineLabels) {
                    val values = descendants(label.parent as View).filterIsInstance<TextView>().filter { it !== label }
                        .map { it.text.toString() }.toList()
                    assertEquals("No OS release presented as engine version", listOf(server.engine), values)
                }
                for (resource in listOf(R.string.vpn0wn_action_repair, R.string.vpn0wn_action_terminal,
                    R.string.vpn0wn_action_uninstall)) {
                    val action = text(detail, fixture.activity.getString(resource)).parent as View
                    assertUnavailableAction(action)
                    assertEquals(1, descendants(action).filterIsInstance<TextView>().count {
                        it.text.toString() == fixture.activity.getString(R.string.vpn0wn_action_unavailable)
                    })
                    for (value in descendants(action).filterIsInstance<TextView>()) assertTextFits(value)
                    assertReachable(action, scroll)
                }
                val repair = text(detail, fixture.activity.getString(R.string.vpn0wn_action_repair)).parent as View
                assertReachable(if (enlarged) repair.parent as View else repair.parent.parent as View, scroll)
                saveCanvasPreview(fixture.root, "ssh-context-detail-$locale-$night-actions")
                if (enlarged) {
                    val terminal = text(detail, fixture.activity.getString(R.string.vpn0wn_action_terminal)).parent as View
                    assertReachable(terminal.parent as View, scroll)
                    saveCanvasPreview(fixture.root, "ssh-context-detail-$locale-$night-actions-lower")
                }
                for (resource in listOf(R.string.vpn0wn_reuse_saved_ssh, R.string.vpn0wn_clear_saved_ssh)) {
                    val label = text(detail, fixture.activity.getString(resource))
                    assertTextFits(label)
                    val action = clickableAncestor(label)
                    assertAccessibleAction(action, android.widget.Button::class.java.name)
                    assertReachable(action, scroll)
                }
                val access = clickableAncestor(text(detail, fixture.activity.getString(R.string.vpn0wn_reuse_saved_ssh))).parent as View
                assertReachable(access, scroll)
                saveCanvasPreview(fixture.root, "ssh-context-detail-$locale-$night-access")
                assertTrue("Unavailable actions open no SSH", fixture.peer.connections.isEmpty())
            } finally {
                fixture.close()
            }
        }
    }

    @Test fun diagramElisionKeepsCompleteNativeValuesAcrossConfigurations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cases = listOf(Triple(320, 640, false), Triple(320, 640, true), Triple(640, 320, false))
        for ((width, height, rtl) in cases) for (night in listOf("notnight", "night")) {
            val locale = if (rtl) "ar" else "en"
            val direction = if (rtl) "ldrtl" else "ldltr"
            val orientation = if (width > height) "land" else "port"
            RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
            RuntimeEnvironment.setFontScale(if (rtl) 2f else 1f)
            val expectedDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            val fixture = provisioningLayoutFixture(folder, expectedDirection)
            try {
                val server = VpsDeployMapper.fromStored(resources, VpsDeployFixtureData.storedServers().first(), VpsDeployFixtureData.NOW)
                    .copy(name = "synthetic-server-with-a-long-context-name", endpoint = "2001:db8:1234:5678::42", cidr = "10.123.240.0/24")
                val factory = VpsProvisioningFragment::class.java.getDeclaredMethod("detailScreen", VpsDeployServer::class.java)
                    .apply { isAccessible = true }
                val detail = factory.invoke(fixture.fragment, server) as View
                fixture.root.removeAllViews()
                fixture.root.addView(detail)
                var drawReady = false
                repeat(3) {
                    measure(fixture.root, width, height)
                    shadowOf(Looper.getMainLooper()).idle()
                    drawReady = !fixture.root.viewTreeObserver.dispatchOnPreDraw()
                }
                assertTrue("Final native layout can draw", drawReady)
                assertEquals("Actual locale", locale, detail.resources.configuration.locales[0].language)
                assertEquals("Actual font scale", if (rtl) 2f else 1f, detail.resources.configuration.fontScale)
                assertEquals("Actual root direction", expectedDirection, fixture.root.layoutDirection)
                assertEquals("Actual detail direction", expectedDirection, detail.layoutDirection)
                assertEquals("Actual theme", if (night == "night") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                    detail.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                assertTextFits(detail.findViewById(R.id.vpn0wn_vps_hero_server))
                val scroll = descendants(detail).filterIsInstance<ScrollView>().single()
                for ((label, value) in listOf(R.string.vpn0wn_label_endpoint to server.endpointAddress,
                    R.string.vpn0wn_metric_cidr to server.cidr)) {
                    val labelView = descendants(detail).filterIsInstance<TextView>().single {
                        it.text.toString() == fixture.activity.getString(label).uppercase(Locale.ROOT) &&
                            (it.parent as ViewGroup).childCount == 2
                    }
                    val valueView = descendants(labelView.parent as View).filterIsInstance<TextView>().single { it !== labelView }
                    assertEquals("Complete native source value", value, valueView.text.toString())
                    val node = valueView.createAccessibilityNodeInfo()
                    try { assertEquals("Complete accessible value", value, node.text.toString()) } finally { node.recycle() }
                    if (label == R.string.vpn0wn_label_endpoint) {
                        assertEquals("IPv6 socket display", "[2001:db8:1234:5678::42]:51820", value)
                        val copy = descendants(labelView.parent.parent as View).filterIsInstance<ImageButton>().single()
                        assertReachable(copy, scroll)
                        assertTrue("Native copy control enabled", copy.isEnabled && copy.isClickable)
                        assertTrue("Native copy action handled", copy.performClick())
                        val clipboard = fixture.activity.getSystemService(ClipboardManager::class.java)
                        assertEquals("Complete IPv6 socket copied", value, clipboard.primaryClip!!.getItemAt(0).text.toString())
                    }
                }
                scroll.scrollTo(0, 0)
                val name = "$width-${if (rtl) "large-rtl" else "normal"}-$night"
                saveCanvasPreview(fixture.root, "server-$name")
                saveCanvasPreview(descendants(detail).filterIsInstance<VpsTopologyView>().single(), "topology-$name")
                assertTrue("Diagram checks open no SSH", fixture.peer.connections.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test fun vpnMetadataUsesObservedValuesAndDisablesUnknownEndpointCopy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (locale in listOf("en", "fr")) for (night in listOf("night", "notnight")) {
            for ((width, height, fontScale) in listOf(Triple(320, 640, 1f), Triple(640, 320, 2f))) {
                val rtl = fontScale > 1f
                val direction = if (rtl) "ldrtl" else "ldltr"
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val fixture = provisioningLayoutFixture(folder, if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                try {
                    for ((endpoint, port) in listOf("" to "", "vpn.example" to "invalid", "" to "51821", "vpn.example" to "51821")) {
                        val stored = VpsDeployFixtureData.storedServers().first().copy(host = "192.0.2.10", endpoint = endpoint,
                            listenPort = port, provider = "", region = "")
                        val server = VpsDeployMapper.fromStored(resources, stored, VpsDeployFixtureData.NOW)
                        val factory = VpsProvisioningFragment::class.java.getDeclaredMethod("detailScreen", VpsDeployServer::class.java)
                            .apply { isAccessible = true }
                        val detail = factory.invoke(fixture.fragment, server) as View
                        fixture.root.removeAllViews()
                        fixture.root.addView(detail)
                        var drawReady = false
                        repeat(3) {
                            if (!drawReady) {
                                measure(fixture.root, width, height)
                                shadowOf(Looper.getMainLooper()).idle()
                                drawReady = !fixture.root.viewTreeObserver.dispatchOnPreDraw()
                            }
                        }
                        assertTrue("Metadata pre-draw completes", drawReady)
                        val endpointLabel = descendants(detail).filterIsInstance<TextView>().single {
                            it.text.toString() == fixture.activity.getString(R.string.vpn0wn_label_endpoint).uppercase(Locale.ROOT) &&
                                (it.parent as ViewGroup).childCount == 2
                        }
                        val endpointRow = endpointLabel.parent.parent as View
                        val expectedAddress = if (endpoint.isNotBlank() && port == "51821") "vpn.example:51821" else "—"
                        val endpointValue = descendants(endpointLabel.parent as View).filterIsInstance<TextView>().single { it !== endpointLabel }
                        assertEquals(expectedAddress, endpointValue.text.toString())
                        assertTextFits(endpointValue)
                        assertEquals("Only complete observed endpoints can be copied", if (expectedAddress == "—") 0 else 1,
                            descendants(endpointRow).filterIsInstance<ImageButton>().count())
                        val portLabel = text(detail, fixture.activity.getString(R.string.vpn0wn_label_listen_port).uppercase(Locale.ROOT))
                        val portValue = descendants(portLabel.parent as View).filterIsInstance<TextView>().single { it !== portLabel }
                        assertEquals(if (port == "51821") "UDP 51821" else "—", portValue.text.toString())
                        assertTextFits(portValue)
                        assertFalse("SSH address is not VPN metadata", descendants(detail).filterIsInstance<TextView>().any {
                            it.text.toString().contains("192.0.2.10") || it.text.toString().contains("51820") || it.text.toString().contains("null")
                        })
                        val cardFactory = VpsProvisioningFragment::class.java.getDeclaredMethod("serverCard", VpsDeployServer::class.java)
                            .apply { isAccessible = true }
                        val card = cardFactory.invoke(fixture.fragment, server) as View
                        assertEquals(1, descendants(card).filterIsInstance<TextView>().count {
                            it.text.toString() == fixture.activity.getString(R.string.vpn0wn_server_card_summary_unknown, expectedAddress, server.engine)
                        })
                        val user = VpsDeployUser("peer", "phone", "phone", "10.8.0.2/32", "0.0.0.0/0", VpsDeployUserStatus.IDLE,
                            "—", "—", "—", "—", "synthetic-public-key")
                        val userFactory = VpsProvisioningFragment::class.java.getDeclaredMethod("userScreen",
                            VpsDeployServer::class.java, VpsDeployUser::class.java).apply { isAccessible = true }
                        val userScreen = userFactory.invoke(fixture.fragment, server, user) as View
                        val userEndpointLabel = text(userScreen, fixture.activity.getString(R.string.vpn0wn_label_endpoint).uppercase(Locale.ROOT))
                        assertEquals(expectedAddress, descendants(userEndpointLabel.parent as View).filterIsInstance<TextView>()
                            .single { it !== userEndpointLabel }.text.toString())
                    }
                } finally {
                    fixture.close()
                }
            }
        }
    }

    private fun verifyPeerHeaders(fontScale: Float) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (locale in listOf("en", "fr")) for (night in listOf("night", "notnight")) {
            for (direction in listOf("ldltr", "ldrtl")) for ((width, height) in listOf(320 to 640, 640 to 320)) {
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val rtl = direction == "ldrtl"
                val fixture = provisioningLayoutFixture(folder, if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                try {
                    val server = VpsDeployMapper.fromStored(resources, VpsDeployFixtureData.storedServers().first(), VpsDeployFixtureData.NOW)
                    val factory = VpsProvisioningFragment::class.java.getDeclaredMethod("userRow",
                        VpsDeployServer::class.java, VpsDeployUser::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                    for (state in VpsDeployUserStatus.entries) {
                        val user = VpsDeployUser("peer", "peer", "phone", "10.8.0.2/32", "0.0.0.0/0", state,
                            "0 B", "0 B", "—", "—", "synthetic-public-key")
                        val row = factory.invoke(fixture.fragment, server, user, 0) as ViewGroup
                        fixture.root.removeAllViews()
                        fixture.root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT))
                        measure(fixture.root, width, height)
                        val index = text(row, fixture.activity.getString(R.string.vpn0wn_user_index, "01").uppercase(Locale.ROOT))
                        val status = text(row, fixture.activity.getString(state.labelRes).uppercase(Locale.ROOT))
                        assertTextFits(index)
                        assertTextFits(status)
                        val indexPosition = IntArray(2).also(index::getLocationInWindow)
                        val statusPosition = IntArray(2).also(status::getLocationInWindow)
                        val indexLayout = requireNotNull(index.layout)
                        val statusLayout = requireNotNull(status.layout)
                        val indexStart = indexPosition[0] + index.compoundPaddingLeft + (0 until indexLayout.lineCount).minOf(indexLayout::getLineLeft)
                        val indexEnd = indexPosition[0] + index.compoundPaddingLeft + (0 until indexLayout.lineCount).maxOf(indexLayout::getLineRight)
                        val statusStart = statusPosition[0] + status.compoundPaddingLeft + (0 until statusLayout.lineCount).minOf(statusLayout::getLineLeft)
                        val statusEnd = statusPosition[0] + status.compoundPaddingLeft + (0 until statusLayout.lineCount).maxOf(statusLayout::getLineRight)
                        val gap = if (rtl) indexStart - statusEnd else statusStart - indexEnd
                        assertTrue("Peer labels retain at least 8dp separation", gap >= 8 * row.resources.displayMetrics.density)
                        if (rtl) assertEquals("Index starts at logical right", (indexPosition[0] + index.width - index.compoundPaddingRight).toFloat(), indexEnd, 0.5f)
                        else assertEquals("Index starts at logical left", (indexPosition[0] + index.compoundPaddingLeft).toFloat(), indexStart, 0.5f)
                    }
                } finally {
                    fixture.close()
                }
            }
        }
    }

    private fun verifyCredentialLayout(fontScale: Float) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (locale in listOf("en", "fr")) for (night in listOf("night", "notnight")) {
            for (direction in listOf("ldltr", "ldrtl")) for ((width, height) in listOf(320 to 640, 640 to 320)) {
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val requestedDirection = if (direction == "ldrtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                val fixture = provisioningLayoutFixture(folder, requestedDirection)
                try {
                    val activity = fixture.activity
                    val root = fixture.root
                    measure(root, width, height)
                    assertEquals("Requested layout direction", requestedDirection, root.layoutDirection)
                    val add = text(root, activity.getString(R.string.vpn0wn_add_server).removePrefix("+ ").trim())
                    assertTrue("Enabled Add label has readable contrast", ColorUtils.calculateContrast(
                        add.currentTextColor, activity.getColor(R.color.vpn0wn_paper)) >= 4.5)
                    val filterTile = clickableAncestor(text(root, activity.getString(R.string.vpn0wn_filter_all).uppercase(Locale.ROOT))) as ViewGroup
                    for (value in descendants(filterTile).filterIsInstance<TextView>()) {
                        assertTextFits(value)
                        assertInside(value, filterTile)
                    }
                    assertEquals("Localized fleet icon names", setOf(activity.getString(R.string.vpn0wn_navigate_back),
                        activity.getString(R.string.vpn0wn_logs_title), activity.getString(R.string.settings)),
                        descendants(root).filterIsInstance<ImageButton>().map { it.contentDescription.toString() }.toSet())
                    for (icon in descendants(root).filterIsInstance<ImageButton>()) {
                        assertAccessibleAction(icon, ImageButton::class.java.name)
                        assertTrue("Localized icon name", !icon.contentDescription.isNullOrBlank())
                        assertEquals("Existing visible icon width", 16, icon.width - icon.paddingLeft - icon.paddingRight)
                        assertEquals("Existing visible icon height", 16, icon.height - icon.paddingTop - icon.paddingBottom)
                    }
                    assertAccessibleAction(clickableAncestor(add), android.widget.Button::class.java.name)
                    assertTrue(clickableAncestor(add).performClick())
                    settle()
                    measure(root, width, height)
                    val host = descendants(root).filterIsInstance<EditText>().single {
                        it.hint.toString() == activity.getString(R.string.vpn0wn_host_placeholder)
                    }
                    host.setText("198.51.100.42")
                    for (password in listOf(true, false)) {
                        val mode = if (password) R.string.vpn0wn_auth_password else R.string.vpn0wn_auth_ssh_key
                        assertTrue(text(root, activity.getString(mode), clickable = true).performClick())
                        settle()
                        measure(root, width, height)
                        val scroll = descendants(root).filterIsInstance<ScrollView>().single()
                        val capture = (fontScale == 1f && width == 320 && direction == "ldltr") ||
                            (fontScale == 2f && width == 640 && direction == "ldrtl")
                        val captureName = "ssh-context-$locale-$night-${width}x$height-${fontScale}x-$direction"
                        if (capture && password && fontScale == 1f) {
                            settlePreview(root, width, height)
                            assertPreviewConfiguration(root, locale, night, fontScale, requestedDirection)
                            scroll.scrollTo(0, 0)
                            saveCanvasPreview(root, "$captureName-top")
                        }
                        val keyTab = text(root, activity.getString(R.string.vpn0wn_auth_ssh_key), clickable = true)
                        val passwordTab = text(root, activity.getString(R.string.vpn0wn_auth_password), clickable = true)
                        assertEquals("Aligned authentication segments", keyTab.height, passwordTab.height)
                        for (tab in listOf(R.string.vpn0wn_auth_ssh_key, R.string.vpn0wn_auth_password)) {
                            val control = text(root, activity.getString(tab), clickable = true)
                            assertTextFits(control)
                            val selected = tab == mode
                            assertAccessibleAction(control, android.widget.RadioButton::class.java.name, selected)
                            assertEquals("Selected auth mode exposed", selected, control.isSelected)
                            assertTrue("Android auth touch target", control.height >= 48 * control.resources.displayMetrics.density)
                            assertReachable(control, scroll)
                        }
                        val note = if (password) R.string.vpn0wn_password_ephemeral_note else R.string.vpn0wn_private_key_keystore_note
                        val noteView = text(root, activity.getString(note))
                        assertTextFits(noteView)
                        assertReachable(noteView, scroll)
                        if (!password) {
                            val picker = text(root, activity.getString(R.string.vpn0wn_pick_key_file).uppercase(Locale.ROOT))
                            assertTextFits(picker)
                            assertUnavailableAction(picker)
                            assertReachable(picker, scroll)
                            val guidance = text(root, activity.getString(R.string.vpn0wn_key_file_picker_pending))
                            assertTextFits(guidance)
                            assertReachable(guidance, scroll)
                            val key = root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_private_key)
                            assertTrue("Manual PEM entry remains available", key.isEnabled && key.onCheckIsTextEditor())
                        }
                        val restoredHost = descendants(root).filterIsInstance<EditText>().single {
                            it.hint.toString() == activity.getString(R.string.vpn0wn_host_placeholder)
                        }
                        assertEquals("198.51.100.42", restoredHost.text.toString())
                        val port = descendants(root).filterIsInstance<EditText>().single {
                            (it.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                        }
                        assertEquals("22", port.text.toString())
                        assertEquals("Numeric port direction stays LTR", View.TEXT_DIRECTION_LTR, port.textDirection)
                        assertTrue("Numeric port retains a rendered text layout", port.layout.lineCount > 0)
                        val portWidth = port.width - port.compoundPaddingLeft - port.compoundPaddingRight
                        assertTrue("Numeric port glyphs remain in its visible horizontal content", port.layout.getLineLeft(0) - port.scrollX >= 0 &&
                            port.layout.getLineRight(0) - port.scrollX <= portWidth)
                        assertReachable(port, scroll)
                        val next = text(root, activity.getString(R.string.vpn0wn_continue), clickable = true)
                        assertTextFits(next)
                        assertAccessibleAction(next, android.widget.Button::class.java.name)
                        assertInside(next, root)
                        if (capture) {
                            settlePreview(root, width, height)
                            assertPreviewConfiguration(root, locale, night, fontScale, requestedDirection)
                            val field = root.findViewById<EditText>(if (password) R.id.vpn0wn_vps_ssh_password else R.id.vpn0wn_vps_ssh_private_key)
                            val visibleHeight = minOf(field.height, scroll.height - scroll.paddingTop - scroll.paddingBottom)
                            assertTrue("Credential area has a usable viewport", visibleHeight > 0)
                            field.requestRectangleOnScreen(Rect(0, 0, field.width, visibleHeight), true)
                            val visible = Rect()
                            assertTrue("Credential area is on screen", field.getLocalVisibleRect(visible))
                            assertEquals("Credential left edge is visible", 0, visible.left)
                            assertEquals("Credential right edge is visible", field.width, visible.right)
                            assertEquals("Credential top is visible", 0, visible.top)
                            assertTrue("Requested credential area is visible", visible.bottom >= visibleHeight)
                            saveCanvasPreview(root, "$captureName-${if (password) "password" else "key"}")
                        }
                    }
                    assertTrue(text(root, activity.getString(R.string.vpn0wn_continue), clickable = true).performClick())
                    settle()
                    measure(root, width, height)
                    assertTrue(text(root, activity.getString(R.string.vpn0wn_ssh_invalid_input)).isShown)
                    assertEquals(Step.SSH, fixture.model.state.value.step)
                    assertTrue("Invalid credentials never open SSH", fixture.peer.connections.isEmpty())
                    assertStageLayout(fixture, Step.SSH, fontScale)

                    assertTrue(text(root, activity.getString(R.string.vpn0wn_auth_password), clickable = true).performClick())
                    settle()
                    root.findViewById<EditText>(R.id.vpn0wn_vps_ssh_password).setText("synthetic-layout-password")
                    clickAction(fixture, R.string.vpn0wn_continue)
                    val confirmation = fixture.model.state.first { !it.busy && it.hostTrustConfirmation != null }.hostTrustConfirmation
                    requireNotNull(fixture.model.confirmHostTrust(requireNotNull(confirmation))).join()
                    fixture.model.state.first { !it.busy && it.detect != null }
                    settle()
                    measure(root, width, height)
                    assertStageLayout(fixture, Step.DETECT, fontScale)
                    val listenPort = root.findViewById<EditText>(R.id.vpn0wn_vps_listen_port)
                    assertEquals("Numeric VPN port direction stays LTR", View.TEXT_DIRECTION_LTR, listenPort.textDirection)
                    assertTextFits(listenPort)
                    assertReachable(listenPort, descendants(root).filterIsInstance<ScrollView>().single())
                    clickAction(fixture, R.string.vpn0wn_generate_plan)
                    fixture.model.state.first { !it.busy && it.plan != null }
                    settle()
                    measure(root, width, height)
                    assertStageLayout(fixture, Step.PLAN, fontScale)
                    clickAction(fixture, R.string.vpn0wn_review_apply)
                    measure(root, width, height)
                    assertStageLayout(fixture, Step.APPLY, fontScale)
                    val apply = text(root, activity.getString(R.string.vpn0wn_apply_plan_button), clickable = true)
                    assertTrue("Fresh Apply opens confirmation", apply.isEnabled)
                    assertEquals(Step.APPLY, fixture.model.state.value.step)
                    assertTrue(fixture.peer.commands.any { it.contains("./'detect.sh'") })
                    assertTrue(fixture.peer.commands.any { it.contains("./'plan.sh'") })
                    assertFalse(fixture.peer.commands.any { it.contains("./'install.sh'") || it.contains("./'add-peer.sh'") })
                } finally {
                    fixture.close()
                }
            }
        }
    }

    private fun verifyTrackerLayout(fontScale: Float) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (locale in listOf("en", "fr")) for (night in listOf("night", "notnight")) {
            for (direction in listOf("ldltr", "ldrtl")) for ((width, height) in listOf(320 to 640, 640 to 320)) {
                val orientation = if (width > height) "land" else "port"
                RuntimeEnvironment.setQualifiers("$locale-$direction-w${width}dp-h${height}dp-$orientation-$night-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                val requestedDirection = if (direction == "ldrtl") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                val fixture = provisioningLayoutFixture(folder, requestedDirection)
                try {
                    val factory = VpsProvisioningFragment::class.java.getDeclaredMethod("stepTracker",
                        List::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                    val steps = stageResources.map { fixture.activity.getString(it) }
                    for (stage in steps.indices) {
                        val tracker = factory.invoke(fixture.fragment, steps, stage) as HorizontalScrollView
                        fixture.root.removeAllViews()
                        fixture.root.addView(tracker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT))
                        measure(fixture.root, width, height)
                        assertEquals("Tracker inherits requested direction", requestedDirection, tracker.layoutDirection)
                        assertTrackerFitsAndIsReachable(fixture.root, stage)
                        assertTrackerWidth(fixture.root, fontScale)
                    }
                    assertFalse("Renderer coverage does not activate a workflow", fixture.model.state.value.active)
                    assertTrue("Renderer coverage never opens SSH", fixture.peer.connections.isEmpty())
                } finally { fixture.close() }
            }
        }
    }

    private fun assertStageLayout(fixture: VpsProvisioningLayoutFixture, step: Step, fontScale: Float) {
        assertEquals(step, fixture.model.state.value.step)
        val summary = fixture.activity.getString(R.string.vpn0wn_deploy_step_summary, step.ordinal + 1,
            fixture.activity.getString(stageResources[step.ordinal])).uppercase(Locale.ROOT)
        assertTrue(text(fixture.root, summary).isShown)
        assertTrackerFitsAndIsReachable(fixture.root, step.ordinal)
        assertTrackerWidth(fixture.root, fontScale)
        val action = fixture.root.findViewById<TextView>(R.id.vpn0wn_vps_bottom_action)
        assertTextFits(action)
        assertAccessibleAction(action, android.widget.Button::class.java.name)
        assertInside(action, fixture.root)
        assertTrue("Fresh verified Detect/Plan keeps each reviewed stage actionable", action.isEnabled)
    }

    private fun assertTrackerWidth(root: View, fontScale: Float) {
        if (fontScale == 1f) {
            val tracker = descendants(root).filterIsInstance<HorizontalScrollView>().single()
            assertEquals("Normal tracker fills available width without overflow", tracker.width, tracker.getChildAt(0).width)
        }
    }

    private fun TestScope.clickAction(fixture: VpsProvisioningLayoutFixture, resource: Int) {
        assertTrue(text(fixture.root, fixture.activity.getString(resource), clickable = true).performClick())
        settle()
    }

    private fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertMaskedCredential(field: EditText, secret: String) {
        assertEquals(secret, field.text.toString())
        assertTrue("SSH credential retains native password masking", field.transformationMethod is PasswordTransformationMethod)
        val transformed = requireNotNull(field.transformationMethod).getTransformation(field.text, field).toString()
        assertEquals(secret.length, transformed.length)
        assertFalse("Native transformation conceals the complete credential", transformed.contains(secret))
        assertFalse("Painted layout conceals the complete credential", field.layout.text.toString().contains(secret))
        assertEquals(1, field.maxLines)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, field.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(ResourcesCompat.getFont(field.context, R.font.jetbrains_mono_regular), field.typeface)
        val node = field.createAccessibilityNodeInfo()
        try {
            assertTrue("Native accessibility marks credential as password", node.isPassword)
            assertTrue("Credential remains editable", node.isEditable)
        } finally { node.recycle() }
    }

    private fun assertAccessibleAction(view: View, role: String, checked: Boolean? = null) {
        val density = view.resources.displayMetrics.density
        assertTrue("Android action target", view.width >= 48 * density && view.height >= 48 * density)
        assertTrue("Keyboard focusable action", view.isFocusable)
        assertTrue("Native pressed/focus feedback behind content", view.background != null && view.background.isStateful)
        assertEquals("Feedback cannot cover content", null, view.foreground)
        if (view is TextView) assertEquals("Shared mono font features", "ss01, zero, tnum", view.fontFeatureSettings)
        val node = view.createAccessibilityNodeInfo()
        try {
            assertEquals("Native action role", role, node.className.toString())
            assertTrue("Accessible click action", node.isClickable)
            assertEquals("Enabled state exposed", view.isEnabled, node.isEnabled)
            if (checked != null) {
                assertTrue("Authentication choice is checkable", node.isCheckable)
                assertEquals("Authentication state exposed", checked, node.isChecked)
            }
        } finally {
            node.recycle()
        }
    }

    private fun assertUnavailableAction(view: View) {
        assertFalse("Unavailable action is disabled before tapping", view.isEnabled)
        assertFalse("Unavailable action has no callback", view.hasOnClickListeners())
        val node = view.createAccessibilityNodeInfo()
        try {
            assertEquals(android.widget.Button::class.java.name, node.className.toString())
            assertFalse("Unavailable state is accessible", node.isEnabled)
            assertFalse("Unavailable action offers no accessible click", node.isClickable)
        } finally { node.recycle() }
    }

    private fun assertEditorBorderStates(field: EditText, other: EditText) {
        assertTrue(other.requestFocus())
        assertFalse(field.hasFocus())
        assertEditorBorderColor(field, R.color.vpn0wn_rule_strong)
        assertTrue(field.requestFocus())
        assertEditorBorderColor(field, R.color.vpn0wn_signal_deep)
        field.isEnabled = false
        try { assertEditorBorderColor(field, R.color.vpn0wn_rule) } finally { field.isEnabled = true }
        assertTrue(other.requestFocus())
    }

    private fun assertEditorBorderColor(field: EditText, colorResource: Int) {
        val background = editorBackground(field)
        try {
            val expected = field.context.getColor(colorResource)
            val pixels = IntArray(background.width * background.height)
            background.getPixels(pixels, 0, background.width, 0, 0, background.width, background.height)
            assertTrue("Rendered editor border reflects focus=${field.hasFocus()}, enabled=${field.isEnabled}; " +
                "expected=${expected.toUInt().toString(16)}, painted=${pixels.filter { it != 0 }.distinct().take(8).map { it.toUInt().toString(16) }}",
                pixels.any { matchesTint(it, expected) })
        } finally { background.recycle() }
    }

    private fun matchesTint(pixel: Int, expected: Int): Boolean {
        val alpha = Color.alpha(pixel)
        if (alpha < 64) return false
        // Native translucent antialiasing rounds premultiplied channels before the bitmap readback.
        val tolerance = kotlin.math.ceil(255.0 / alpha).toInt()
        return kotlin.math.abs(Color.red(pixel) - Color.red(expected)) <= tolerance &&
            kotlin.math.abs(Color.green(pixel) - Color.green(expected)) <= tolerance &&
            kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) <= tolerance
    }

    private fun editorBackground(field: EditText): Bitmap {
        field.jumpDrawablesToCurrentState()
        return Bitmap.createBitmap(field.width, field.height, Bitmap.Config.ARGB_8888).also {
            requireNotNull(field.background).apply {
                setBounds(0, 0, field.width, field.height)
                draw(Canvas(it))
            }
        }
    }

    private fun assertTrackerFitsAndIsReachable(root: View, activeStage: Int) {
        val tracker = descendants(root).filterIsInstance<HorizontalScrollView>().single()
        val row = tracker.getChildAt(0) as ViewGroup
        val columns = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<ViewGroup>()
        assertEquals("All five stages remain available", 5, columns.size)
        assertTrue("Tracker fills its viewport or uses native overflow", row.width >= tracker.width)
        assertTrue("Step widths remain evenly distributed", columns.maxOf { it.width } - columns.minOf { it.width } <= 1)
        assertEquals("Steps span content from the first gutter", row.paddingLeft, columns.minOf { it.left })
        assertEquals("Steps span content through the final gutter", row.width - row.paddingRight, columns.maxOf { it.right })
        assertInside(columns[activeStage], tracker)
        for (column in columns) {
            val badgeRow = column.getChildAt(0) as ViewGroup
            val badge = (0 until badgeRow.childCount).map { badgeRow.getChildAt(it) }.filterIsInstance<TextView>().single()
            val label = (0 until column.childCount).map { column.getChildAt(it) }.filterIsInstance<TextView>().last()
            assertTextFits(badge)
            assertTextFits(label)
            assertEquals("Stage names remain single line", 1, label.lineCount)
            assertTrue("Existing minimum step width", column.width >= 48 * column.resources.displayMetrics.density)
            assertTrue("Existing minimum badge", badge.width >= 22 * badge.resources.displayMetrics.density &&
                badge.height >= 22 * badge.resources.displayMetrics.density)
            tracker.requestChildRectangleOnScreen(row, Rect(column.left, column.top, column.right, column.bottom), true)
            assertInside(column, tracker)
        }
    }

    private fun assertTextFits(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue("Complete text: ${view.text}", layout.lineCount > 0 && layout.getLineEnd(layout.lineCount - 1) == view.text.length)
        assertTrue("Text height: ${view.text}; view=${view.width}x${view.height}; layout=${layout.width}x${layout.height}; " +
            "lines=${layout.lineCount}; verticalPadding=${view.compoundPaddingTop + view.compoundPaddingBottom}",
            layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        val contentWidth = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals("No truncation: ${view.text}", 0, layout.getEllipsisCount(line))
            assertTrue("Text width: ${view.text}; line=$line; scrollX=${view.scrollX}; " +
                "glyphs=${layout.getLineLeft(line)}..${layout.getLineRight(line)}; content=$contentWidth",
                layout.getLineLeft(line) - view.scrollX >= 0 && layout.getLineRight(line) - view.scrollX <= contentWidth)
        }
    }

    private fun assertReachable(view: View, scroll: ScrollView) {
        assertTrue("Usable scroll viewport", scroll.height >= view.height + scroll.paddingTop + scroll.paddingBottom)
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        assertInside(view, scroll)
    }

    private fun assertInside(view: View, parent: ViewGroup) {
        val position = IntArray(2)
        val viewport = IntArray(2)
        view.getLocationInWindow(position)
        parent.getLocationInWindow(viewport)
        val bounds = Rect(position[0] - viewport[0], position[1] - viewport[1],
            position[0] - viewport[0] + view.width, position[1] - viewport[1] + view.height)
        assertTrue("Horizontally reachable: $bounds", bounds.left >= parent.paddingLeft && bounds.right <= parent.width - parent.paddingRight)
        assertTrue("Vertically reachable: $bounds", bounds.top >= parent.paddingTop && bounds.bottom <= parent.height - parent.paddingBottom)
    }

    private fun text(root: View, value: String, clickable: Boolean = false): TextView =
        descendants(root).filterIsInstance<TextView>().single { it.text.toString() == value && (!clickable || it.isClickable) }

    private fun clickableAncestor(view: View): View {
        var current = view
        while (!current.isClickable) current = current.parent as? View ?: error("Missing clickable ancestor")
        return current
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun saveCanvasPreview(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File("../.artifacts/canvas-font-previews").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun TestScope.settlePreview(root: View, width: Int, height: Int) {
        var drawReady = false
        repeat(3) {
            measure(root, width, height)
            settle()
            drawReady = !root.viewTreeObserver.dispatchOnPreDraw()
        }
        assertTrue("Final native layout can draw", drawReady)
        assertEquals(width, root.width)
        assertEquals(height, root.height)
    }

    private fun assertPreviewConfiguration(root: View, locale: String, night: String, fontScale: Float, direction: Int) {
        val configuration = root.resources.configuration
        assertEquals("Actual preview locale", locale, configuration.locales[0].language)
        assertEquals("Actual preview font scale", fontScale, configuration.fontScale)
        assertEquals("Actual preview root direction", direction, root.layoutDirection)
        assertEquals("Actual preview theme", if (night == "night") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
    }

    private fun measure(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private val stageResources = listOf(R.string.vpn0wn_step_ssh, R.string.vpn0wn_step_detect, R.string.vpn0wn_step_plan,
        R.string.vpn0wn_step_apply, R.string.vpn0wn_step_import)
}

internal class VpsProvisioningLayoutFixture(
    private val environment: TunnelTestEnvironment,
    val controller: ActivityController<VpsProvisioningLayoutHostActivity>,
    val fragment: VpsProvisioningFragment,
    val peer: VpsPlanningTestPeer,
    val model: VpsProvisioningViewModel,
    private val modelJob: Job,
) {
    val activity get() = controller.get()
    val root get() = fragment.requireView() as FrameLayout

    suspend fun close() = withContext(NonCancellable) {
        peer.blockDetect?.complete(Unit)
        try { controller.pause().stop().destroy() } finally {
            try { modelJob.cancelAndJoin() } finally { environment.close() }
        }
    }
}

// Failed setup must also drain Android, ViewModel and DataStore before resetMain.
@Suppress("TooGenericExceptionCaught")
internal suspend fun TestScope.provisioningLayoutFixture(folder: TemporaryFolder, direction: Int): VpsProvisioningLayoutFixture {
    val environment = TunnelTestEnvironment(folder.newFolder(), backgroundScope)
    val controller = Robolectric.buildActivity(VpsProvisioningLayoutHostActivity::class.java)
    val peer = VpsPlanningTestPeer()
    var modelJob: Job? = null
    try {
        Vpn0wnApplication.getVpsServerStore().servers.first()
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        controller.get().window.decorView.layoutDirection = direction
        val model = VpsProvisioningViewModel(controller.get().application, Vpn0wnApplication.getVpsServerStore(), peer)
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
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        return VpsProvisioningLayoutFixture(environment, controller, fragment, peer, model, createdModelJob)
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            peer.blockDetect?.complete(Unit)
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

internal class VpsProvisioningLayoutHostActivity : AppCompatActivity()
