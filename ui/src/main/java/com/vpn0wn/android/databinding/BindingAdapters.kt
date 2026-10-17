/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.databinding

import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
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
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView
import com.vpn0wn.android.BR
import com.vpn0wn.android.R
import com.vpn0wn.android.backend.HandshakeObservation
import com.vpn0wn.android.backend.Statistics
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.util.ClipboardUtils
import com.vpn0wn.android.util.QuantityFormatter
import com.vpn0wn.android.widget.TunnelSignalView
import com.vpn0wn.android.widget.TunnelThroughputView
import com.vpn0wn.android.widget.ToggleSwitch
import com.vpn0wn.android.widget.ToggleSwitch.OnBeforeCheckedChangeListener
import com.vpn0wn.android.widget.TvCardView
import com.vpn0wn.config.Attribute
import com.vpn0wn.config.Config
import com.vpn0wn.config.InetNetwork
import com.vpn0wn.config.Peer
import com.vpn0wn.crypto.Key
import java.util.Locale
import java.net.InetAddress
import java.util.Optional
import java.util.WeakHashMap

/**
 * Static methods for use by generated code in the Android data binding library.
 */
object BindingAdapters {
    @JvmStatic
    @BindingAdapter("copyText")
    fun setCopyText(view: View, text: CharSequence?) {
        val canCopy = view.isEnabled && !text.isNullOrEmpty()
        view.setOnClickListener(if (canCopy) View.OnClickListener { ClipboardUtils.copyText(view, text) } else null)
        view.isClickable = canCopy
        view.isFocusable = canCopy
    }

    @JvmStatic
    @BindingAdapter("tunnelSelectionInset")
    fun setTunnelSelectionInset(view: View, selecting: Boolean) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val inset = view.resources.getDimensionPixelSize(if (selecting) R.dimen.vpn0wn_selection_text_inset else R.dimen.vpn0wn_card_text_inset)
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
    @BindingAdapter("detailPeers", "detailPeerDiagnostics", "detailPeerState")
    fun setDetailPeers(
        view: LinearLayout,
        oldPeers: Iterable<Peer>?, @Suppress("UNUSED_PARAMETER") oldDiagnostics: Map<Key, ObservableTunnel.PeerHandshakeDiagnostics>?,
        @Suppress("UNUSED_PARAMETER") oldState: Tunnel.State?,
        newPeers: Iterable<Peer>?, newDiagnostics: Map<Key, ObservableTunnel.PeerHandshakeDiagnostics>?, newState: Tunnel.State?
    ) {
        setItems(view, oldPeers, R.layout.tunnel_detail_peer, newPeers, R.layout.tunnel_detail_peer)
        val available = newDiagnostics != null && newState != null && newState != Tunnel.State.DOWN
        for (index in 0 until view.childCount) {
            val binding = DataBindingUtil.getBinding<TunnelDetailPeerBinding>(view.getChildAt(index)) ?: continue
            binding.diagnosticsAvailable = available
            binding.diagnostics = if (available) binding.item?.publicKey?.let { newDiagnostics?.get(it) } else null
            binding.executePendingBindings()
        }
    }

    @JvmStatic
    @BindingAdapter("peerHandshakeObservation", "peerHandshakeAvailable")
    fun setPeerHandshakeObservation(view: TextView, observation: HandshakeObservation?, available: Boolean) {
        val text = when {
            !available -> R.string.vpn0wn_handshake_unavailable
            observation == null -> R.string.vpn0wn_handshake_unobserved
            else -> when (observation.event()) {
                HandshakeObservation.Event.ATTEMPT -> if (observation.retry()) R.string.vpn0wn_handshake_retry_attempted else R.string.vpn0wn_handshake_attempted
                HandshakeObservation.Event.SENT -> R.string.vpn0wn_handshake_sent
                HandshakeObservation.Event.SEND_FAILED -> R.string.vpn0wn_handshake_send_failed
                HandshakeObservation.Event.AUTHENTICATED -> R.string.vpn0wn_handshake_authenticated
                HandshakeObservation.Event.EXHAUSTED -> R.string.vpn0wn_handshake_exhausted
            }
        }
        view.setText(text)
    }

    @JvmStatic
    fun observedHandshakeEndpoint(observation: HandshakeObservation?): String = observation?.endpoint()?.toString() ?: "—"

    @JvmStatic
    fun observedHandshakeAddressFamily(observation: HandshakeObservation?): String = when (observation?.addressFamily()) {
        HandshakeObservation.AddressFamily.IPV4 -> "IPv4"
        HandshakeObservation.AddressFamily.IPV6 -> "IPv6"
        else -> "—"
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
        view.text = optionalText(text, "")
    }

    @JvmStatic
    fun optionalText(text: Optional<*>?, emptyText: String): String = text?.map { it.toString() }?.orElse(emptyText) ?: emptyText

    @JvmStatic
    @BindingAdapter("android:text")
    fun setInetNetworkSetText(view: TextView, networks: Iterable<InetNetwork?>?) {
        view.text = inetNetworksText(networks)
    }

    @JvmStatic
    fun inetNetworksText(networks: Iterable<InetNetwork?>?): String = if (networks != null) Attribute.join(networks) else ""

    @JvmStatic
    @BindingAdapter("android:text")
    fun setInetAddressSetText(view: TextView, addresses: Iterable<InetAddress?>?) {
        view.text = inetAddressesText(addresses)
    }

    @JvmStatic
    fun inetAddressesText(addresses: Iterable<InetAddress?>?): String =
        if (addresses != null) Attribute.join(addresses.map { it?.hostAddress }) else ""

    @JvmStatic
    @BindingAdapter("android:text")
    fun setStringSetText(view: TextView, strings: Iterable<String?>?) {
        view.text = stringsText(strings)
    }

    @JvmStatic
    fun stringsText(strings: Iterable<String?>?): String = if (strings != null) Attribute.join(strings) else ""

    @JvmStatic
    fun applicationsText(context: Context, config: Config?): String {
        val local = config?.getInterface()
        val excluded = local?.includedApplications?.isEmpty() == true
        val count = (if (excluded) local?.excludedApplications else local?.includedApplications)?.size ?: 0
        val resource = if (excluded) R.plurals.n_excluded_applications else R.plurals.n_included_applications
        return context.resources.getQuantityString(resource, count, count)
    }

    @JvmStatic
    fun applicationsLabel(context: Context, config: Config?): String {
        val local = config?.getInterface()
        val excluded = local?.includedApplications?.isEmpty() == true
        val count = (if (excluded) local?.excludedApplications else local?.includedApplications)?.size ?: 0
        val resource = if (excluded) R.plurals.set_excluded_applications else R.plurals.set_included_applications
        return context.resources.getQuantityString(resource, count, count)
    }

    @JvmStatic
    fun persistentKeepaliveText(context: Context, peer: Peer?): String {
        val seconds = peer?.persistentKeepalive?.orElse(0) ?: return ""
        return context.resources.getQuantityString(R.plurals.persistent_keepalive_seconds_unit, seconds, seconds)
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
        val wordmark = context.getString(R.string.vpn0wn_home_wordmark)
        if (tunnels == null) return wordmark
        return context.getString(R.string.vpn0wn_home_header_summary,
            wordmark, tunnels.count())
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
    @BindingAdapter("homeEmptyAppBar")
    fun setHomeEmptyAppBar(view: LinearLayout, oldAppBar: AppBarLayout?, newAppBar: AppBarLayout?) {
        if (oldAppBar === newAppBar) return
        val padding = view.resources.getDimensionPixelSize(R.dimen.vpn0wn_home_empty_padding)
        val listener = newAppBar?.let {
            AppBarLayout.OnOffsetChangedListener { appBar, offset ->
                // Material measures the scroll viewport with the header's still-hidden collapse space.
                val bottomPadding = padding + (appBar.totalScrollRange + offset).coerceAtLeast(0)
                if (view.paddingBottom != bottomPadding) {
                    view.setPaddingRelative(view.paddingStart, view.paddingTop, view.paddingEnd, bottomPadding)
                    if (view.isInLayout) {
                        // Header layout dispatches offsets after this child's gravity calculation was measured.
                        view.post { view.requestLayout() }
                    }
                }
            }
        }
        val previous = ListenerUtil.trackListener(view, listener, R.id.vpn0wn_home_empty_offset_listener)
        oldAppBar?.removeOnOffsetChangedListener(previous)
        if (newAppBar != null) {
            newAppBar.addOnOffsetChangedListener(listener)
            newAppBar.doOnLayout { appBar ->
                if (view.getTag(R.id.vpn0wn_home_empty_offset_listener) === listener) {
                    val params = appBar.layoutParams as CoordinatorLayout.LayoutParams
                    val behavior = params.behavior as AppBarLayout.Behavior
                    listener?.onOffsetChanged(newAppBar, behavior.topAndBottomOffset)
                }
            }
        } else {
            view.setPaddingRelative(view.paddingStart, view.paddingTop, view.paddingEnd, padding)
        }
    }

    @JvmStatic
    @BindingAdapter("homeHeaderTunnels")
    fun setHomeHeaderTunnels(view: TextView, tunnels: Iterable<ObservableTunnel>?) {
        val summary = homeHeaderSummary(view.context, tunnels)
        val wordmark = view.context.getString(R.string.vpn0wn_home_wordmark)
        val artwork = requireNotNull(ContextCompat.getDrawable(view.context, R.drawable.vpnown_wordmark))
        val height = view.resources.getDimensionPixelSize(R.dimen.vpnown_home_wordmark_height)
        val width = (height * artwork.intrinsicWidth.toFloat() / artwork.intrinsicHeight).roundToInt()
        artwork.setBounds(0, 0, width, height)
        view.contentDescription = summary.replace('ø', '0')
        val countLabel = summary.substring(wordmark.length).trimStart().replace(' ', '\u00a0')
        view.text = SpannableString("$wordmark $countLabel".trimEnd()).apply {
            setSpan(HomeWordmarkSpan(artwork),
                0, wordmark.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @JvmStatic
    @BindingAdapter("homeProtectionTunnels")
    fun setHomeProtectionTunnels(view: TextView, tunnels: Iterable<ObservableTunnel>?) {
        val mode = aggregateMode(tunnels)
        view.text = homeProtectionLabel(view.context, tunnels)
        view.setTextColor(ContextCompat.getColor(view.context, when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.vpn0wn_ink
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.vpn0wn_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_ink_2
        }))
    }

    @JvmStatic
    @BindingAdapter("homeProtectionDotTunnels")
    fun setHomeProtectionDotTunnels(view: View, tunnels: Iterable<ObservableTunnel>?) {
        val mode = aggregateMode(tunnels)
        val fill = ContextCompat.getColor(view.context, when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.vpn0wn_connected
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.vpn0wn_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_paper_3
        })
        val stroke = ContextCompat.getColor(view.context, when (mode) {
            TunnelSignalView.TunnelMode.UP         -> R.color.vpn0wn_connected
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.vpn0wn_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_rule_strong
        })
        val dot = view.getTag(R.id.vpn0wn_dot_drawable) as? GradientDrawable ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            view.setTag(R.id.vpn0wn_dot_drawable, this)
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
            TunnelSignalView.TunnelMode.UP         -> R.color.vpn0wn_signal_deep
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.vpn0wn_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_rule
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
            ?.let { view.context.getString(R.string.vpn0wn_detail_subtitle, it) }
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
                view.context.getString(R.string.vpn0wn_detail_status_waiting)
            TunnelSignalView.TunnelMode.ERROR ->
                view.context.getString(R.string.vpn0wn_detail_status_unreachable)
            TunnelSignalView.TunnelMode.DOWN ->
                view.context.getString(R.string.vpn0wn_detail_status_tap_up)
        }
    }

    @JvmStatic
    @BindingAdapter(requireAll = false, value = ["detailStatusDotTunnel", "detailStatusDotState", "detailStatusDotStatistics"])
    fun setDetailStatusDotTunnel(view: View, tunnel: ObservableTunnel?, state: Tunnel.State?, statistics: Statistics?) {
        val mode = tunnelMode(tunnel, state, statistics)
        val color = ContextCompat.getColor(view.context, modeColor(mode))
        val dot = view.getTag(R.id.vpn0wn_dot_drawable) as? GradientDrawable ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            view.setTag(R.id.vpn0wn_dot_drawable, this)
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
        TunnelSignalView.TunnelMode.UP         -> R.color.vpn0wn_signal_deep
        TunnelSignalView.TunnelMode.CONNECTING -> R.color.vpn0wn_warn
        TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_error
        TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_ink_3
    }

    private fun topologyModeLabel(context: Context, mode: TunnelSignalView.TunnelMode): String =
        context.getString(topologyModeLabelRes(mode))

    private fun detailModeLabel(context: Context, mode: TunnelSignalView.TunnelMode): String =
        context.getString(detailModeLabelRes(mode))

    private fun statusModeLabel(context: Context, mode: TunnelSignalView.TunnelMode): String =
        context.getString(statusModeLabelRes(mode))

    private fun topologyModeLabelRes(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.string.vpn0wn_mode_up
        TunnelSignalView.TunnelMode.CONNECTING -> R.string.vpn0wn_mode_connecting
        TunnelSignalView.TunnelMode.ERROR      -> R.string.vpn0wn_mode_no_signal
        TunnelSignalView.TunnelMode.DOWN       -> R.string.vpn0wn_mode_down
    }

    private fun detailModeLabelRes(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.string.vpn0wn_detail_status_up
        TunnelSignalView.TunnelMode.CONNECTING -> R.string.vpn0wn_handshake
        TunnelSignalView.TunnelMode.ERROR      -> R.string.vpn0wn_mode_no_signal
        TunnelSignalView.TunnelMode.DOWN       -> R.string.vpn0wn_detail_status_down
    }

    private fun statusModeLabelRes(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.string.vpn0wn_tunnel_status_active
        TunnelSignalView.TunnelMode.CONNECTING -> R.string.vpn0wn_tunnel_status_connecting
        TunnelSignalView.TunnelMode.ERROR      -> R.string.vpn0wn_tunnel_status_no_signal
        TunnelSignalView.TunnelMode.DOWN       -> R.string.vpn0wn_tunnel_status_inactive
    }

    private fun connectionLabelRes(mode: TunnelSignalView.TunnelMode): Int = when (mode) {
        TunnelSignalView.TunnelMode.UP         -> R.string.vpn0wn_home_connected
        TunnelSignalView.TunnelMode.CONNECTING -> R.string.vpn0wn_mode_connecting
        TunnelSignalView.TunnelMode.ERROR      -> R.string.vpn0wn_mode_no_signal
        TunnelSignalView.TunnelMode.DOWN       -> R.string.vpn0wn_home_disconnected
    }

    @JvmStatic
    fun tunnelPeerBadge(context: Context, config: Config?): String {
        val peer = config?.peers?.firstOrNull() ?: return context.getString(R.string.vpn0wn_no_peer)
        return if (peer.preSharedKey.isPresent) "PSK" else context.getString(R.string.vpn0wn_no_psk)
    }

    @JvmStatic
    fun tunnelRoute(context: Context, config: Config?): String {
        val address = config?.`interface`?.addresses?.firstOrNull()?.toString() ?: context.getString(R.string.vpn0wn_address_pending)
        val endpoint = config?.peers?.firstOrNull()?.endpoint?.map { it.toString() }?.orElse(null)
            ?: context.getString(R.string.vpn0wn_endpoint_pending)
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
        view.text = if (age == "--") age else view.context.getString(R.string.vpn0wn_home_handshake_age, age)
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
            TunnelSignalView.TunnelMode.UP         -> R.color.vpn0wn_signal_deep
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.vpn0wn_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_ink_3
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
            TunnelSignalView.TunnelMode.UP         -> R.color.vpn0wn_signal
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.vpn0wn_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_signal
        })
        val drawable = view.getTag(R.id.vpn0wn_rail_drawable) as? android.graphics.drawable.Drawable
            ?: ContextCompat.getDrawable(view.context, R.drawable.vpn0wn_active_rail)!!.mutate().also {
                view.setTag(R.id.vpn0wn_rail_drawable, it)
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
            TunnelSignalView.TunnelMode.UP         -> R.color.vpn0wn_signal_deep
            TunnelSignalView.TunnelMode.CONNECTING -> R.color.vpn0wn_warn
            TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_error
            TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_rule_strong
        }
        val thumb = when (mode) {
            TunnelSignalView.TunnelMode.UP,
            TunnelSignalView.TunnelMode.CONNECTING,
            TunnelSignalView.TunnelMode.ERROR      -> R.color.vpn0wn_paper
            TunnelSignalView.TunnelMode.DOWN       -> R.color.vpn0wn_ink_2
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
        view.setMtu(config?.`interface`?.mtu?.map { it.toString() }?.orElse("—") ?: "—")
    }

    @JvmStatic
    @BindingAdapter("isConnecting")
    fun setIsConnecting(view: TunnelThroughputView, connecting: Boolean) {
        view.connecting = connecting
    }

}

private class HomeWordmarkSpan(artwork: Drawable) : ImageSpan(artwork) {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val metrics = paint.fontMetricsInt
        val bounds = drawable.bounds
        val artworkTop = artworkTop(paint, text, end)
        fm?.apply {
            ascent = minOf(metrics.ascent, floor(artworkTop).toInt())
            descent = maxOf(metrics.descent, ceil(artworkTop + bounds.height()).toInt())
            top = minOf(metrics.top, ascent)
            bottom = maxOf(metrics.bottom, descent)
        }
        return bounds.width()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
                      top: Int, y: Int, bottom: Int, paint: Paint) {
        canvas.save()
        canvas.translate(x, y + artworkTop(paint, text, end))
        drawable.draw(canvas)
        canvas.restore()
    }

    private fun artworkTop(paint: Paint, text: CharSequence, end: Int): Float {
        val countBounds = Rect()
        paint.getTextBounds(text.toString(), end, text.length, countBounds)
        return countBounds.exactCenterY() - drawable.bounds.height() * WORDMARK_INK_CENTER
    }

    private companion object {
        // Approved wordmark ink spans y=38.6467..190 in its 206-unit viewport, including the cyan slash.
        const val WORDMARK_INK_CENTER = 0.5549677f
    }
}
