package com.vpn0wn.android.model

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.vpn0wn.android.Application
import com.vpn0wn.android.backend.Backend
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.configStore.ConfigStore
import com.vpn0wn.config.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal class TunnelTestEnvironment(directory: File, scope: CoroutineScope) {
    val backend = ControlledBackend()
    val store = MemoryStore()
    val application = Application()
    val applicationScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    val manager: TunnelManager

    init {
        Application::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(application, ApplicationProvider.getApplicationContext<Context>())
        setField("coroutineScope", applicationScope)
        setField("preferencesDataStore", PreferenceDataStoreFactory.create(
            scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO)
        ) { File(directory, "settings.preferences_pb") })
        @Suppress("UNCHECKED_CAST")
        val future = Application::class.java.getDeclaredField("futureBackend").apply { isAccessible = true }
            .get(application) as CompletableDeferred<Backend>
        future.complete(backend)
        manager = TunnelManager(store)
        setField("tunnelManager", manager)
    }

    private fun setField(name: String, value: Any) {
        Application::class.java.getDeclaredField(name).apply { isAccessible = true }.set(application, value)
    }

    fun replaceBackend(backend: Backend) {
        setField("futureBackend", CompletableDeferred(backend))
    }

    class ControlledBackend : Backend {
        private val states = ConcurrentHashMap<Tunnel, Tunnel.State>()
        val appliedConfigs = ConcurrentHashMap<Tunnel, Config>()
        var blockUp = false
        var blockDown = false
        var failUp = false
        var failDown = false
        var singleActiveTunnel = false
        var blockStateRead = false
        val stateReadEntered = CountDownLatch(1)
        val releaseStateRead = CountDownLatch(1)
        val upEntered = CountDownLatch(1)
        val releaseUp = CountDownLatch(1)
        val downEntered = CountDownLatch(1)
        val releaseDown = CountDownLatch(1)
        var statisticsReader: (Tunnel) -> Statistics = {
            Statistics::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }
        override fun getRunningTunnelNames(): Set<String> =
            states.filterValues { it == Tunnel.State.UP }.keys.map { it.name }.toSet()
        override fun getState(tunnel: Tunnel): Tunnel.State {
            val snapshot = states[tunnel] ?: Tunnel.State.DOWN
            if (blockStateRead) {
                stateReadEntered.countDown()
                if (!releaseStateRead.await(5, TimeUnit.SECONDS))
                    throw IOException("State read fixture timed out")
            }
            return snapshot
        }
        override fun getStatistics(tunnel: Tunnel): Statistics = statisticsReader(tunnel)
        override fun getVersion(): String = "fixture"
        override fun isAlwaysOn(): Boolean = false
        override fun isLockdownEnabled(): Boolean = false
        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State {
            val target = if (state == Tunnel.State.TOGGLE) {
                if (getState(tunnel) == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
            } else state
            if (target == Tunnel.State.UP && blockUp) {
                upEntered.countDown()
                if (!releaseUp.await(5, TimeUnit.SECONDS))
                    throw IOException("Activation fixture timed out")
            }
            if (target == Tunnel.State.DOWN && blockDown) {
                downEntered.countDown()
                if (!releaseDown.await(5, TimeUnit.SECONDS))
                    throw IOException("Deactivation fixture timed out")
            }
            if (target == Tunnel.State.UP && failUp || target == Tunnel.State.DOWN && failDown)
                throw IOException("Injected backend failure")
            if (target == Tunnel.State.UP && singleActiveTunnel) {
                for (active in states.keys) {
                    if (active !== tunnel && states[active] == Tunnel.State.UP) {
                        states[active] = Tunnel.State.DOWN
                        active.onStateChange(Tunnel.State.DOWN)
                    }
                }
            }
            states[tunnel] = target
            if (config != null) appliedConfigs[tunnel] = config
            tunnel.onStateChange(target)
            return target
        }
    }

    class MemoryStore : ConfigStore {
        val configs = ConcurrentHashMap<String, Config>()
        val deleteAttempts = CopyOnWriteArrayList<String>()
        var failEnumerate = false
        var failDelete = false
        var deleteDefect = false
        val failDeleteNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        var failSave = false
        val failLoadNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val loadAttempts = CopyOnWriteArrayList<String>()
        val loadEntered = ConcurrentHashMap<String, CountDownLatch>()
        val releaseLoad = ConcurrentHashMap<String, CountDownLatch>()
        override fun create(name: String, config: Config): Config {
            check(configs.putIfAbsent(name, config) == null)
            return config
        }
        override fun delete(name: String) {
            deleteAttempts += name
            if (deleteDefect) throw ArithmeticException("Injected delete defect")
            if (failDelete || name in failDeleteNames) throw IOException("Injected delete failure")
            checkNotNull(configs.remove(name))
        }
        override fun enumerate(): Set<String> {
            if (failEnumerate) throw IOException("Injected enumeration failure")
            return configs.keys.toSet()
        }
        override fun load(name: String): Config {
            loadAttempts += name
            if (name in failLoadNames) throw IOException("Injected load failure")
            loadEntered[name]?.countDown()
            if (releaseLoad[name]?.await(5, TimeUnit.SECONDS) == false)
                throw IOException("Load fixture timed out")
            return checkNotNull(configs[name])
        }
        override fun rename(name: String, replacement: String) {
            check(!configs.containsKey(replacement))
            configs[replacement] = checkNotNull(configs.remove(name))
        }
        override fun save(name: String, config: Config): Config {
            if (failSave) throw IOException("Injected save failure")
            configs[name] = config
            return config
        }
    }

    companion object {
        fun profile(port: Int = 51820): Config = Config.parse("""
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            ListenPort = $port
            Address = 192.0.2.2/32
        """.trimIndent().byteInputStream())
    }
}
