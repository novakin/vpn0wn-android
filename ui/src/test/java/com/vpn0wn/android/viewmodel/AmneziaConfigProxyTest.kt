/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.viewmodel

import android.app.Application
import android.os.Parcel
import com.vpn0wn.config.BadConfigException
import com.vpn0wn.config.Config as TunnelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 28])
class AmneziaConfigProxyTest {
    @Test
    fun unrelatedEditorChangesAndRecreationPreserveProtocolAndAllFields() {
        val original = TunnelConfig.parse("""
            [Interface]
            Protocol = AmneziaWG
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Jc = 0
            H1 = 20-25
            I1 = <b 0x0102><r 8>
            DisableCookies = false
            [Peer]
            PublicKey = AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            PersistentKeepalive = 20-30
        """.trimIndent().reader().buffered())
        val proxy = ConfigProxy(original)
        proxy.`interface`.addresses = "10.0.0.2/32"
        proxy.peers.single().endpoint = "192.0.2.1:51820"
        val restored = recreate(proxy).resolve()
        assertEquals(original.`interface`.amneziaSettings, restored.`interface`.amneziaSettings)
        assertEquals("20-30", restored.peers.single().amneziaKeepalive.get())
        assertTrue(restored.toWgQuickString().contains("Address = 10.0.0.2/32"))
        assertTrue(restored.toWgQuickString().contains("Endpoint = 192.0.2.1:51820"))
    }

    @Test
    fun packetSizeValidationUsesActualEditedMtuAfterRecreation() {
        val original = TunnelConfig.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            MTU = 576
            S4 = 64800
        """.trimIndent().reader().buffered())
        val proxy = ConfigProxy(original)
        assertEquals(original, proxy.resolve())
        proxy.`interface`.mtu = "600"
        val restored = recreate(proxy)
        assertEquals(600, restored.resolve().`interface`.mtu.get().toInt())
        assertEquals(original.`interface`.amneziaSettings, restored.resolve().`interface`.amneziaSettings)
        restored.`interface`.mtu = "1280"
        assertThrows(BadConfigException::class.java) { recreate(restored).resolve() }
        restored.`interface`.mtu = "unfinished"
        assertEquals("unfinished", recreate(restored).`interface`.mtu)
    }

    @Test
    fun explicitEmptyPacketsSurviveEditorExportAndRecreation() {
        val original = TunnelConfig.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            I1 =
            I2 =
            I3 =
            I4 =
            I5 =
        """.trimIndent().reader().buffered())
        val proxy = ConfigProxy(original)
        proxy.`interface`.listenPort = "51820"
        val restored = recreate(proxy).resolve()
        assertEquals(original.`interface`.amneziaSettings, restored.`interface`.amneziaSettings)
        assertEquals(restored, TunnelConfig.parse(restored.toWgQuickString().reader().buffered()))
        assertTrue(restored.toWgQuickString().contains("I1 = \n"))
    }

    private fun recreate(proxy: ConfigProxy): ConfigProxy {
        val parcel = Parcel.obtain()
        return try {
            proxy.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ConfigProxy.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
