/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.setPadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.Application
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.R
import com.vpn0wn.android.databinding.BindingAdapters
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.provisioning.VpsDeployAction
import com.vpn0wn.android.provisioning.VpsDeployHealthCheck
import com.vpn0wn.android.provisioning.VpsDeployServer
import com.vpn0wn.android.provisioning.VpsDeployServerStatus
import com.vpn0wn.android.provisioning.VpsDeployUser
import com.vpn0wn.android.provisioning.VpsDeployUserStatus
import com.vpn0wn.android.provisioning.VpsDeployMapper
import com.vpn0wn.android.util.vpn0wnStyle
import com.vpn0wn.android.widget.VpsQrCodeView
import com.vpn0wn.android.widget.VpsCornerLayout
import com.vpn0wn.android.widget.VpsSwitchGlyphView
import com.vpn0wn.android.widget.VpsTerminalStreamView
import com.vpn0wn.android.widget.VpsTopologyView
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VpsProvisioningFragment : BaseFragment() {
    private lateinit var root: FrameLayout
    private var screen: Screen = Screen.Fleet
    private var filter: VpsDeployServerStatus? = null
    private var qrUser: VpsDeployUser? = null
    private var applyRunning = false
    private var applyDone = false
    private var provisionStep = SSH_STEP
    private var authMode = AuthMode.KEY
    private var trustHost = true
    private var backCallback: OnBackPressedCallback? = null
    private var applyJob: Job? = null
    private var storeJob: Job? = null
    private var provisionForm = ProvisionForm()
    private val servers = mutableListOf<VpsDeployServer>()
    private val serverStore by lazy { Application.getVpsServerStore() }
    private val cachedMonoTypeface by lazy {
        ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
    }
    private val cachedBoldMonoTypeface by lazy {
        Typeface.create(cachedMonoTypeface, Typeface.BOLD)
    }
    private val cachedUiTypeface by lazy {
        ResourcesCompat.getFont(requireContext(), R.font.inter_tight) ?: Typeface.DEFAULT
    }
    private val cachedBoldUiTypeface by lazy {
        Typeface.create(cachedUiTypeface, Typeface.BOLD)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = FrameLayout(requireContext()).apply {
            setBackgroundColor(color(R.color.vpn0wn_paper))
        }
        backCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { goBack() }
        render()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectServers()
    }

    override fun onDestroyView() {
        resetProvisionSession()
        storeJob?.cancel()
        storeJob = null
        backCallback?.remove()
        backCallback = null
        super.onDestroyView()
    }

    // This screen listens through BaseFragment but does not display a selected tunnel.
    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) = Unit

    private fun render() {
        root.removeAllViews()
        root.addView(
            when (val current = screen) {
                Screen.Fleet -> fleetScreen()
                is Screen.Detail -> server(current.serverId)?.let { detailScreen(it) } ?: fleetScreen()
                is Screen.Provision -> provisionScreen()
                is Screen.User -> {
                    val selectedServer = server(current.serverId)
                    val selectedUser = selectedServer?.users?.find { it.id == current.userId }
                    if (selectedServer != null && selectedUser != null) userScreen(selectedServer, selectedUser) else fleetScreen()
                }
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        val user = qrUser
        val currentScreen = screen
        if (user != null && currentScreen is Screen.User) {
            val server = server(currentScreen.serverId) ?: return
            root.addView(qrOverlay(server, user), FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun collectServers() {
        storeJob?.cancel()
        storeJob = viewLifecycleOwner.lifecycleScope.launch {
            serverStore.servers.collect { storedServers ->
                val now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
                servers.clear()
                servers.addAll(storedServers.map { VpsDeployMapper.fromStored(it, now) })
                if (::root.isInitialized) render()
            }
        }
    }

    private fun fleetScreen(): View {
        val container = vertical()
        container.addView(topBar(title = str(R.string.vpn0wn_servers_title).lowercase(Locale.ROOT), sub = str(R.string.vpn0wn_servers_summary, servers.size, servers.sumOf { it.users.size }), leftIcon = R.drawable.ic_vpn0wn_chevron_left, onLeft = { goBack() }, right = listOf(iconButton(R.drawable.ic_vpn0wn_logs) { toast(str(R.string.vpn0wn_server_logs_pending)) }, iconButton(R.drawable.ic_vpn0wn_settings) { toast(str(R.string.vpn0wn_server_settings_pending)) }), big = true))
        container.addView(statusTiles())
        val (scrollView, scroll) = scrollContent()
        scroll.addView(sectionHeader(1, str(R.string.vpn0wn_server_fleet), filter?.let { str(it.shortLabelRes) } ?: str(R.string.vpn0wn_self_hosted)))
        val displayed = if (filter == null) servers else servers.filter { it.status == filter }
        displayed.forEach { scroll.addView(serverCard(it)) }
        if (displayed.isEmpty()) scroll.addView(emptyFleetCard())
        scroll.addView(dashedButton(str(R.string.vpn0wn_add_server)) {
            provisionForm.clear()
            provisionStep = SSH_STEP
            applyRunning = false
            applyDone = false
            provisionForm = ProvisionForm()
            authMode = AuthMode.KEY
            trustHost = true
            screen = Screen.Provision
            render()
        })
        container.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        return container
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun emptyFleetCard(): View = card().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(label(str(R.string.vpn0wn_no_servers)))
        addView(mono(str(R.string.vpn0wn_no_servers_summary), 11, R.color.vpn0wn_ink_2).apply { setPadding(0, dp(6), 0, 0) })
    }.withMargins(bottom = 10)

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun statusTiles(): View {
        val row = horizontal().apply {
            setBackgroundColor(color(R.color.vpn0wn_paper_2))
        }
        val tiles = mutableListOf<Pair<VpsDeployServerStatus?, String>>()
        tiles.add(null to str(R.string.vpn0wn_filter_all))
        VpsDeployServerStatus.values().filter { status -> servers.any { it.status == status } }.forEach { tiles.add(it to str(it.shortLabelRes)) }
        tiles.forEach { (status, label) ->
            val count = if (status == null) servers.size else servers.count { it.status == status }
            row.addView(
                statTile(label, count.toString(), status?.dot ?: "", status?.colorRes ?: R.color.vpn0wn_ink, filter == status) {
                    filter = if (filter == status) null else status
                    render()
                },
                LinearLayout.LayoutParams(0, dp(62), 1f),
            )
        }
        return vertical().apply {
            addView(row)
            addView(View(requireContext()).apply { setBackgroundColor(color(R.color.vpn0wn_rule)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun serverCard(server: VpsDeployServer): View {
        val meta = server.status
        val failing = server.health.any { it.ok == false }
        val stroke = when {
            failing -> R.color.vpn0wn_error
            server.status == VpsDeployServerStatus.PROVISIONING -> R.color.vpn0wn_warn
            else -> R.color.vpn0wn_rule
        }
        val card = card(stroke).apply {
            setOnClickListener {
                screen = Screen.Detail(server.id)
                render()
            }
        }
        val railRow = horizontal()
        railRow.addView(View(requireContext()).apply { setBackgroundColor(color(meta.colorRes)) }, LinearLayout.LayoutParams(dp(3), -1))
        val body = vertical().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        body.addView(horizontal().apply {
            addView(label(str(meta.labelRes), meta.colorRes), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("${server.provider.uppercase(Locale.ROOT)} · ${server.region.uppercase(Locale.ROOT)}"))
        })
        body.addView(mono(server.name, 16, R.color.vpn0wn_ink, Typeface.BOLD).apply { setPadding(0, dp(8), 0, 0) })
        body.addView(mono(str(R.string.vpn0wn_server_card_summary, "${server.endpoint}:${server.port}", server.users.size, server.engine), 10, R.color.vpn0wn_ink_3).apply { setPadding(0, dp(4), 0, dp(10)) })
        body.addView(metricRow(listOf(str(R.string.vpn0wn_metric_cidr) to server.cidr, str(R.string.vpn0wn_metric_uptime) to server.uptime, str(R.string.vpn0wn_metric_sync) to server.lastSync)))
        body.addView(horizontalWrap(server.health.map { healthBadge(it) }).apply { setPadding(0, dp(10), 0, 0) })
        railRow.addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(railRow)
        return card.withMargins(bottom = 10)
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun detailScreen(server: VpsDeployServer): View {
        val container = vertical()
        container.addView(topBar(title = server.name, sub = str(R.string.vpn0wn_server_subtitle, server.region.uppercase(Locale.ROOT)), leftIcon = R.drawable.ic_vpn0wn_chevron_left, onLeft = { screen = Screen.Fleet; render() }, right = listOf(iconButton(R.drawable.ic_vpn0wn_refresh) { toast(str(R.string.vpn0wn_server_synced)) }, iconButton(R.drawable.ic_vpn0wn_more) { toast(str(R.string.vpn0wn_more_pending)) })))
        val (scrollView, scroll) = scrollContent()
        scroll.addView(VpsTopologyView(requireContext()).apply {
            bind(server)
            minimumHeight = dp(164)
            background = panelBg()
        }.withMargins(bottom = 14))
        scroll.addView(sectionHeader(1, str(R.string.vpn0wn_section_health), str(server.status.labelRes)))
        scroll.addView(panel {
            configLine(str(R.string.vpn0wn_label_endpoint), "${server.endpoint}:${server.port}", copy = true)
            configLine(str(R.string.vpn0wn_label_distro), server.distro)
            configLine(str(R.string.vpn0wn_label_engine), "${server.engine} · ${server.version}")
            configLine(str(R.string.vpn0wn_label_uptime), server.uptime)
            configLine(str(R.string.vpn0wn_label_last_sync), server.lastSync)
            addView(label(str(R.string.vpn0wn_checks)).apply { setPadding(dp(14), dp(10), dp(14), dp(6)) })
            addView(horizontalWrap(server.health.map { healthBadge(it) }).apply { setPadding(dp(14), 0, dp(14), dp(10)) })
        })
        scroll.addView(sectionHeader(2, str(R.string.vpn0wn_section_vpn_users), str(R.string.vpn0wn_peers_count, server.users.size)))
        scroll.addView(panel {
            if (server.users.isEmpty()) {
                addView(mono(str(R.string.vpn0wn_no_peers_yet), 11, R.color.vpn0wn_ink_3).apply { setPadding(dp(14), dp(14), dp(14), dp(14)) })
            } else {
                server.users.forEachIndexed { index, user -> addView(userRow(server, user, index)) }
            }
            addView(rowButton(str(R.string.vpn0wn_add_vpn_user)) {
                addUser()
            })
        })
        scroll.addView(sectionHeader(3, str(R.string.vpn0wn_section_network), "WG0"))
        scroll.addView(panel {
            configLine(str(R.string.vpn0wn_metric_cidr), server.cidr)
            configLine(str(R.string.vpn0wn_label_dns), server.dns)
            configLine(str(R.string.vpn0wn_label_listen_port), "UDP ${server.port}")
            configLine(str(R.string.vpn0wn_label_engine), server.engine)
        })
        scroll.addView(sectionHeader(4, str(R.string.vpn0wn_section_actions), str(R.string.vpn0wn_hint_remote)))
        scroll.addView(actionGrid(listOf(
            VpsDeployAction(R.drawable.ic_vpn0wn_refresh, str(R.string.vpn0wn_action_repair), str(R.string.vpn0wn_action_repair_sub), R.color.vpn0wn_ink) { toast(str(R.string.vpn0wn_repair_queued)) },
            VpsDeployAction(R.drawable.ic_vpn0wn_share, str(R.string.vpn0wn_action_backup), str(R.string.vpn0wn_action_backup_sub), R.color.vpn0wn_signal_deep) { toast(str(R.string.vpn0wn_backup_exported)) },
            VpsDeployAction(R.drawable.ic_vpn0wn_logs, str(R.string.vpn0wn_action_terminal), str(R.string.vpn0wn_action_terminal_sub), R.color.vpn0wn_ink) { toast(str(R.string.vpn0wn_terminal_pending)) },
            VpsDeployAction(R.drawable.ic_vpn0wn_trash, str(R.string.vpn0wn_action_uninstall), str(R.string.vpn0wn_action_uninstall_sub), R.color.vpn0wn_error) { toast(str(R.string.vpn0wn_uninstall_requires_confirmation)) },
        )))
        container.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        return container
    }

    private fun provisionScreen(): View {
        val steps = listOf(str(R.string.vpn0wn_step_ssh), str(R.string.vpn0wn_step_detect), str(R.string.vpn0wn_step_plan), str(R.string.vpn0wn_step_apply), str(R.string.vpn0wn_step_import))
        val container = vertical()
        val scrollView = ScrollView(requireContext())
        val page = vertical()
        page.addView(topBar(title = str(R.string.vpn0wn_deploy_server_title), sub = str(R.string.vpn0wn_deploy_step_summary, provisionStep + 1, steps[provisionStep]), leftIcon = R.drawable.ic_vpn0wn_chevron_left, onLeft = {
            if (provisionStep == 0) {
                resetProvisionSession()
                screen = Screen.Fleet
            } else {
                provisionStep--
            }
            render()
        }))
        page.addView(progressStrip(steps.size, provisionStep))
        page.addView(stepTracker(steps))
        val scroll = vertical().apply { setPadding(dp(14), 0, dp(14), dp(24)) }
        when (provisionStep) {
            SSH_STEP -> sshStep(scroll)
            DETECT_STEP -> detectStep(scroll)
            PLAN_STEP -> planStep(scroll)
            APPLY_STEP -> applyStep(scroll)
            else -> importStep(scroll)
        }
        page.addView(scroll)
        scrollView.addView(page)
        container.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        container.addView(bottomAction())
        return container
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun sshStep(parent: LinearLayout) {
        parent.addView(sectionHeader(1, str(R.string.vpn0wn_section_ssh_access), str(R.string.vpn0wn_hint_no_cloud_relay)))
        parent.addView(panel {
            editLine(str(R.string.vpn0wn_label_host), str(R.string.vpn0wn_host_placeholder), provisionForm.host) {
                provisionForm = provisionForm.copy(host = it)
            }
            val split = horizontal()
            split.addView(editLineView(str(R.string.vpn0wn_label_port), "22", provisionForm.port, InputType.TYPE_CLASS_NUMBER) {
                provisionForm = provisionForm.copy(port = it)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            split.addView(editLineView(str(R.string.vpn0wn_label_user), "root", provisionForm.user) {
                provisionForm = provisionForm.copy(user = it)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(split)
            addView(segmented(listOf(AuthMode.KEY.name to str(R.string.vpn0wn_auth_ssh_key), AuthMode.PASSWORD.name to str(R.string.vpn0wn_auth_password)), authMode.name) {
                authMode = AuthMode.valueOf(it)
                render()
            }.apply { setPadding(dp(14), dp(10), dp(14), dp(10)) })
            if (authMode == AuthMode.KEY) {
                boxedEditLine(str(R.string.vpn0wn_label_private_key), str(R.string.vpn0wn_private_key_placeholder), provisionForm.privateKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, minLines = 5, textSize = 10) {
                    provisionForm = provisionForm.copy(privateKey = it)
                }
                addView(mono(str(R.string.vpn0wn_private_key_keystore_note), 10, R.color.vpn0wn_ink_3).apply { setPadding(dp(14), 0, dp(14), dp(8)) })
                addView(smallOutlineButton(str(R.string.vpn0wn_pick_key_file)) {
                    toast(str(R.string.vpn0wn_key_file_picker_pending))
                }.withMargins(left = 14, right = 14, bottom = 12))
            } else {
                boxedEditLine(str(R.string.vpn0wn_auth_password), str(R.string.vpn0wn_password_placeholder), String(provisionForm.password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, textSize = 14) {
                    provisionForm.clear()
                    provisionForm = provisionForm.copy(password = it.toCharArray())
                }
                addView(mono(str(R.string.vpn0wn_password_ephemeral_note), 10, R.color.vpn0wn_warn).apply { setPadding(dp(14), 0, dp(14), dp(12)) })
            }
            addView(toggleLine(str(R.string.vpn0wn_trust_server_key), str(R.string.vpn0wn_trust_server_key_desc), trustHost) {
                trustHost = !trustHost
                render()
            })
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun detectStep(parent: LinearLayout) {
        val pending = str(R.string.vpn0wn_pending_live_data)
        parent.addView(sectionHeader(2, str(R.string.vpn0wn_step_detect).lowercase(Locale.ROOT), str(R.string.vpn0wn_hint_dry_run)))
        parent.addView(panel {
            configLine(str(R.string.vpn0wn_label_distro), pending)
            configLine(str(R.string.vpn0wn_label_kernel), pending)
            configLine(str(R.string.vpn0wn_label_public_ip), pending)
            configLine(str(R.string.vpn0wn_label_firewall), pending)
            configLine(str(R.string.vpn0wn_label_wg_status), pending)
            configLine(str(R.string.vpn0wn_label_ip_forwarding), pending)
        })
        parent.addView(card().apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(label(str(R.string.vpn0wn_detect_assessment)))
            addView(mono(str(R.string.vpn0wn_live_detect_placeholder), 11, R.color.vpn0wn_ink_2).apply { setPadding(0, dp(6), 0, 0) })
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun planStep(parent: LinearLayout) {
        parent.addView(sectionHeader(3, str(R.string.vpn0wn_section_deployment_plan), str(R.string.vpn0wn_hint_review_before_apply)))
        parent.addView(panel {
            addView(VpsTerminalStreamView(requireContext()).apply {
                setLines(listOf(str(R.string.vpn0wn_live_plan_placeholder)), false)
            })
            addView(label(str(R.string.vpn0wn_files_written)).apply { setPadding(dp(14), dp(10), dp(14), dp(6)) })
            addView(mono(str(R.string.vpn0wn_pending_live_data), 10, R.color.vpn0wn_ink_3).apply { setPadding(dp(14), 0, dp(14), dp(8)) })
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun applyStep(parent: LinearLayout) {
        parent.addView(sectionHeader(4, str(R.string.vpn0wn_step_apply).lowercase(Locale.ROOT), if (applyDone) str(R.string.vpn0wn_apply_complete) else if (applyRunning) str(R.string.vpn0wn_apply_running) else str(R.string.vpn0wn_apply_ready)))
        parent.addView(panel {
            if (!applyRunning && !applyDone) {
                addView(mono(str(R.string.vpn0wn_apply_review_note), 11, R.color.vpn0wn_ink_3).apply { setPadding(dp(14)) })
            } else {
                addView(VpsTerminalStreamView(requireContext()).apply { setLines(listOf(str(R.string.vpn0wn_live_apply_placeholder)), applyRunning) })
            }
            if (applyDone) addView(mono(str(R.string.vpn0wn_live_provisioning_required), 11, R.color.vpn0wn_warn).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) })
        })
        if (!applyRunning && !applyDone) {
            parent.addView(dashedButton(str(R.string.vpn0wn_apply_plan_button), R.color.vpn0wn_warn, icon = null) {
                startApplyPreview()
            })
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun importStep(parent: LinearLayout) {
        val pending = str(R.string.vpn0wn_pending_live_data)
        parent.addView(sectionHeader(5, str(R.string.vpn0wn_section_import_device), str(R.string.vpn0wn_hint_first_peer)))
        parent.addView(panel {
            configLine(str(R.string.vpn0wn_label_device_name), pending)
            configLine(str(R.string.vpn0wn_label_assigned_ip), pending)
            configLine(str(R.string.vpn0wn_label_dns), pending)
            configLine(str(R.string.vpn0wn_label_allowed_ips), pending)
            configLine(str(R.string.vpn0wn_label_keepalive), pending)
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun userScreen(server: VpsDeployServer, user: VpsDeployUser): View {
        val container = vertical()
        container.addView(topBar(title = user.name, sub = str(R.string.vpn0wn_user_subtitle, server.name.uppercase(Locale.ROOT)), leftIcon = R.drawable.ic_vpn0wn_chevron_left, onLeft = { screen = Screen.Detail(server.id); render() }, right = listOf(iconButton(R.drawable.ic_vpn0wn_share) { toast(str(R.string.vpn0wn_profile_exported)) })))
        val (scrollView, scroll) = scrollContent()
        scroll.addView(card(user.status.colorRes).apply {
            setPadding(dp(14), dp(14), dp(14), dp(10))
            addView(label(str(user.status.labelRes), user.status.colorRes))
            addView(mono(user.ip, 22, R.color.vpn0wn_ink, Typeface.BOLD).apply { setPadding(0, dp(8), 0, 0) })
            addView(mono("${user.device} · created ${user.created}", 11, R.color.vpn0wn_ink_3).apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(metricRow(listOf(str(R.string.vpn0wn_label_rx) to user.rx, str(R.string.vpn0wn_label_tx) to user.tx, str(R.string.vpn0wn_handshake) to user.handshake)))
        }.withMargins(bottom = 14))
        scroll.addView(sectionHeader(1, str(R.string.vpn0wn_section_profile), str(R.string.vpn0wn_hint_wireguard)))
        scroll.addView(panel {
            configLine(str(R.string.vpn0wn_label_server), server.name)
            configLine(str(R.string.vpn0wn_label_public_key), BindingAdapters.shortKey(user.key), copy = true)
            configLine(str(R.string.vpn0wn_label_endpoint), "${server.endpoint}:${server.port}")
            configLine(str(R.string.vpn0wn_label_dns), server.dns)
            configLine(str(R.string.vpn0wn_label_allowed), user.allowedIps)
        })
        scroll.addView(sectionHeader(2, str(R.string.vpn0wn_section_actions), str(R.string.vpn0wn_hint_device)))
        scroll.addView(actionGrid(listOf(
            VpsDeployAction(R.drawable.ic_vpn0wn_qr, str(R.string.vpn0wn_action_show_qr), str(R.string.vpn0wn_action_show_qr_sub), R.color.vpn0wn_signal_deep, user.status == VpsDeployUserStatus.REVOKED) { qrUser = user; render() },
            VpsDeployAction(R.drawable.ic_vpn0wn_share, str(R.string.vpn0wn_action_export), str(R.string.vpn0wn_action_export_sub), R.color.vpn0wn_ink) { toast(str(R.string.vpn0wn_profile_exported)) },
            VpsDeployAction(R.drawable.ic_vpn0wn_refresh, str(R.string.vpn0wn_action_rotate_key), str(R.string.vpn0wn_action_rotate_key_sub), R.color.vpn0wn_ink, user.status == VpsDeployUserStatus.REVOKED) {
                toast(str(R.string.vpn0wn_live_add_peer_required))
            },
            VpsDeployAction(R.drawable.ic_vpn0wn_trash, str(R.string.vpn0wn_action_revoke), str(R.string.vpn0wn_action_revoke_sub), R.color.vpn0wn_error, user.status == VpsDeployUserStatus.REVOKED) {
                toast(str(R.string.vpn0wn_live_add_peer_required))
            },
        )))
        container.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        return container
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun topBar(title: String, sub: String, leftIcon: Int, onLeft: () -> Unit, right: List<View> = emptyList(), big: Boolean = false): View {
        val bar = vertical().apply {
            setBackgroundColor(color(R.color.vpn0wn_paper_2))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val row = horizontal(Gravity.CENTER_VERTICAL)
        row.addView(iconButton(leftIcon, onLeft))
        row.addView(label(sub).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        right.forEach { row.addView(it) }
        bar.addView(row)
        bar.addView(mono(title, if (big) 26 else 18, R.color.vpn0wn_ink, Typeface.BOLD).apply { setPadding(0, dp(8), 0, 0) })
        bar.addView(View(requireContext()).apply { setBackgroundColor(color(R.color.vpn0wn_rule)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })
        return bar
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun bottomAction(): View {
        val label = if (provisionStep == PLAN_STEP) str(R.string.vpn0wn_confirm_apply) else if (provisionStep == IMPORT_STEP) str(R.string.vpn0wn_finish_close) else str(R.string.vpn0wn_continue)
        val canAdvance = provisionStep != APPLY_STEP || applyDone
        return TextView(requireContext()).apply {
            text = label
            typeface = monoTypeface()
            textSize = 11f
            gravity = Gravity.CENTER
            isEnabled = canAdvance
            setTextColor(color(if (canAdvance) R.color.vpn0wn_paper else R.color.vpn0wn_ink_3))
            background = rounded(
                when {
                    !canAdvance -> R.color.vpn0wn_paper_2
                    provisionStep == IMPORT_STEP -> R.color.vpn0wn_signal_deep
                    else -> R.color.vpn0wn_ink
                },
                when {
                    !canAdvance -> R.color.vpn0wn_rule_strong
                    provisionStep == IMPORT_STEP -> R.color.vpn0wn_signal_deep
                    else -> R.color.vpn0wn_ink
                },
            )
            setPadding(dp(13))
            setOnClickListener {
                if (provisionStep < IMPORT_STEP) provisionStep++ else {
                    toast(str(R.string.vpn0wn_live_provisioning_required))
                    resetProvisionSession()
                    screen = Screen.Fleet
                }
                render()
            }
        }.withMargins(left = 14, top = 12, right = 14, bottom = 12)
    }

    private fun addUser() {
        toast(str(R.string.vpn0wn_live_add_peer_required))
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun userRow(server: VpsDeployServer, user: VpsDeployUser, index: Int): View {
        val row = horizontal(Gravity.CENTER_VERTICAL).apply {
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                screen = Screen.User(server.id, user.id)
                render()
            }
        }
        row.addView(View(requireContext()).apply { setBackgroundColor(color(user.status.colorRes)) }, LinearLayout.LayoutParams(dp(3), -1))
        val body = vertical().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        body.addView(horizontal().apply {
            addView(label(str(R.string.vpn0wn_user_index, (index + 1).toString().padStart(2, '0'))), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label(str(user.status.labelRes), user.status.colorRes))
        })
        body.addView(mono(user.name, 14, R.color.vpn0wn_ink).apply { setPadding(0, dp(5), 0, 0) })
        body.addView(mono(str(R.string.vpn0wn_user_row_summary, user.ip, user.rx, user.tx, user.handshake), 10, R.color.vpn0wn_ink_3).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun actionGrid(actions: List<VpsDeployAction>): View {
        val grid = vertical()
        actions.chunked(2).forEach { pair ->
            val row = horizontal()
            pair.forEach { spec ->
                row.addView(actionTile(spec), LinearLayout.LayoutParams(0, dp(104), 1f).apply { marginEnd = dp(10) })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        return grid
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun actionTile(spec: VpsDeployAction): View {
        return vertical().apply {
            alpha = if (spec.disabled) 0.45f else 1f
            background = rounded(R.color.vpn0wn_paper_2, spec.colorRes)
            setPadding(dp(12))
            if (!spec.disabled) setOnClickListener { spec.onClick() }
            addView(ImageView(requireContext()).apply {
                setImageDrawable(AppCompatResources.getDrawable(requireContext(), spec.iconRes))
                setColorFilter(color(spec.colorRes))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(Space(requireContext()), LinearLayout.LayoutParams(1, 0, 1f))
            addView(mono(spec.label, 12, spec.colorRes, Typeface.BOLD))
            addView(mono(spec.sub, 10, R.color.vpn0wn_ink_3).apply { setPadding(0, dp(3), 0, 0) })
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun qrOverlay(server: VpsDeployServer, user: VpsDeployUser): View {
        return FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.argb(235, 5, 10, 18))
            setOnClickListener {
                qrUser = null
                render()
            }
            val modal = vertical().apply {
                background = rounded(R.color.vpn0wn_paper, R.color.vpn0wn_rule, dp(14))
                setPadding(dp(20))
                setOnClickListener { }
                addView(horizontal(Gravity.CENTER_VERTICAL).apply {
                    addView(vertical().apply {
                        addView(label(str(R.string.vpn0wn_scan_to_pair)))
                        addView(mono(user.name, 13, R.color.vpn0wn_ink).apply { setPadding(0, dp(2), 0, 0) })
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(iconButton(R.drawable.ic_vpn0wn_close) {
                        qrUser = null
                        render()
                    })
                })
                addView(VpsQrCodeView(requireContext()).apply { bind("${server.endpoint}:${server.port}#${user.key}") }, LinearLayout.LayoutParams(dp(282), dp(282)).apply { topMargin = dp(14) })
                addView(qrInfoGrid(server, user))
                addView(mono(str(R.string.vpn0wn_qr_footer), 10, R.color.vpn0wn_ink_3).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, 0)
                })
            }
            addView(modal, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun qrInfoGrid(server: VpsDeployServer, user: VpsDeployUser): View {
        return vertical().apply {
            setPadding(0, dp(14), 0, 0)
            addView(horizontal().apply {
                addView(qrInfoCell(str(R.string.vpn0wn_label_server), server.name), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
                addView(qrInfoCell(str(R.string.vpn0wn_label_ip), user.ip), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            addView(qrInfoCell(str(R.string.vpn0wn_label_endpoint), "${server.endpoint}:${server.port}").withMargins(top = 10))
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun qrInfoCell(title: String, value: String): View = vertical().apply {
        addView(label(title).apply { textSize = 9f })
        addView(mono(value, 11, R.color.vpn0wn_ink).apply {
            setPadding(0, dp(2), 0, 0)
            setSingleLine(false)
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun progressStrip(count: Int, current: Int): View {
        return horizontal().apply {
            repeat(count) { index ->
                addView(View(requireContext()).apply {
                    background = rounded(
                        when {
                            index < current -> R.color.vpn0wn_signal_deep
                            index == current -> R.color.vpn0wn_signal
                            else -> R.color.vpn0wn_rule
                        },
                        when {
                            index < current -> R.color.vpn0wn_signal_deep
                            index == current -> R.color.vpn0wn_signal
                            else -> R.color.vpn0wn_rule
                        },
                        dp(2),
                    )
                }, LinearLayout.LayoutParams(0, dp(3), 1f))
            }
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun stepTracker(steps: List<String>): View = HorizontalScrollView(requireContext()).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(color(R.color.vpn0wn_paper_2))
        addView(horizontal(Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            steps.forEachIndexed { index, step ->
                val done = index < provisionStep
                val active = index == provisionStep
                val stateColor = when {
                    done -> R.color.vpn0wn_signal_deep
                    active -> R.color.vpn0wn_warn
                    else -> R.color.vpn0wn_ink_3
                }
                addView(vertical(Gravity.CENTER).apply {
                    addView(TextView(requireContext()).apply {
                        text = if (done) "✓" else (index + 1).toString().padStart(2, '0')
                        gravity = Gravity.CENTER
                        typeface = monoTypeface()
                        textSize = 9f
                        setTextColor(color(stateColor))
                        background = rounded(if (done) R.color.vpn0wn_paper_3 else R.color.vpn0wn_paper_2, stateColor, dp(6))
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                    addView(label(step).apply {
                        textSize = 8f
                        setTextColor(color(stateColor))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(3), 0, 0)
                    })
                }, LinearLayout.LayoutParams(dp(48), WRAP_CONTENT))
                if (index < steps.lastIndex) {
                    addView(View(requireContext()).apply {
                        setBackgroundColor(color(if (done) R.color.vpn0wn_signal_deep else R.color.vpn0wn_rule))
                    }, LinearLayout.LayoutParams(dp(18), dp(1)))
                }
            }
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun sectionHeader(index: Int, title: String, hint: String): View {
        return horizontal(Gravity.CENTER_VERTICAL).apply {
            setPadding(0, dp(14), 0, dp(8))
            addView(label("§${index.toString().padStart(2, '0')}", R.color.vpn0wn_rule_strong))
            addView(mono(title, 12, R.color.vpn0wn_ink, Typeface.BOLD).apply { setPadding(dp(8), 0, dp(8), 0) })
            addView(View(requireContext()).apply { setBackgroundColor(color(R.color.vpn0wn_rule)) }, LinearLayout.LayoutParams(0, dp(1), 1f))
            addView(label(hint).apply { setPadding(dp(8), 0, 0, 0) })
        }
    }

    private fun panel(block: LinearLayout.() -> Unit): View = card().apply(block).withMargins(bottom = 14)

    // Local text/padding values preserve the configuration-row presentation.
    @Suppress("MagicNumber")
    private fun LinearLayout.configLine(title: String, value: String, copy: Boolean = false) {
        val row = horizontal(Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(vertical().apply {
            addView(label(title))
            addView(mono(value, 13, R.color.vpn0wn_ink).apply {
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (copy) {
            row.addView(iconButton(R.drawable.ic_vpn0wn_copy) { copyToClipboard(title, value) })
        }
        addView(row)
        addView(View(requireContext()).apply { setBackgroundColor(color(R.color.vpn0wn_rule)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun LinearLayout.editLine(
        title: String,
        hint: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        onChange: (String) -> Unit = {},
    ) {
        addView(editLineView(title, hint, value, inputType, onChange))
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun LinearLayout.boxedEditLine(
        title: String,
        hint: String,
        value: String,
        inputType: Int,
        minLines: Int = 1,
        textSize: Int = 13,
        onChange: (String) -> Unit = {},
    ) {
        addView(vertical().apply {
            setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(label(title).apply { setPadding(0, 0, 0, dp(8)) })
            addView(EditText(requireContext()).apply {
                setText(value)
                this.hint = hint
                this.inputType = inputType
                typeface = monoTypeface()
                this.textSize = textSize.toFloat()
                setTextColor(color(R.color.vpn0wn_ink))
                setHintTextColor(color(R.color.vpn0wn_ink_3))
                setSingleLine(minLines == 1)
                this.minLines = minLines
                gravity = if (minLines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL
                background = rounded(R.color.vpn0wn_paper_3, R.color.vpn0wn_rule_strong, dp(4))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                doAfterTextChanged { onChange(it?.toString().orEmpty()) }
            }, LinearLayout.LayoutParams(-1, WRAP_CONTENT))
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun editLineView(
        title: String,
        hint: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        onChange: (String) -> Unit = {},
    ): View = vertical().apply {
        setPadding(dp(14), dp(8), dp(14), dp(10))
        addView(label(title))
        addView(EditText(requireContext()).apply {
            setText(value)
            this.hint = hint
            this.inputType = inputType
            typeface = monoTypeface()
            textSize = 13f
            setTextColor(color(R.color.vpn0wn_ink))
            setHintTextColor(color(R.color.vpn0wn_ink_3))
            setSingleLine((inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.vpn0wn_rule_strong))
            setPadding(0, dp(2), 0, dp(2))
            doAfterTextChanged { onChange(it?.toString().orEmpty()) }
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): View {
        return horizontal().apply {
            background = rounded(R.color.vpn0wn_paper_2, R.color.vpn0wn_rule_strong, dp(6))
            options.forEach { (key, text) ->
                addView(TextView(requireContext()).apply {
                    this.text = text
                    gravity = Gravity.CENTER
                    minHeight = dp(48)
                    typeface = monoTypeface()
                    textSize = 10f
                    setTextColor(color(if (key == selected) R.color.vpn0wn_paper else R.color.vpn0wn_ink_3))
                    background = if (key == selected) rounded(R.color.vpn0wn_ink, R.color.vpn0wn_ink, dp(6)) else null
                    setPadding(dp(8))
                    setOnClickListener { onSelect(key) }
                }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun toggleLine(title: String, desc: String, value: Boolean, onClick: () -> Unit): View {
        return horizontal(Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(vertical().apply {
                addView(ui(title, 14, R.color.vpn0wn_ink, Typeface.BOLD))
                addView(ui(desc, 11, R.color.vpn0wn_ink_3).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(VpsSwitchGlyphView(requireContext()).apply { checked = value }, LinearLayout.LayoutParams(dp(44), dp(24)))
            setOnClickListener { onClick() }
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun statTile(title: String, value: String, dot: String, colorRes: Int, active: Boolean, onClick: () -> Unit): View {
        return vertical().apply {
            setBackgroundColor(color(if (active) R.color.vpn0wn_paper_3 else R.color.vpn0wn_paper_2))
            setPadding(dp(14), dp(10), dp(14), 0)
            setOnClickListener { onClick() }
            addView(label("$dot $title".trim(), if (active) colorRes else R.color.vpn0wn_ink_3).apply { textSize = 9f })
            addView(mono(value, 20, if (active) colorRes else R.color.vpn0wn_ink, Typeface.BOLD).apply { setPadding(0, dp(1), 0, 0) })
            addView(Space(requireContext()), LinearLayout.LayoutParams(1, 0, 1f))
            addView(View(requireContext()).apply { setBackgroundColor(if (active) color(colorRes) else Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, dp(2)))
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun metricRow(metrics: List<Pair<String, String>>): View {
        return horizontal().apply {
            background = rounded(R.color.vpn0wn_paper_3, R.color.vpn0wn_rule)
            metrics.forEach { (title, value) ->
                addView(vertical().apply {
                    setPadding(dp(8))
                    addView(label(title).apply { textSize = 8f })
                    addView(mono(value, 10, R.color.vpn0wn_ink_2).apply {
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun horizontalWrap(items: List<View>): View {
        val hsv = HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false }
        val row = horizontal()
        items.forEach { row.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, dp(28)).apply { marginEnd = dp(6) }) }
        hsv.addView(row)
        return hsv
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun healthBadge(check: VpsDeployHealthCheck): View {
        val res = when (check.ok) {
            true -> R.color.vpn0wn_signal_deep
            false -> R.color.vpn0wn_error
            null -> R.color.vpn0wn_warn
        }
        val glyph = when (check.ok) {
            true -> "◆"
            false -> "✕"
            null -> "◑"
        }
        return label("$glyph ${check.label}", res).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(R.color.vpn0wn_paper_3, res, dp(999))
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun dashedButton(text: String, stroke: Int = R.color.vpn0wn_rule_strong, icon: Int? = R.drawable.ic_vpn0wn_plus, onClick: () -> Unit): View {
        return horizontal(Gravity.CENTER).apply {
            background = rounded(R.color.vpn0wn_paper, stroke, dash = true)
            setPadding(dp(13))
            setOnClickListener { onClick() }
            if (icon != null) {
                addView(ImageView(requireContext()).apply {
                    setImageDrawable(AppCompatResources.getDrawable(requireContext(), icon))
                    setColorFilter(color(stroke))
                }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) })
            }
            addView(mono(text.removePrefix("+ ").trim(), 11, stroke).apply {
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
            })
        }.withMargins(top = 2, bottom = 14)
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun smallOutlineButton(text: String, onClick: () -> Unit): View {
        return TextView(requireContext()).apply {
            this.text = text.uppercase(Locale.ROOT)
            gravity = Gravity.CENTER
            typeface = monoTypeface()
            textSize = 10f
            letterSpacing = 0.06f
            setTextColor(color(R.color.vpn0wn_ink_2))
            background = rounded(R.color.vpn0wn_paper_2, R.color.vpn0wn_rule_strong, dp(4))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onClick() }
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun rowButton(text: String, onClick: () -> Unit): View = horizontal(Gravity.CENTER_VERTICAL).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(ImageView(requireContext()).apply {
            setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_vpn0wn_plus))
            setColorFilter(color(R.color.vpn0wn_ink_3))
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(10) })
        addView(mono(text.removePrefix("+ ").trim(), 11, R.color.vpn0wn_ink_3).apply { letterSpacing = 0.06f })
        setOnClickListener { onClick() }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun iconButton(icon: Int, onClick: () -> Unit): View = ImageButton(requireContext()).apply {
        setImageDrawable(AppCompatResources.getDrawable(requireContext(), icon))
        setColorFilter(color(R.color.vpn0wn_ink_2))
        background = null
        setPadding(dp(8))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun scrollContent(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = false
            setBackgroundColor(color(R.color.vpn0wn_paper))
        }
        val content = vertical().apply { setPadding(dp(14), 0, dp(14), dp(24)) }
        scroll.addView(content)
        return scroll to content
    }

    private fun vertical(gravity: Int = Gravity.NO_GRAVITY): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    private fun horizontal(gravity: Int = Gravity.NO_GRAVITY): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    private fun card(stroke: Int = R.color.vpn0wn_rule): LinearLayout = VpsCornerLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(R.color.vpn0wn_paper_2, stroke)
        clipToOutline = true
    }

    private fun panelBg() = rounded(R.color.vpn0wn_paper_2, R.color.vpn0wn_rule)

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun rounded(fill: Int, stroke: Int, radius: Int = dp(8), dash: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = radius.toFloat()
        if (dash) setStroke(dp(1), color(stroke), dp(6).toFloat(), dp(4).toFloat()) else setStroke(dp(1), color(stroke))
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun label(text: String, colorRes: Int = R.color.vpn0wn_ink_3): TextView = TextView(requireContext()).apply {
        this.text = text.uppercase(Locale.ROOT)
        typeface = monoTypeface()
        textSize = 10f
        letterSpacing = 0.08f
        setTextColor(color(colorRes))
        includeFontPadding = false
    }

    private fun mono(text: String, size: Int, colorRes: Int, style: Int = Typeface.NORMAL): TextView = TextView(requireContext()).apply {
        this.text = text
        typeface = when (style) {
            Typeface.NORMAL -> monoTypeface()
            Typeface.BOLD -> cachedBoldMonoTypeface
            else -> Typeface.create(monoTypeface(), style)
        }
        textSize = size.toFloat()
        setTextColor(color(colorRes))
        includeFontPadding = false
    }

    private fun ui(text: String, size: Int, colorRes: Int, style: Int = Typeface.NORMAL): TextView = TextView(requireContext()).apply {
        this.text = text
        typeface = when (style) {
            Typeface.NORMAL -> uiTypeface()
            Typeface.BOLD -> cachedBoldUiTypeface
            else -> Typeface.create(uiTypeface(), style)
        }
        textSize = size.toFloat()
        setTextColor(color(colorRes))
        includeFontPadding = false
    }

    private fun View.withMargins(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): View = apply {
        layoutParams = LinearLayout.LayoutParams(-1, WRAP_CONTENT).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        requireContext().getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(label, value))
        toast(str(R.string.vpn0wn_field_copied, label))
    }

    private fun toast(message: String) {
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).vpn0wnStyle().show()
    }

    private fun goBack() {
        if (qrUser != null) {
            qrUser = null
            render()
            return
        }
        when (val current = screen) {
            Screen.Fleet -> {
                parentFragmentManager.popBackStack()
                return
            }
            is Screen.Detail -> screen = Screen.Fleet
            is Screen.Provision -> if (provisionStep > 0) {
                provisionStep--
            } else {
                resetProvisionSession()
                screen = Screen.Fleet
            }
            is Screen.User -> screen = Screen.Detail(current.serverId)
        }
        render()
    }

    private fun startApplyPreview() {
        applyJob?.cancel()
        applyJob = viewLifecycleOwner.lifecycleScope.launch {
            applyRunning = true
            render()
            delay(APPLY_PREVIEW_DURATION_MS)
            applyRunning = false
            applyDone = true
            render()
        }
    }

    private companion object {
        const val SSH_STEP = 0
        const val DETECT_STEP = 1
        const val PLAN_STEP = 2
        const val APPLY_STEP = 3
        const val IMPORT_STEP = 4
        const val APPLY_PREVIEW_DURATION_MS = 5200L
    }

    private fun server(id: String) = servers.find { it.id == id }
    private fun resetProvisionSession() {
        applyJob?.cancel()
        applyJob = null
        provisionForm.clear()
        applyRunning = false
        applyDone = false
        provisionStep = SSH_STEP
        authMode = AuthMode.KEY
        trustHost = true
        provisionForm = ProvisionForm()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun color(res: Int): Int = ContextCompat.getColor(requireContext(), res)
    private fun str(res: Int, vararg args: Any): String = getString(res, *args)
    private fun monoTypeface(): Typeface = cachedMonoTypeface
    private fun uiTypeface(): Typeface = cachedUiTypeface

    private data class ProvisionForm(
        val host: String = "",
        val port: String = "22",
        val user: String = "root",
        val privateKey: String = "",
        val password: CharArray = CharArray(0),
    ) {
        fun clear() {
            password.fill('\u0000')
        }
    }

    private enum class AuthMode {
        KEY,
        PASSWORD,
    }

    private sealed class Screen {
        object Fleet : Screen()
        data class Detail(val serverId: String) : Screen()
        object Provision : Screen()
        data class User(val serverId: String, val userId: String) : Screen()
    }

}
