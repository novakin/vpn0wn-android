package com.vpn0wn.android

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.backend.GoBackend
import com.vpn0wn.android.model.TunnelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ApplicationIdentityTest {
    @Test
    fun controlManifestUsesIndependentPermissionAndOnlyLegacyRefreshAlias() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.packageManager
        val receiverName = TunnelManager.IntentReceiver::class.java.name
        for (action in listOf(
            "com.vpn0wn.android.action.REFRESH_TUNNEL_STATES",
            "com.wireguard.android.action.REFRESH_TUNNEL_STATES",
            "com.vpn0wn.android.action.SET_TUNNEL_UP",
            "com.vpn0wn.android.action.SET_TUNNEL_DOWN",
        )) {
            val receivers = manager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), PackageManager.GET_RESOLVED_FILTER)
            assertEquals(listOf(receiverName), receivers.map { it.activityInfo.name })
            assertEquals("${context.packageName}.permission.CONTROL_TUNNELS", receivers.single().activityInfo.permission)
        }
        for (action in listOf("com.wireguard.android.action.SET_TUNNEL_UP", "com.wireguard.android.action.SET_TUNNEL_DOWN")) {
            assertTrue(manager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0).isEmpty())
        }
    }

    @Test
    fun nativeServiceAndLogProviderUseRenamedIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        val service = context.packageManager.getServiceInfo(ComponentName(context, GoBackend.VpnService::class.java), 0)
        assertEquals("android.permission.BIND_VPN_SERVICE", service.permission)
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.exported-log", 0)
        assertEquals("com.vpn0wn.android.activity.LogViewerActivity\$ExportedLogContentProvider", provider?.name)
    }
}
