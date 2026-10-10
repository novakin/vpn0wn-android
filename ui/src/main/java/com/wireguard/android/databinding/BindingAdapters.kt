/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.databinding

import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TextAppearanceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.databinding.BindingAdapter
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableList
import androidx.databinding.ViewDataBinding
import androidx.databinding.adapters.ListenerUtil
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
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
    @JvmStatic
    @BindingAdapter("tunnelSelectionInset")
    fun setTunnelSelectionInset(view: View, selecting: Boolean) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val inset = view.resources.getDimensionPixelSize(if (selecting) R.dimen.tunnl_selection_text_inset else R.dimen.tunnl_card_text_inset)
        if (params.marginStart == inset) return
        params.marginStart = inset
        view.layoutParams = params
    }

    private const val SHORT_KEY_EDGE_LENGTH = 6
    private const val MIN_RATE_SAMPLE_INTERVAL_MS = 250L
    private data class RateSample(
        val statistics: Statistics,
        val rx: Long,
        val tx: Long,
        val timeMs: Long,
        val rxRate: Long = 0L,
        val txRate: Long = 0L,
        val rxRateText: String = "--",
        val txRateText: String = "--",
    )
    private val rateSamples = WeakHashMap<ObservableTunnel, RateSample>()
    private val connectingSince = WeakHashMap<ObservableTunnel, Long>()

    private const val CONNECTING_GRACE_MS = 30_000L
    private const val HANDSHAKE_STALE_MS = 180_000L

    private fun Statistics.latestHandshakeMillis(): Long? {
        return peers()
            .mapNotNull { peer(it)?.latestHandshakeEpochMillis() }
            .filter { it > 0L }
            .maxOrNull()
    }

    private fun tunnelMode(tunnel: ObservableTunnel?): TunnelSignalView.TunnelMode {
        val state = tunnel?.state
        val statistics = tunnel?.peekStatistics()
        return tunnelMode(tunnel, state, statistics)
    }

    private fun tunnelMode(tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?): TunnelSignalView.TunnelMode {
        val now = System.currentTimeMillis()
        val latest = statistics?.latestHandshakeMillis()
        return when {
            state == Tunnel.State.TOGGLE && tunnel?.transitionTarget != Tunnel.State.DOWN -> TunnelSignalView.TunnelMode.CONNECTING
            state != Tunnel.State.UP -> {
                tunnel?.let { synchronized(connectingSince) { connectingSince.remove(it) } }
                TunnelSignalView.TunnelMode.DOWN
            }
            latest != null && now - latest < HANDSHAKE_STALE_MS -> {
                tunnel?.let { synchronized(connectingSince) { connectingSince.remove(it) } }
                TunnelSignalView.TunnelMode.UP
            }
            latest != null -> TunnelSignalView.TunnelMode.ERROR
            else -> {
                val since = tunnel?.let {
                    synchronized(connectingSince) { connectingSince.getOrPut(it) { now } }
                } ?: now
                if (now - since < CONNECTING_GRACE_MS) {
                    TunnelSignalView.TunnelMode.CONNECTING
                } else {
                    TunnelSignalView.TunnelMode.ERROR
                }
            }
        }
    }

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
        } catch (_: NumberFormatException) {
            0
        }
    }

    @JvmStatic
    fun activeTunnelCount(tunnels: Iterable<ObservableTunnel>?): Int =
        tunnels?.count { tunnelMode(it) == TunnelSignalView.TunnelMode.UP } ?: 0

    @JvmStatic
    fun hasConnectingTunnel(tunnels: Iterable<ObservableTunnel>?): Boolean =
        tunnels?.any { tunnelMode(it) == TunnelSignalView.TunnelMode.CONNECTING } == true

    @JvmStatic
    fun hasErrorTunnel(tunnels: Iterable<ObservableTunnel>?): Boolean =
        tunnels?.any { tunnelMode(it) == TunnelSignalView.TunnelMode.ERROR } == true

    private fun aggregateMode(tunnels: Iterable<ObservableTunnel>?): TunnelSignalView.TunnelMode {
        var best = TunnelSignalView.TunnelMode.DOWN
        for (tunnel in tunnels ?: emptyList()) {
            when (tunnelMode(tunnel)) {
                TunnelSignalView.TunnelMode.UP -> return TunnelSignalView.TunnelMode.UP
                TunnelSignalView.TunnelMode.CONNECTING -> best = TunnelSignalView.TunnelMode.CONNECTING
                TunnelSignalView.TunnelMode.ERROR -> if (best != TunnelSignalView.TunnelMode.CONNECTING) {
                    best = TunnelSignalView.TunnelMode.ERROR
                }
                TunnelSignalView.TunnelMode.DOWN -> Unit
            }
        }
        return best
    }

    @JvmStatic
    fun homeHeaderSummary(context: Context, tunnels: Iterable<ObservableTunnel>?): String {
        return context.getString(R.string.tunnl_home_header_summary,
            context.getString(R.string.tunnl_home_wordmark), tunnels?.count() ?: 0)
    }

    @JvmStatic
    fun homeProtectionLabel(context: Context, tunnels: Iterable<ObservableTunnel>?): String =
        context.getString(connectionLabelRes(aggregateMode(tunnels)))

    @JvmStatic
    fun homeEndpointHost(tunnels: Iterable<ObservableTunnel>?): String {
        val activeTunnel = tunnels?.firstOrNull { tunnelMode(it) == TunnelSignalView.TunnelMode.UP }
        return activeTunnel
            ?.peekConfig()
            ?.peers
            ?.firstOrNull()
            ?.endpoint
            ?.map { endpoint -> endpoint.host }
            ?.orElse("—")
            ?: "—"
    }

    @JvmStatic
    @BindingAdapter("homeHeaderTunnels")
    fun setHomeHeaderTunnels(view: TextView, tunnels: Iterable<ObservableTunnel>?) {
        val summary = homeHeaderSummary(view.context, tunnels)
        val wordmark = view.context.getString(R.string.tunnl_home_wordmark)
        view.contentDescription = summary.replace('ø', '0')
        view.text = SpannableString(summary).apply {
            setSpan(TextAppearanceSpan(view.context, R.style.TunnlHomeWordmarkText),
                0, wordmark.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val zeroIndex = wordmark.indexOf('ø')
            setSpan(ForegroundColorSpan(ContextCompat.getColor(view.context, R.color.tunnl_signal)),
                zeroIndex, zeroIndex + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @JvmStatic
    @BindingAdapter("homeProtectionTunnels")
    fun setHomeProtectionTunnels(view: TextView, tunnels: Iterable<ObservableTunnel>?) {
        val mode = aggregateMode(tunnels)
        view.text = homeProtectionLabel(view.context, tunnels)
        view.setTextColor(ContextCompat.getColor(view.context, when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.tunnl_ink
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.tunnl_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_ink_2
        }))
    }

    @JvmStatic
    @BindingAdapter("homeProtectionDotTunnels")
    fun setHomeProtectionDotTunnels(view: View, tunnels: Iterable<ObservableTunnel>?) {
        val mode = aggregateMode(tunnels)
        val fill = ContextCompat.getColor(view.context, when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.tunnl_connected
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.tunnl_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_paper_3
        })
        val stroke = ContextCompat.getColor(view.context, when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.tunnl_connected
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.tunnl_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_rule_strong
        })
        val dot = view.getTag(R.id.tunnl_dot_drawable) as? GradientDrawable ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            view.setTag(R.id.tunnl_dot_drawable, this)
            view.background = this
        }
        dot.setColor(fill)
        dot.setStroke(view.resources.displayMetrics.density.roundToInt(), stroke)
    }

    @JvmStatic
    fun tunnelStatsVisible(tunnel: ObservableTunnel?): Boolean =
        tunnelStatsVisible(tunnel, tunnel?.state, tunnel?.statistics)

    @JvmStatic
    fun tunnelStatsVisible(tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?): Boolean =
        tunnelMode(tunnel, state, statistics) == TunnelSignalView.TunnelMode.UP

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["detailStatusStrokeTunnel", "detailStatusStrokeState", "detailStatusStrokeStatistics"])
    fun setDetailStatusStrokeTunnel(view: MaterialCardView, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        view.strokeColor = ContextCompat.getColor(view.context, when (tunnelMode(tunnel, state, statistics)) {
            TunnelSignalView.TunnelMode.UP         -> R.color.tunnl_signal_deep
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.tunnl_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_rule
        })
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["detailTopologyStatusTunnel", "detailTopologyStatusState", "detailTopologyStatusStatistics"])
    fun setDetailTopologyStatusTunnel(view: TextView, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        val mode = tunnelMode(tunnel, state, statistics)
        view.text = topologyModeLabel(view.context, mode)
        view.setTextColor(ContextCompat.getColor(view.context, modeColor(mode)))
    }

    @JvmStatic
    @BindingAdapter("detailTopBarSubtitleTunnel")
    fun setDetailTopBarSubtitleTunnel(view: TextView, tunnel: ObservableTunnel?) {
        view.text = tunnel?.name
            ?.uppercase(Locale.ROOT)
            ?.let { view.context.getString(R.string.tunnl_detail_subtitle, it) }
            ?: ""
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["detailStatusTitleTunnel", "detailStatusTitleState", "detailStatusTitleStatistics"])
    fun setDetailStatusTitleTunnel(view: TextView, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        val mode = tunnelMode(tunnel, state, statistics)
        view.text = detailModeLabel(view.context, mode)
        view.setTextColor(ContextCompat.getColor(view.context, modeColor(mode)))
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["detailStatusSubtitleTunnel", "detailStatusSubtitleState", "detailStatusSubtitleStatistics"])
    fun setDetailStatusSubtitleTunnel(view: TextView, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        val mode = tunnelMode(tunnel, state, statistics)
        view.text = when (mode) {
            TunnelSignalView.TunnelMode.UP ->
                tunnelHandshake(statistics)
            TunnelSignalView.TunnelMode.CONNECTING ->
                view.context.getString(R.string.tunnl_detail_status_waiting)
            TunnelSignalView.TunnelMode.ERROR ->
                view.context.getString(R.string.tunnl_detail_status_unreachable)
            TunnelSignalView.TunnelMode.DOWN ->
                view.context.getString(R.string.tunnl_detail_status_tap_up)
        }
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["detailStatusDotTunnel", "detailStatusDotState", "detailStatusDotStatistics"])
    fun setDetailStatusDotTunnel(view: View, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        val mode = tunnelMode(tunnel, state, statistics)
        val color = ContextCompat.getColor(view.context, modeColor(mode))
        val dot = view.getTag(R.id.tunnl_dot_drawable) as? GradientDrawable ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            view.setTag(R.id.tunnl_dot_drawable, this)
            view.background = this
        }
        dot.setColor(color)
        dot.setStroke(view.resources.displayMetrics.density.roundToInt(), color)
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["throughputModeTunnel", "throughputModeState", "throughputModeStatistics"])
    fun setThroughputModeTunnel(view: TunnelThroughputView, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        when (tunnelMode(tunnel, state, statistics)) {
            TunnelSignalView.TunnelMode.UP -> {
                view.live = true
                view.connecting = false
            }
            TunnelSignalView.TunnelMode.CONNECTING -> {
                view.live = false
                view.connecting = true
            }
            else -> {
                view.live = false
                view.connecting = false
            }
        }
    }

    private fun modeColor(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.color.tunnl_signal_deep
        TunnelSignalView.TunnelMode.CONNECTING -> R.color.tunnl_warn
        TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_error
        TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_ink_3
    }

    private fun topologyModeLabel(context: Context, mode: TunnelSignalView.TunnelMode): String =
        context.getString(topologyModeLabelRes(mode))

    private fun detailModeLabel(context: Context, mode: TunnelSignalView.TunnelMode): String =
        context.getString(detailModeLabelRes(mode))

    private fun statusModeLabel(context: Context, mode: TunnelSignalView.TunnelMode): String =
        context.getString(statusModeLabelRes(mode))

    private fun topologyModeLabelRes(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.string.tunnl_mode_up
        TunnelSignalView.TunnelMode.CONNECTING -> R.string.tunnl_mode_connecting
        TunnelSignalView.TunnelMode.ERROR      -> R.string.tunnl_mode_no_signal
        TunnelSignalView.TunnelMode.DOWN       -> R.string.tunnl_mode_down
    }

    private fun detailModeLabelRes(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.string.tunnl_detail_status_up
        TunnelSignalView.TunnelMode.CONNECTING -> R.string.tunnl_handshake
        TunnelSignalView.TunnelMode.ERROR      -> R.string.tunnl_mode_no_signal
        TunnelSignalView.TunnelMode.DOWN       -> R.string.tunnl_detail_status_down
    }

    private fun statusModeLabelRes(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.string.tunnl_tunnel_status_active
        TunnelSignalView.TunnelMode.CONNECTING -> R.string.tunnl_tunnel_status_connecting
        TunnelSignalView.TunnelMode.ERROR      -> R.string.tunnl_tunnel_status_no_signal
        TunnelSignalView.TunnelMode.DOWN       -> R.string.tunnl_tunnel_status_inactive
    }

    private fun connectionLabelRes(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.string.tunnl_home_connected
        TunnelSignalView.TunnelMode.CONNECTING -> R.string.tunnl_mode_connecting
        TunnelSignalView.TunnelMode.ERROR      -> R.string.tunnl_mode_no_signal
        TunnelSignalView.TunnelMode.DOWN       -> R.string.tunnl_home_disconnected
    }

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
    fun peerRx(statistics: Statistics.PeerStats?): String = QuantityFormatter.formatBytes(statistics?.rxBytes() ?: 0L)

    @JvmStatic
    fun peerTx(statistics: Statistics.PeerStats?): String = QuantityFormatter.formatBytes(statistics?.txBytes() ?: 0L)

    @JvmStatic
    fun peerTransferVisible(statistics: Statistics.PeerStats?): Boolean =
        statistics != null && (statistics.rxBytes() != 0L || statistics.txBytes() != 0L)

    @JvmStatic
    fun peerHandshakeVisible(statistics: Statistics.PeerStats?): Boolean =
        statistics != null && statistics.latestHandshakeEpochMillis() != 0L

    @JvmStatic
    fun peerHandshake(statistics: Statistics.PeerStats?): String {
        val handshake = statistics?.latestHandshakeEpochMillis() ?: return ""
        if (handshake == 0L) return ""
        return QuantityFormatter.formatEpochAgo(handshake)
    }

    @JvmStatic
    fun tunnelTx(statistics: Statistics?): String = formatBytesValue(statistics?.totalTx() ?: 0L)

    @JvmStatic
    fun tunnelRxRate(tunnel: ObservableTunnel?, statistics: Statistics?): String = tunnelRate(tunnel, statistics).first

    @JvmStatic
    fun tunnelTxRate(tunnel: ObservableTunnel?, statistics: Statistics?): String = tunnelRate(tunnel, statistics).second

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
    @BindingAdapter("homeHandshakeStatistics")
    fun setHomeHandshakeStatistics(view: TextView, statistics: Statistics?) {
        val age = tunnelHandshake(statistics)
        view.text = if (age == "--") age else view.context.getString(R.string.tunnl_home_handshake_age, age)
    }

    @JvmStatic
    fun tunnelCipher(config: Config?): String {
        val peer = config?.peers?.firstOrNull() ?: return "unbound"
        return if (peer.preSharedKey.isPresent) "ChaCha20 + PSK" else "ChaCha20"
    }

    private fun formatBytesValue(bytes: Long): String {
        val raw = QuantityFormatter.formatBytes(bytes)
        return raw.uppercase(Locale.getDefault())
            .replace("KIB", "KB")
            .replace("MIB", "MB")
            .replace("GIB", "GB")
            .replace("TIB", "TB")
    }

    private fun tunnelRate(tunnel: ObservableTunnel?, statistics: Statistics? = tunnel?.statistics): Pair<String, String> {
        tunnel ?: return "--" to "--"
        val stats = statistics ?: run {
            synchronized(rateSamples) { rateSamples.remove(tunnel) }
            return "--" to "--"
        }
        val now = SystemClock.elapsedRealtime()
        val rx = stats.totalRx()
        val tx = stats.totalTx()
        val sample = synchronized(rateSamples) {
            val previous = rateSamples[tunnel]
            val updated = if (previous?.statistics === stats) {
                previous
            } else if (previous == null || now <= previous.timeMs || rx < previous.rx || tx < previous.tx) {
                RateSample(stats, rx, tx, now)
            } else {
                val elapsed = now - previous.timeMs
                if (elapsed < MIN_RATE_SAMPLE_INTERVAL_MS) {
                    previous
                } else {
                    val rxRate = ((rx - previous.rx) * TimeUnit.SECONDS.toMillis(1)) / elapsed
                    val txRate = ((tx - previous.tx) * TimeUnit.SECONDS.toMillis(1)) / elapsed
                    RateSample(
                        stats,
                        rx,
                        tx,
                        now,
                        rxRate,
                        txRate,
                        "${formatBytesValue(rxRate)}/S",
                        "${formatBytesValue(txRate)}/S",
                    )
                }
            }
            rateSamples[tunnel] = updated
            updated
        }
        return sample.rxRateText to sample.txRateText
    }

    @JvmStatic
    @BindingAdapter("isUp")
    fun setIsUp(card: TvCardView, up: Boolean) {
        card.isUp = up
    }

    @JvmStatic
    @BindingAdapter("isUp")
    fun setIsUp(signalView: TunnelSignalView, up: Boolean) {
        signalView.mode = if (up) TunnelSignalView.TunnelMode.UP else TunnelSignalView.TunnelMode.DOWN
    }

    @JvmStatic
    @BindingAdapter("isUp")
    fun setIsUp(throughputView: TunnelThroughputView, up: Boolean) {
        throughputView.live = up
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["tunnelSignalTunnel", "tunnelSignalState", "tunnelSignalStatistics"])
    fun setTunnelSignalTunnel(view: TunnelSignalView, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        view.mode = tunnelMode(tunnel, state, statistics)
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["tunnelStatusTunnel", "tunnelStatusState", "tunnelStatusStatistics", "tunnelStatusConfig"])
    fun setTunnelStatusTunnel(view: TextView, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?, config: Config?) {
        val mode = tunnelMode(tunnel, state, statistics)
        val status = statusModeLabel(view.context, mode)
        val statusColor = ContextCompat.getColor(view.context, when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.tunnl_signal_deep
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.tunnl_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_ink_3
        })
        view.text = SpannableString("$status · ${tunnelCipher(config)}").apply {
            setSpan(ForegroundColorSpan(statusColor), 0, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["tunnelRailTunnel", "tunnelRailState", "tunnelRailStatistics"])
    fun setTunnelRailTunnel(view: View, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        val mode = tunnelMode(tunnel, state, statistics)
        if (mode == TunnelSignalView.TunnelMode.DOWN) {
            view.visibility = View.INVISIBLE
            return
        }
        view.visibility = View.VISIBLE
        val color = ContextCompat.getColor(view.context, when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.tunnl_signal
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.tunnl_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_signal
        })
        val drawable = view.getTag(R.id.tunnl_rail_drawable) as? android.graphics.drawable.Drawable
            ?: ContextCompat.getDrawable(view.context, R.drawable.tunnl_active_rail)!!.mutate().also {
                view.setTag(R.id.tunnl_rail_drawable, it)
                view.background = it
            }
        DrawableCompat.setTint(drawable, color)
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["tunnelSwitchModeTunnel", "tunnelSwitchModeState", "tunnelSwitchModeStatistics"])
    fun setTunnelSwitchModeTunnel(view: ToggleSwitch, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        view.setCheckedInternal(state == Tunnel.State.UP || state == Tunnel.State.TOGGLE && tunnel?.transitionTarget != Tunnel.State.DOWN)
        view.isEnabled = state != Tunnel.State.TOGGLE
        val mode = tunnelMode(tunnel, state, statistics)
        val track = when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.tunnl_signal_deep
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.tunnl_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_rule_strong
        }
        val thumb = when (mode) {
            TunnelSignalView.TunnelMode.UP,
            TunnelSignalView.TunnelMode.CONNECTING,
            TunnelSignalView.TunnelMode.ERROR      -> R.color.tunnl_paper
            TunnelSignalView.TunnelMode.DOWN       -> R.color.tunnl_ink_2
        }
        view.trackTintList = ColorStateList.valueOf(ContextCompat.getColor(view.context, track))
        view.thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(view.context, thumb))
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["trafficTunnel", "trafficStatistics"])
    fun setTrafficTunnel(throughputView: TunnelThroughputView, tunnel: ObservableTunnel?, @Suppress("UNUSED_PARAMETER") statistics: Statistics?) {
        val rates = tunnelRate(tunnel, statistics)
        throughputView.setRates(rates.first, rates.second)
    }

    @JvmStatic
    @BindingAdapter("isDeleting")
    fun setIsDeleting(card: TvCardView, deleting: Boolean) {
        card.isDeleting = deleting
    }

    @JvmStatic
    fun shortKey(key: String?): String {
        if (key.isNullOrEmpty()) return "—"
        return if (key.length > SHORT_KEY_EDGE_LENGTH * 2) key.take(SHORT_KEY_EDGE_LENGTH) + "…" + key.takeLast(SHORT_KEY_EDGE_LENGTH) else key
    }

    @JvmStatic
    @BindingAdapter("topologyConfig")
    fun setTopologyConfig(view: TunnelThroughputView, config: Config?) {
        val address  = config?.`interface`?.addresses?.firstOrNull()?.toString() ?: "—"
        val endpoint = config?.peers?.firstOrNull()?.endpoint?.map { it.toString() }?.orElse("—") ?: "—"
        view.setAddress(address)
        view.setEndpoint(endpoint)
    }

    @JvmStatic
    @BindingAdapter("isConnecting")
    fun setIsConnecting(view: TunnelThroughputView, connecting: Boolean) {
        view.connecting = connecting
    }

}
