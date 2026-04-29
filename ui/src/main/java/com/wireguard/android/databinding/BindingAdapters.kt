/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.databinding

import android.text.InputFilter
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.databinding.BindingAdapter
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableList
import androidx.databinding.ViewDataBinding
import androidx.databinding.adapters.ListenerUtil
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.backend.Statistics
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.android.widget.TunnelSignalView
import com.wireguard.android.widget.TunnelThroughputView
import com.wireguard.android.widget.ToggleSwitch
import com.wireguard.android.widget.ToggleSwitch.OnBeforeCheckedChangeListener
import com.wireguard.android.widget.TvCardView
import com.wireguard.config.Attribute
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import java.util.Locale
import java.net.InetAddress
import java.util.Optional
import java.util.WeakHashMap

/**
 * Static methods for use by generated code in the Android data binding library.
 */
object BindingAdapters {
    private data class RateSample(val rx: Long, val tx: Long, val timeMs: Long, val rxRate: Long = 0L, val txRate: Long = 0L)
    private val rateSamples = WeakHashMap<ObservableTunnel, RateSample>()

    @JvmStatic
    @BindingAdapter("checked")
    fun setChecked(view: ToggleSwitch, checked: Boolean) {
        view.setCheckedInternal(checked)
    }

    @JvmStatic
    @BindingAdapter("filter")
    fun setFilter(view: TextView, filter: InputFilter) {
        view.filters = arrayOf(filter)
    }

    @JvmStatic
    @BindingAdapter("items", "layout", "fragment")
    fun <E> setItems(
        view: LinearLayout,
        oldList: ObservableList<E>?, oldLayoutId: Int, @Suppress("UNUSED_PARAMETER") oldFragment: Fragment?,
        newList: ObservableList<E>?, newLayoutId: Int, newFragment: Fragment?
    ) {
        if (oldList === newList && oldLayoutId == newLayoutId)
            return
        var listener: ItemChangeListener<E>? = ListenerUtil.getListener(view, R.id.item_change_listener)
        // If the layout changes, any existing listener must be replaced.
        if (listener != null && oldList != null && oldLayoutId != newLayoutId) {
            listener.setList(null)
            listener = null
            // Stop tracking the old listener.
            ListenerUtil.trackListener<Any?>(view, null, R.id.item_change_listener)
        }
        // Avoid adding a listener when there is no new list or layout.
        if (newList == null || newLayoutId == 0)
            return
        if (listener == null) {
            listener = ItemChangeListener(view, newLayoutId, newFragment)
            ListenerUtil.trackListener(view, listener, R.id.item_change_listener)
        }
        // Either the list changed, or this is an entirely new listener because the layout changed.
        listener.setList(newList)
    }

    @JvmStatic
    @BindingAdapter("items", "layout")
    fun <E> setItems(
        view: LinearLayout,
        oldList: Iterable<E>?, oldLayoutId: Int,
        newList: Iterable<E>?, newLayoutId: Int
    ) {
        if (oldList === newList && oldLayoutId == newLayoutId)
            return
        view.removeAllViews()
        if (newList == null)
            return
        val layoutInflater = LayoutInflater.from(view.context)
        for (item in newList) {
            val binding = DataBindingUtil.inflate<ViewDataBinding>(layoutInflater, newLayoutId, view, false)
            binding.setVariable(BR.collection, newList)
            binding.setVariable(BR.item, item)
            binding.executePendingBindings()
            view.addView(binding.root)
        }
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["items", "layout", "configurationHandler"])
    fun <K, E : Keyed<out K>> setItems(
        view: RecyclerView,
        oldList: ObservableKeyedArrayList<K, E>?, oldLayoutId: Int,
        @Suppress("UNUSED_PARAMETER") oldRowConfigurationHandler: RowConfigurationHandler<*, *>?,
        newList: ObservableKeyedArrayList<K, E>?, newLayoutId: Int,
        newRowConfigurationHandler: RowConfigurationHandler<*, *>?
    ) {
        if (view.layoutManager == null)
            view.layoutManager = LinearLayoutManager(view.context, RecyclerView.VERTICAL, false)
        if (oldList === newList && oldLayoutId == newLayoutId)
            return
        // The ListAdapter interface is not generic, so this cannot be checked.
        @Suppress("UNCHECKED_CAST") var adapter = view.adapter as? ObservableKeyedRecyclerViewAdapter<K, E>?
        // If the layout changes, any existing adapter must be replaced.
        if (adapter != null && oldList != null && oldLayoutId != newLayoutId) {
            adapter.setList(null)
            adapter = null
        }
        // Avoid setting an adapter when there is no new list or layout.
        if (newList == null || newLayoutId == 0)
            return
        if (adapter == null) {
            adapter = ObservableKeyedRecyclerViewAdapter(view.context, newLayoutId, newList)
            view.adapter = adapter
        }
        adapter.setRowConfigurationHandler(newRowConfigurationHandler)
        // Either the list changed, or this is an entirely new listener because the layout changed.
        adapter.setList(newList)
    }

    @JvmStatic
    @BindingAdapter("onBeforeCheckedChanged")
    fun setOnBeforeCheckedChanged(
        view: ToggleSwitch,
        listener: OnBeforeCheckedChangeListener?
    ) {
        view.setOnBeforeCheckedChangeListener(listener)
    }

    @JvmStatic
    @BindingAdapter("onFocusChange")
    fun setOnFocusChange(
        view: EditText,
        listener: View.OnFocusChangeListener?
    ) {
        view.onFocusChangeListener = listener
    }

    @JvmStatic
    @BindingAdapter("android:text")
    fun setOptionalText(view: TextView, text: Optional<*>?) {
        view.text = text?.map { it.toString() }?.orElse("") ?: ""
    }

    @JvmStatic
    @BindingAdapter("android:text")
    fun setInetNetworkSetText(view: TextView, networks: Iterable<InetNetwork?>?) {
        view.text = if (networks != null) Attribute.join(networks) else ""
    }

    @JvmStatic
    @BindingAdapter("android:text")
    fun setInetAddressSetText(view: TextView, addresses: Iterable<InetAddress?>?) {
        view.text = if (addresses != null) Attribute.join(addresses.map { it?.hostAddress }) else ""
    }

    @JvmStatic
    @BindingAdapter("android:text")
    fun setStringSetText(view: TextView, strings: Iterable<String?>?) {
        view.text = if (strings != null) Attribute.join(strings) else ""
    }

    @JvmStatic
    fun tryParseInt(s: String?): Int {
        if (s == null)
            return 0
        return try {
            Integer.parseInt(s)
        } catch (_: Throwable) {
            0
        }
    }

    @JvmStatic
    fun activeTunnelCount(tunnels: Iterable<ObservableTunnel>?): Int =
        tunnels?.count { it.state == com.wireguard.android.backend.Tunnel.State.UP } ?: 0

    @JvmStatic
    fun tunnelPeerBadge(config: Config?): String {
        val peer = config?.peers?.firstOrNull() ?: return "NO PEER"
        return if (peer.preSharedKey.isPresent) "PSK" else "NO PSK"
    }

    @JvmStatic
    fun tunnelRoute(config: Config?): String {
        val address = config?.`interface`?.addresses?.firstOrNull()?.toString() ?: "address pending"
        val endpoint = config?.peers?.firstOrNull()?.endpoint?.map { it.toString() }?.orElse("endpoint pending") ?: "endpoint pending"
        return "$address -> $endpoint"
    }

    @JvmStatic
    fun tunnelRx(statistics: Statistics?): String = formatBytesValue(statistics?.totalRx() ?: 0L)

    @JvmStatic
    fun tunnelTx(statistics: Statistics?): String = formatBytesValue(statistics?.totalTx() ?: 0L)

    @JvmStatic
    fun tunnelRxRate(tunnel: ObservableTunnel?, @Suppress("UNUSED_PARAMETER") statistics: Statistics?): String = tunnelRate(tunnel).first

    @JvmStatic
    fun tunnelTxRate(tunnel: ObservableTunnel?, @Suppress("UNUSED_PARAMETER") statistics: Statistics?): String = tunnelRate(tunnel).second

    @JvmStatic
    fun tunnelHandshake(statistics: Statistics?): String {
        val latest = statistics
            ?.peers()
            ?.mapNotNull { statistics.peer(it)?.latestHandshakeEpochMillis() }
            ?.filter { it > 0L }
            ?.maxOrNull()
        return if (latest == null) "--" else QuantityFormatter.formatEpochAgo(latest)
    }

    @JvmStatic
    fun tunnelCipher(config: Config?): String {
        val peer = config?.peers?.firstOrNull() ?: return "unbound"
        return if (peer.preSharedKey.isPresent) "ChaCha20 + PSK" else "ChaCha20"
    }

    private fun formatBytesValue(bytes: Long): String {
        val raw = QuantityFormatter.formatBytes(bytes)
        return raw.uppercase(Locale.getDefault()).replace("I", "")
    }

    private fun tunnelRate(tunnel: ObservableTunnel?): Pair<String, String> {
        tunnel ?: return "--" to "--"
        val stats = tunnel.statistics ?: return "--" to "--"
        val now = SystemClock.elapsedRealtime()
        val rx = stats.totalRx()
        val tx = stats.totalTx()
        val sample = synchronized(rateSamples) {
            val previous = rateSamples[tunnel]
            val updated = if (previous == null || now <= previous.timeMs || rx < previous.rx || tx < previous.tx) {
                RateSample(rx, tx, now)
            } else {
                val elapsed = now - previous.timeMs
                if (elapsed < 250L) {
                    previous
                } else {
                    RateSample(
                        rx,
                        tx,
                        now,
                        ((rx - previous.rx) * 1000L) / elapsed,
                        ((tx - previous.tx) * 1000L) / elapsed
                    )
                }
            }
            rateSamples[tunnel] = updated
            updated
        }
        return "${formatBytesValue(sample.rxRate)}/S" to "${formatBytesValue(sample.txRate)}/S"
    }

    @JvmStatic
    @BindingAdapter("isUp")
    fun setIsUp(card: TvCardView, up: Boolean) {
        card.isUp = up
    }

    @JvmStatic
    @BindingAdapter("isUp")
    fun setIsUp(signalView: TunnelSignalView, up: Boolean) {
        signalView.live = up
    }

    @JvmStatic
    @BindingAdapter("isUp")
    fun setIsUp(throughputView: TunnelThroughputView, up: Boolean) {
        throughputView.live = up
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["trafficTunnel", "trafficStatistics"])
    fun setTrafficTunnel(throughputView: TunnelThroughputView, tunnel: ObservableTunnel?, @Suppress("UNUSED_PARAMETER") statistics: Statistics?) {
        val rates = tunnelRate(tunnel)
        throughputView.setRates(rates.first, rates.second)
    }

    @JvmStatic
    @BindingAdapter("isDeleting")
    fun setIsDeleting(card: TvCardView, deleting: Boolean) {
        card.isDeleting = deleting
    }
}
