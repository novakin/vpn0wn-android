/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import com.vpn0wn.android.activity.SettingsActivity
import com.vpn0wn.android.model.AutomationNetwork
import com.vpn0wn.android.model.AutomationNetworks
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NetworkMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generation = Any()
    private val candidates = mutableMapOf<Network, AutomationNetwork>()
    private val callbackLock = Any()
    private var appDefault: AutomationNetwork? = null
    private var registered = false
    private var promoted = false
    private var command = 0L
    private val controller get() = Application.getTunnelManager().networkAutomation
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish {
            candidates[network] = AutomationNetwork(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish {
            candidates[network]?.let { candidates[network] = it.copy(capabilities = NetworkCapabilities(capabilities)) }
        }

        override fun onLosing(network: Network, maxMsToLive: Int) = publish {
            candidates[network]?.let { candidates[network] = it.copy(losing = true) }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = publish {
            candidates[network]?.let { candidates[network] = it.copy(blocked = blocked) }
        }

        override fun onLost(network: Network) = publish { candidates.remove(network) }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish { appDefault = AutomationNetwork(network) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish {
            if (appDefault?.network == network) appDefault = appDefault?.copy(capabilities = NetworkCapabilities(capabilities))
        }
        override fun onLost(network: Network) = publish {
            if (appDefault?.network == network) appDefault = null
        }
    }

    private fun publish(update: () -> Unit) {
        val snapshot = synchronized(callbackLock) {
            val sequence = controller.nextSequence()
            update()
            AutomationNetworks(generation, sequence, candidates.values.toList(), appDefault?.takeIf { it.eligible }?.network)
        }
        serviceScope.launch { controller.observe(snapshot) }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.automation_notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION, notification)
            promoted = true
        } catch (_: SecurityException) {
            controller.startFailed()
            stopSelf()
        } catch (_: IllegalStateException) {
            controller.startFailed()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoted) return START_NOT_STICKY
        val capturedCommand = ++command
        serviceScope.launch {
            try {
                if (intent?.action == DISABLE) {
                    try {
                        controller.disable()
                    } catch (_: IOException) {
                        if (command == capturedCommand) {
                            controller.startFailed()
                            if (!registered) finish()
                        }
                        return@launch
                    }
                    if (command == capturedCommand) finish()
                    return@launch
                }
                when (intent?.action) {
                    ENABLE -> controller.enable()
                    RESUME -> controller.resume()
                    else -> if (!controller.mayStart()) {
                        if (command == capturedCommand) finish()
                        return@launch
                    }
                }
                if (command != capturedCommand) return@launch
                if (!registered) {
                    val admitted = controller.attach(generation)
                    if (command != capturedCommand) return@launch
                    if (admitted && !registered) register()
                }
                if (!registered) {
                    finish()
                    return@launch
                }
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION, notification())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                if (command == capturedCommand) {
                    controller.startFailed()
                    finish()
                }
            } catch (_: SecurityException) {
                if (command == capturedCommand) {
                    controller.startFailed()
                    finish()
                }
            } catch (_: IllegalStateException) {
                if (command == capturedCommand) {
                    controller.startFailed()
                    finish()
                }
            }
        }
        return START_STICKY
    }

    private fun register() {
        val manager = connectivity ?: throw IOException("Connectivity service unavailable")
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .build()
        manager.registerNetworkCallback(request, callback)
        try {
            manager.registerDefaultNetworkCallback(defaultCallback)
        } catch (failure: RuntimeException) {
            manager.unregisterNetworkCallback(callback)
            throw failure
        }
        registered = true
    }

    private fun unregister() {
        if (registered) {
            registered = false
            connectivity?.unregisterNetworkCallback(callback)
            connectivity?.unregisterNetworkCallback(defaultCallback)
        }
        controller.detach(generation)
        synchronized(callbackLock) {
            candidates.clear()
            appDefault = null
        }
    }

    private fun notification(): Notification {
        val settings = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val disable = PendingIntent.getService(this, 1, Intent(this, NetworkMonitorService::class.java).setAction(DISABLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.automation_notification_title))
            .setContentText(getString(R.string.automation_notification_summary))
            .setContentIntent(settings)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.automation_disable), disable).build())
            .build()
    }

    private fun finish() {
        unregister()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unregister()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "network_automation"
        private const val NOTIFICATION = 74
        internal const val ENABLE = "com.vpn0wn.android.automation.ENABLE"
        internal const val RESUME = "com.vpn0wn.android.automation.RESUME"
        internal const val DISABLE = "com.vpn0wn.android.automation.DISABLE"

        internal fun start(context: Context, action: String? = null): Boolean = try {
            val intent = Intent(context, NetworkMonitorService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            true
        } catch (_: SecurityException) {
            Application.getTunnelManager().networkAutomation.startFailed()
            false
        } catch (_: IllegalStateException) {
            Application.getTunnelManager().networkAutomation.startFailed()
            false
        }

        internal suspend fun startIfEnabled(context: Context) {
            if (Application.getTunnelManager().networkAutomation.mayStart()) start(context)
        }
    }
}
