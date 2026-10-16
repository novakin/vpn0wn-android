/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.setPadding
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vpn0wn.android.Application
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.R
import com.vpn0wn.android.model.ObservableTunnel
import com.vpn0wn.android.provisioning.VpsDeployAction
import com.vpn0wn.android.provisioning.VpsDeployServer
import com.vpn0wn.android.provisioning.VpsDeployServerStatus
import com.vpn0wn.android.provisioning.VpsDeployUser
import com.vpn0wn.android.provisioning.VpsDeployUserStatus
import com.vpn0wn.android.provisioning.VpsDeployMapper
import com.vpn0wn.android.provisioning.StoredVpsServer
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.AuthMode
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Problem
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.SavedAccess
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.State
import com.vpn0wn.android.provisioning.VpsProvisioningViewModel.Step
import com.vpn0wn.android.util.vpn0wnStyle
import com.vpn0wn.android.widget.VpsSectionHeaderLayout
import com.vpn0wn.android.widget.VpsHealthChecksLayout
import com.vpn0wn.android.widget.VpsCornerLayout
import com.vpn0wn.android.widget.VpsTerminalStreamView
import com.vpn0wn.android.widget.VpsTopologyView
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VpsProvisioningFragment : BaseFragment() {
    private lateinit var root: FrameLayout
    private var screen: Screen = Screen.Fleet
    private var filter: VpsDeployServerStatus? = null
    private val provision by viewModels<VpsProvisioningViewModel>()
    private val provisionState get() = provision.state.value
    private val provisionForm get() = provision.draft
    private val provisionStep get() = provisionState.step.ordinal
    private var backCallback: OnBackPressedCallback? = null
    private var storeJob: Job? = null
    private var renderedProvisionState: State? = null
    private val servers = mutableListOf<VpsDeployServer>()
    private val storedServers = mutableListOf<StoredVpsServer>()
    private val serverStore by lazy { Application.getVpsServerStore() }
    private val cachedMonoTypeface by lazy {
        ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
    }
    private val cachedMediumMonoTypeface by lazy {
        ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_medium) ?: cachedMonoTypeface
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = FrameLayout(requireContext()).apply {
            setBackgroundColor(color(R.color.vpn0wn_paper))
            isSaveFromParentEnabled = false
        }
        if (provisionState.active) screen = Screen.Provision
        backCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { goBack() }
        render()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectServers()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                provision.state.collect { state ->
                    val previous = renderedProvisionState
                    if (state.active) screen = Screen.Provision else if (screen is Screen.Provision) screen = Screen.Fleet
                    val editingStep = state.step == Step.SSH || state.step == Step.DETECT
                    if (previous != null && screen is Screen.Provision && editingStep && previous.step == state.step &&
                        !state.busy && previous.busy == state.busy && state.problem == null &&
                        (state.detect === previous.detect || state.detect == null)
                    ) {
                        root.findViewById<LinearLayout>(R.id.vpn0wn_vps_operation_status)?.let { renderOperationStatus(it) }
                        if (previous.usingSavedAccess != state.usingSavedAccess)
                            root.findViewById<LinearLayout>(R.id.vpn0wn_vps_ssh_credentials)?.let { it.sshCredentials() }
                        root.findViewById<View>(R.id.vpn0wn_vps_bottom_action)?.let { action ->
                            val parent = action.parent as ViewGroup
                            val index = parent.indexOfChild(action)
                            parent.removeView(action)
                            parent.addView(bottomAction(), index)
                        }
                        renderedProvisionState = state
                    } else {
                        render()
                    }
                    val settledProblem = !state.busy && state.problem != null
                    if (settledProblem && (state.problem != previous?.problem || previous?.busy == true)) {
                        root.findViewById<View>(R.id.vpn0wn_vps_operation_problem)?.let { problem ->
                            problem.doOnLayout {
                                problem.post {
                                    if (problem.isAttachedToWindow && provisionState.problem == state.problem && !provisionState.busy)
                                        problem.requestRectangleOnScreen(Rect(0, 0, problem.width, problem.height), true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        storeJob?.cancel()
        storeJob = null
        backCallback?.remove()
        backCallback = null
        renderedProvisionState = null
        super.onDestroyView()
    }

    // This screen listens through BaseFragment but does not display a selected tunnel.
    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) = Unit

    private fun render() {
        updateServerProjection(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
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
        renderedProvisionState = if (screen is Screen.Provision) provisionState else null

    }

    private fun collectServers() {
        storeJob?.cancel()
        storeJob = viewLifecycleOwner.lifecycleScope.launch {
            serverStore.servers.collect { storedServers ->
                this@VpsProvisioningFragment.storedServers.clear()
                this@VpsProvisioningFragment.storedServers.addAll(storedServers)
                val now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
                provision.retainCurrentServers(storedServers)
                updateServerProjection(now)
                if (::root.isInitialized && screen !is Screen.Provision) render()
            }
        }
    }

    private fun updateServerProjection(now: Long) {
        servers.clear()
        servers.addAll(storedServers.map { stored ->
            provisionState.statusSnapshots[stored.id]?.takeIf { it.matches(stored) }?.let { snapshot ->
                VpsDeployMapper.fromStatus(stored, snapshot.status, now, snapshot.observedAtEpochSeconds)
            } ?: VpsDeployMapper.fromStored(stored, now)
        })
    }

    private fun fleetScreen(): View {
        val container = vertical()
        container.addView(topBar(title = str(R.string.vpn0wn_servers_title).lowercase(Locale.ROOT), sub = str(if (servers.all { it.usersKnown }) R.string.vpn0wn_servers_summary else R.string.vpn0wn_servers_summary_partial, servers.size, servers.sumOf { it.users.size }), leftIcon = R.drawable.ic_vpn0wn_chevron_left, onLeft = { goBack() }, right = listOf(iconButton(R.drawable.ic_vpn0wn_logs, str(R.string.vpn0wn_logs_title)) { toast(str(R.string.vpn0wn_server_logs_pending)) }, iconButton(R.drawable.ic_vpn0wn_settings, str(R.string.settings)) { toast(str(R.string.vpn0wn_server_settings_pending)) }), big = true))
        container.addView(statusTiles())
        val (scrollView, scroll) = scrollContent()
        scroll.addView(sectionHeader(1, str(R.string.vpn0wn_server_fleet), filter?.let { str(it.shortLabelRes) } ?: str(R.string.vpn0wn_self_hosted)))
        val displayed = if (filter == null) servers else servers.filter { it.status == filter }
        displayed.forEach { scroll.addView(serverCard(it)) }
        if (displayed.isEmpty()) scroll.addView(emptyFleetCard())
        scroll.addView(dashedButton(str(R.string.vpn0wn_add_server)) {
            provision.begin()
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
            actionAccessibility(Button::class.java.name)
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
        body.addView(mono(server.name, 16, R.color.vpn0wn_ink).apply { setPadding(0, dp(8), 0, 0) })
        body.addView(mono(if (server.usersKnown) str(R.string.vpn0wn_server_card_summary, "${server.endpoint}:${server.port}", server.users.size, server.engine) else str(R.string.vpn0wn_server_card_summary_unknown, "${server.endpoint}:${server.port}", server.engine), 10, R.color.vpn0wn_ink_3).apply { setPadding(0, dp(4), 0, dp(10)) })
        body.addView(metricRow(listOf(str(R.string.vpn0wn_metric_cidr) to server.cidr, str(R.string.vpn0wn_metric_uptime) to server.uptime, str(R.string.vpn0wn_metric_sync) to server.lastSync)))
        body.addView(VpsHealthChecksLayout(requireContext(), server.health).apply { setPadding(0, dp(10), 0, 0) })
        railRow.addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(railRow)
        return card.withMargins(bottom = 10)
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun detailScreen(server: VpsDeployServer): View {
        val container = vertical()
        container.addView(topBar(title = server.name, sub = str(R.string.vpn0wn_server_subtitle, server.region.uppercase(Locale.ROOT)), leftIcon = R.drawable.ic_vpn0wn_chevron_left, onLeft = { screen = Screen.Fleet; render() }, right = listOf(iconButton(R.drawable.ic_vpn0wn_more, str(R.string.vpn0wn_more_actions)) { toast(str(R.string.vpn0wn_more_pending)) })))
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
            configLine(str(R.string.vpn0wn_label_engine), server.engine)
            configLine(str(R.string.vpn0wn_label_uptime), server.uptime)
            configLine(str(R.string.vpn0wn_label_last_sync), server.lastSync)
            addView(label(str(R.string.vpn0wn_checks)).apply { setPadding(dp(14), dp(10), dp(14), dp(6)) })
            addView(VpsHealthChecksLayout(requireContext(), server.health).apply { setPadding(dp(14), 0, dp(14), dp(10)) })
        })
        scroll.addView(sectionHeader(2, str(R.string.vpn0wn_section_vpn_users), if (server.usersKnown) str(R.string.vpn0wn_peers_count, server.users.size) else str(R.string.vpn0wn_pending_live_data)))
        scroll.addView(panel {
            if (server.users.isEmpty()) {
                addView(mono(str(if (server.usersKnown) R.string.vpn0wn_no_peers_yet else R.string.vpn0wn_peers_unavailable), 11, R.color.vpn0wn_ink_3).apply { setPadding(dp(14), dp(14), dp(14), dp(14)) })
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
        storedServers.find { it.id == server.id }?.let { stored ->
            val checking = provisionState.busy && provisionState.serverId == stored.id
            scroll.addView(rowButton(str(if (checking) R.string.vpn0wn_cancel_check else R.string.vpn0wn_refresh_server),
                if (checking) R.drawable.ic_vpn0wn_close else R.drawable.ic_vpn0wn_refresh) {
                if (provisionState.busy) provision.cancelOperation() else provision.syncStatus(stored)
            }.apply {
                id = R.id.vpn0wn_vps_refresh_status
                isEnabled = !provisionState.busy || provisionState.serverId == stored.id
            })
            scroll.addView(sectionHeader(5, str(R.string.vpn0wn_section_ssh_access), str(R.string.vpn0wn_hint_local)))
            scroll.addView(panel {
                addView(rowButton(str(R.string.vpn0wn_reuse_saved_ssh)) { provision.reuse(stored) })
                addView(rowButton(str(R.string.vpn0wn_clear_saved_ssh)) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.vpn0wn_clear_saved_ssh)
                        .setMessage(R.string.vpn0wn_clear_saved_ssh_confirmation)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.vpn0wn_clear_saved_ssh) { _, _ -> provision.clearSavedAccess(stored) }
                        .show()
                })
                addOperationStatus(this, stored.id)
            })
        }
        container.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        return container
    }

    // Local scroll insets preserve the scalable wizard's existing drawing geometry.
    @Suppress("MagicNumber")
    private fun provisionScreen(): View {
        val steps = listOf(str(R.string.vpn0wn_step_ssh), str(R.string.vpn0wn_step_detect), str(R.string.vpn0wn_step_plan), str(R.string.vpn0wn_step_apply), str(R.string.vpn0wn_step_import))
        val container = vertical()
        val scrollView = ScrollView(requireContext())
        val page = vertical()
        page.addView(topBar(title = str(R.string.vpn0wn_deploy_server_title), sub = str(R.string.vpn0wn_deploy_step_summary, provisionStep + 1, steps[provisionStep]), leftIcon = R.drawable.ic_vpn0wn_chevron_left, onLeft = { provision.back() }))
        page.addView(progressStrip(steps.size, provisionStep))
        page.addView(stepTracker(steps, provisionStep))
        val scroll = vertical().apply { setPadding(dp(14), 0, dp(14), dp(24)) }
        when (provisionStep) {
            SSH_STEP -> sshStep(scroll)
            DETECT_STEP -> detectStep(scroll)
            PLAN_STEP -> planStep(scroll)
            APPLY_STEP -> applyStep(scroll)
        }
        addOperationStatus(scroll)
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
            editLine(R.id.vpn0wn_vps_ssh_host, str(R.string.vpn0wn_label_host), str(R.string.vpn0wn_host_placeholder), provisionForm.host) {
                provision.edit(provisionForm.copy(host = it))
            }
            val split = horizontal()
            split.addView(editLineView(R.id.vpn0wn_vps_ssh_port, str(R.string.vpn0wn_label_port), "22", provisionForm.sshPort, InputType.TYPE_CLASS_NUMBER) {
                provision.edit(provisionForm.copy(sshPort = it))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            split.addView(editLineView(R.id.vpn0wn_vps_ssh_user, str(R.string.vpn0wn_label_user), "root", provisionForm.user) {
                provision.edit(provisionForm.copy(user = it))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(split)
            editLine(R.id.vpn0wn_vps_ssh_fingerprint, str(R.string.vpn0wn_ssh_fingerprint), "SHA256:…", provisionForm.fingerprint) {
                provision.edit(provisionForm.copy(fingerprint = it))
            }
            addView(mono(str(R.string.vpn0wn_ssh_pin_note), 10, R.color.vpn0wn_ink_3).apply { setPadding(dp(14), 0, dp(14), dp(12)) })
            addView(vertical().apply { id = R.id.vpn0wn_vps_ssh_credentials; sshCredentials() })
        })
    }

    // Local spacing preserves the surrounding authentication form.
    @Suppress("MagicNumber")
    private fun LinearLayout.sshCredentials() {
        removeAllViews()
        if (provisionState.usingSavedAccess) {
            addView(mono(str(R.string.vpn0wn_using_saved_ssh), 11, R.color.vpn0wn_ink_2).apply { setPadding(dp(14)) })
            addView(rowButton(str(R.string.vpn0wn_enter_new_ssh)) { provision.enterCredentials() })
        } else {
            addView(segmented(listOf(AuthMode.KEY.name to str(R.string.vpn0wn_auth_ssh_key), AuthMode.PASSWORD.name to str(R.string.vpn0wn_auth_password)), provisionForm.authMode.name) {
                provision.edit(provisionForm.copy(authMode = AuthMode.valueOf(it)))
                sshCredentials()
            }.apply { setPadding(dp(14), dp(10), dp(14), dp(10)) })
            if (provisionForm.authMode == AuthMode.KEY) {
                boxedEditLine(R.id.vpn0wn_vps_ssh_private_key, str(R.string.vpn0wn_label_private_key), str(R.string.vpn0wn_private_key_placeholder), provisionForm.privateKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, minLines = 5, textSize = 10) {
                    provision.edit(provisionForm.copy(privateKey = it))
                }
                boxedEditLine(R.id.vpn0wn_vps_ssh_passphrase, str(R.string.vpn0wn_ssh_passphrase), str(R.string.vpn0wn_optional), provisionForm.passphrase, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                    provision.edit(provisionForm.copy(passphrase = it))
                }
                addView(mono(str(R.string.vpn0wn_private_key_keystore_note), 10, R.color.vpn0wn_ink_3).apply { setPadding(dp(14), 0, dp(14), dp(8)) })
                addView(smallOutlineButton(str(R.string.vpn0wn_pick_key_file)) {
                    toast(str(R.string.vpn0wn_key_file_picker_pending))
                }.withMargins(left = 14, right = 14, bottom = 12))
            } else {
                boxedEditLine(R.id.vpn0wn_vps_ssh_password, str(R.string.vpn0wn_auth_password), str(R.string.vpn0wn_password_placeholder), String(provisionForm.password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, textSize = 14) {
                    provision.edit(provisionForm.copy(password = it.toCharArray()))
                }
                addView(mono(str(R.string.vpn0wn_password_ephemeral_note), 10, R.color.vpn0wn_warn).apply { setPadding(dp(14), 0, dp(14), dp(12)) })
            }
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun detectStep(parent: LinearLayout) {
        val result = provisionState.detect ?: return
        parent.addView(sectionHeader(2, str(R.string.vpn0wn_step_detect).lowercase(Locale.ROOT), str(R.string.vpn0wn_hint_dry_run)))
        parent.addView(panel {
            configLine(str(R.string.vpn0wn_label_distro), "${result.os} ${result.version}")
            configLine(str(R.string.vpn0wn_label_host), provisionForm.host)
            configLine(str(R.string.vpn0wn_ssh_fingerprint), provisionForm.fingerprint, wrap = true)
            configLine(str(R.string.vpn0wn_label_firewall), result.firewall)
            configLine(str(R.string.vpn0wn_label_wg_status), str(if (result.wireGuardInstalled) R.string.vpn0wn_tools_installed else R.string.vpn0wn_tools_absent))
            configLine(str(R.string.vpn0wn_label_ip_forwarding), result.ipv4Forwarding)
        })
        parent.addView(card().apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(label(str(R.string.vpn0wn_detect_assessment)))
            addView(mono(str(if (result.supported) R.string.vpn0wn_detect_supported else R.string.vpn0wn_detect_unsupported), 11, R.color.vpn0wn_ink_2).apply { setPadding(0, dp(6), 0, 0) })
        })
        parent.addView(sectionHeader(3, str(R.string.vpn0wn_section_network), str(R.string.vpn0wn_hint_review_before_apply)))
        parent.addView(panel {
            editLine(R.id.vpn0wn_vps_vpn_endpoint, str(R.string.vpn0wn_label_endpoint), str(R.string.vpn0wn_vpn_endpoint_hint), provisionForm.endpoint) {
                provision.edit(provisionForm.copy(endpoint = it))
            }
            editLine(R.id.vpn0wn_vps_listen_port, str(R.string.vpn0wn_label_listen_port), "51820", provisionForm.port, InputType.TYPE_CLASS_NUMBER) {
                provision.edit(provisionForm.copy(port = it))
            }
            editLine(R.id.vpn0wn_vps_cidr, str(R.string.vpn0wn_metric_cidr), "10.8.0.0/24", provisionForm.cidr) {
                provision.edit(provisionForm.copy(cidr = it))
            }
            editLine(R.id.vpn0wn_vps_dns, str(R.string.vpn0wn_label_dns), "1.1.1.1, 1.0.0.1", provisionForm.dns) {
                provision.edit(provisionForm.copy(dns = it))
            }
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun planStep(parent: LinearLayout) {
        val result = provisionState.plan ?: return
        parent.addView(sectionHeader(3, str(R.string.vpn0wn_section_deployment_plan), str(R.string.vpn0wn_hint_review_before_apply)))
        parent.addView(panel {
            configLine(str(R.string.vpn0wn_label_host), provisionForm.host)
            configLine(str(R.string.vpn0wn_ssh_fingerprint), provisionForm.fingerprint, wrap = true)
            configLine(str(R.string.vpn0wn_label_endpoint), result.endpoint)
            configLine(str(R.string.vpn0wn_label_listen_port), result.port)
            configLine(str(R.string.vpn0wn_metric_cidr), result.cidr)
            configLine(str(R.string.vpn0wn_label_dns), result.dns)
            addView(VpsTerminalStreamView(requireContext()).apply {
                setLines(result.steps, false)
            })
            addView(mono(str(if (result.willOverwriteConfig) R.string.vpn0wn_plan_existing_config else R.string.vpn0wn_plan_advisory), 11, R.color.vpn0wn_warn).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) })
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun applyStep(parent: LinearLayout) {
        parent.addView(sectionHeader(4, str(R.string.vpn0wn_step_apply).lowercase(Locale.ROOT), str(R.string.vpn0wn_apply_unavailable)))
        parent.addView(panel {
            configLine(str(R.string.vpn0wn_label_host), provisionForm.host)
            configLine(str(R.string.vpn0wn_ssh_fingerprint), provisionForm.fingerprint, wrap = true)
            addView(mono(str(R.string.vpn0wn_apply_not_connected), 11, R.color.vpn0wn_warn).apply { setPadding(dp(14)) })
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun userScreen(server: VpsDeployServer, user: VpsDeployUser): View {
        val container = vertical()
        container.addView(topBar(title = user.name, sub = str(R.string.vpn0wn_user_subtitle, server.name.uppercase(Locale.ROOT)), leftIcon = R.drawable.ic_vpn0wn_chevron_left, onLeft = { screen = Screen.Detail(server.id); render() }, right = listOf(iconButton(R.drawable.ic_vpn0wn_share, str(R.string.vpn0wn_action_export)) { toast(str(R.string.vpn0wn_profile_unavailable)) })))
        val (scrollView, scroll) = scrollContent()
        scroll.addView(card(user.status.colorRes).apply {
            setPadding(dp(14), dp(14), dp(14), dp(10))
            addView(label(str(user.status.labelRes), user.status.colorRes))
            addView(mono(user.ip, 22, R.color.vpn0wn_ink, medium = true).apply { setPadding(0, dp(8), 0, 0) })
            addView(mono("${user.device} · created ${user.created}", 11, R.color.vpn0wn_ink_3).apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(metricRow(listOf(str(R.string.vpn0wn_label_rx) to user.rx, str(R.string.vpn0wn_label_tx) to user.tx, str(R.string.vpn0wn_handshake) to user.handshake)))
        }.withMargins(bottom = 14))
        scroll.addView(sectionHeader(1, str(R.string.vpn0wn_section_profile), str(R.string.vpn0wn_hint_wireguard)))
        scroll.addView(panel {
            configLine(str(R.string.vpn0wn_label_server), server.name)
            configLine(str(R.string.vpn0wn_label_public_key), user.key, copy = true)
            configLine(str(R.string.vpn0wn_label_endpoint), "${server.endpoint}:${server.port}")
            configLine(str(R.string.vpn0wn_label_dns), server.dns)
            configLine(str(R.string.vpn0wn_label_allowed), user.allowedIps)
        })
        scroll.addView(sectionHeader(2, str(R.string.vpn0wn_section_actions), str(R.string.vpn0wn_hint_device)))
        scroll.addView(actionGrid(listOf(
            VpsDeployAction(R.drawable.ic_vpn0wn_qr, str(R.string.vpn0wn_action_show_qr), str(R.string.vpn0wn_action_show_qr_sub), R.color.vpn0wn_signal_deep, user.status == VpsDeployUserStatus.REVOKED) { toast(str(R.string.vpn0wn_profile_unavailable)) },
            VpsDeployAction(R.drawable.ic_vpn0wn_share, str(R.string.vpn0wn_action_export), str(R.string.vpn0wn_action_export_sub), R.color.vpn0wn_ink) { toast(str(R.string.vpn0wn_profile_unavailable)) },
            VpsDeployAction(R.drawable.ic_vpn0wn_refresh, str(R.string.vpn0wn_action_rotate_key), str(R.string.vpn0wn_action_rotate_key_sub), R.color.vpn0wn_ink, user.status == VpsDeployUserStatus.REVOKED) {
                toast(str(R.string.vpn0wn_peer_mutation_unavailable))
            },
            VpsDeployAction(R.drawable.ic_vpn0wn_trash, str(R.string.vpn0wn_action_revoke), str(R.string.vpn0wn_action_revoke_sub), R.color.vpn0wn_error, user.status == VpsDeployUserStatus.REVOKED) {
                toast(str(R.string.vpn0wn_peer_mutation_unavailable))
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
        row.addView(iconButton(leftIcon, str(R.string.vpn0wn_navigate_back), onLeft))
        row.addView(label(sub).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        right.forEach { row.addView(it) }
        bar.addView(row)
        bar.addView(mono(title, if (big) 26 else 18, R.color.vpn0wn_ink, medium = true).apply { setPadding(0, dp(8), 0, 0) })
        bar.addView(View(requireContext()).apply { setBackgroundColor(color(R.color.vpn0wn_rule)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })
        return bar
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun bottomAction(): View {
        val label = str(when {
            provisionState.busy -> R.string.vpn0wn_cancel_check
            provisionStep == DETECT_STEP -> R.string.vpn0wn_generate_plan
            provisionStep == PLAN_STEP -> R.string.vpn0wn_review_apply
            provisionStep == APPLY_STEP -> R.string.vpn0wn_apply_unavailable
            else -> R.string.vpn0wn_continue
        })
        val canAdvance = provisionState.busy || when (provisionStep) {
            SSH_STEP -> true
            DETECT_STEP -> provisionState.detect?.supported == true
            PLAN_STEP -> provisionState.plan != null
            else -> false
        }
        return TextView(requireContext()).apply {
            setTextAppearance(R.style.Vpn0wnMonoSubText)
            id = R.id.vpn0wn_vps_bottom_action
            text = label
            typeface = monoTypeface()
            textSize = 11f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            isEnabled = canAdvance
            setTextColor(color(if (canAdvance) R.color.vpn0wn_paper else R.color.vpn0wn_ink_3))
            background = rounded(
                when {
                    !canAdvance -> R.color.vpn0wn_paper_2
                    else -> R.color.vpn0wn_ink
                },
                when {
                    !canAdvance -> R.color.vpn0wn_rule_strong
                    else -> R.color.vpn0wn_ink
                },
            )
            actionAccessibility(Button::class.java.name)
            setPadding(dp(13))
            setOnClickListener {
                when {
                    provisionState.busy -> provision.back()
                    provisionStep == SSH_STEP -> provision.detect()
                    provisionStep == DETECT_STEP -> provision.plan()
                    provisionStep == PLAN_STEP -> provision.reviewApply()
                }
            }
        }.withMargins(left = 14, top = 12, right = 14, bottom = 12)
    }

    // Literal dimensions retain the surrounding form typography and spacing.
    @Suppress("MagicNumber")
    private fun addOperationStatus(parent: LinearLayout, serverId: String? = null) {
        if (serverId != null && serverId != provisionState.serverId) return
        parent.addView(vertical().apply {
            id = R.id.vpn0wn_vps_operation_status
            renderOperationStatus(this)
        })
    }

    // Literal spacing preserves the surrounding status typography.
    @Suppress("MagicNumber")
    private fun renderOperationStatus(parent: LinearLayout) {
        parent.removeAllViews()
        val problem = provisionState.problem
        if (problem != null) parent.addView(mono(str(when (problem) {
            Problem.INVALID_INPUT -> R.string.vpn0wn_ssh_invalid_input
            Problem.CONNECTION_FAILED -> R.string.vpn0wn_ssh_connection_failed
            Problem.INVALID_RESPONSE -> R.string.vpn0wn_ssh_invalid_response
            Problem.ACCESS_MISSING -> R.string.vpn0wn_saved_ssh_missing
            Problem.CONNECTION_CHANGED -> R.string.vpn0wn_ssh_connection_changed
            Problem.STORAGE_FAILED -> R.string.vpn0wn_saved_ssh_failed
            Problem.STORAGE_CONFLICT -> R.string.vpn0wn_saved_ssh_conflict
            Problem.CANCELLED -> R.string.vpn0wn_ssh_cancelled
        }), 11, R.color.vpn0wn_warn).apply {
            id = R.id.vpn0wn_vps_operation_problem
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(dp(14), dp(8), dp(14), dp(8))
        })
        val saved = when (provisionState.savedAccess) {
            SavedAccess.NONE -> null
            SavedAccess.SAVED -> R.string.vpn0wn_saved_ssh_saved
            SavedAccess.METADATA_ONLY -> R.string.vpn0wn_saved_ssh_metadata_only
            SavedAccess.UNKNOWN -> R.string.vpn0wn_saved_ssh_unknown
            SavedAccess.CLEARED -> R.string.vpn0wn_saved_ssh_cleared
        }
        if (saved != null) parent.addView(mono(str(saved), 11, R.color.vpn0wn_ink_2).apply { setPadding(dp(14), dp(8), dp(14), dp(8)) })
    }

    private fun addUser() {
        toast(str(R.string.vpn0wn_peer_mutation_unavailable))
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun userRow(server: VpsDeployServer, user: VpsDeployUser, index: Int): View {
        val row = horizontal(Gravity.CENTER_VERTICAL).apply {
            minimumHeight = dp(48)
            actionAccessibility(Button::class.java.name)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                screen = Screen.User(server.id, user.id)
                render()
            }
        }
        row.addView(View(requireContext()).apply { setBackgroundColor(color(user.status.colorRes)) }, LinearLayout.LayoutParams(dp(3), -1))
        val body = vertical().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        body.addView(horizontal().apply {
            addView(label(str(R.string.vpn0wn_user_index, (index + 1).toString().padStart(2, '0'))).apply {
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label(str(user.status.labelRes), user.status.colorRes), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
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
            pair.forEachIndexed { index, spec ->
                row.addView(actionTile(spec), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    marginEnd = if (index < pair.lastIndex) dp(10) else 0
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        return grid
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun actionTile(spec: VpsDeployAction): View {
        val border = if (spec.colorRes == R.color.vpn0wn_ink) R.color.vpn0wn_rule else spec.colorRes
        return card(border).apply {
            alpha = if (spec.disabled) 0.45f else 1f
            isEnabled = !spec.disabled
            minimumHeight = dp(104)
            actionAccessibility(Button::class.java.name)
            setPadding(dp(12))
            if (!spec.disabled) setOnClickListener { spec.onClick() }
            addView(ImageView(requireContext()).apply {
                setImageDrawable(AppCompatResources.getDrawable(requireContext(), spec.iconRes))
                setColorFilter(color(spec.colorRes))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(Space(requireContext()), LinearLayout.LayoutParams(1, 0, 1f))
            addView(mono(spec.label, 12, spec.colorRes, medium = true))
            addView(mono(spec.sub, 10, R.color.vpn0wn_ink_3).apply { setPadding(0, dp(3), 0, 0) })
        }
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
    private fun stepTracker(steps: List<String>, current: Int): View = HorizontalScrollView(requireContext()).apply {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(color(R.color.vpn0wn_paper_2))
        val row = horizontal(Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            steps.forEachIndexed { index, step ->
                val done = index < current
                val active = index == current
                val stateColor = when {
                    done -> R.color.vpn0wn_signal_deep
                    active -> R.color.vpn0wn_warn
                    else -> R.color.vpn0wn_ink_3
                }
                addView(vertical(Gravity.CENTER).apply {
                    minimumWidth = dp(48)
                    addView(horizontal(Gravity.CENTER_VERTICAL).apply {
                        addView(View(requireContext()).apply {
                            visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
                            setBackgroundColor(color(if (index <= current) R.color.vpn0wn_signal_deep else R.color.vpn0wn_rule))
                        }, LinearLayout.LayoutParams(0, dp(1), 1f))
                        addView(TextView(requireContext()).apply {
                            setTextAppearance(R.style.Vpn0wnMonoSubText)
                            text = if (done) "✓" else (index + 1).toString().padStart(2, '0')
                            gravity = Gravity.CENTER
                            typeface = monoTypeface()
                            textSize = 9f
                            minWidth = dp(22)
                            minHeight = dp(22)
                            setTextColor(color(stateColor))
                            background = rounded(if (done) R.color.vpn0wn_paper_3 else R.color.vpn0wn_paper_2, stateColor, dp(6))
                        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                        addView(View(requireContext()).apply {
                            visibility = if (index == steps.lastIndex) View.INVISIBLE else View.VISIBLE
                            setBackgroundColor(color(if (done) R.color.vpn0wn_signal_deep else R.color.vpn0wn_rule))
                        }, LinearLayout.LayoutParams(0, dp(1), 1f))
                    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    addView(label(step).apply {
                        textSize = 8f
                        setSingleLine(true)
                        setHorizontallyScrolling(false)
                        setTextColor(color(stateColor))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(3), 0, 0)
                    })
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 1f))
            }
        }
        val columns = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<LinearLayout>()
        // Equal native minimums prevent weighted fill from narrowing a longer translated step.
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        columns.forEach { it.measure(unspecified, unspecified) }
        val minimumColumnWidth = columns.maxOf { it.measuredWidth }
        columns.forEach { it.minimumWidth = minimumColumnWidth }
        addView(row)
        val activeColumn = row.getChildAt(current)
        doOnLayout {
            requestChildRectangleOnScreen(row, Rect(activeColumn.left, activeColumn.top, activeColumn.right, activeColumn.bottom), true)
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun sectionHeader(index: Int, title: String, hint: String): View {
        return VpsSectionHeaderLayout(
            requireContext(),
            label("§${index.toString().padStart(2, '0')}", R.color.vpn0wn_rule_strong),
            mono(title, 13, R.color.vpn0wn_ink, medium = true).apply { setPadding(dp(10), 0, dp(10), 0) },
            View(requireContext()).apply { setBackgroundColor(color(R.color.vpn0wn_rule)) },
            label(hint).apply { setPaddingRelative(dp(8), 0, 0, 0) },
        ).apply {
            setPadding(0, dp(22), 0, dp(10))
        }
    }

    private fun panel(block: LinearLayout.() -> Unit): View = card().apply(block).withMargins(bottom = 14)

    // Local text/padding values preserve the configuration-row presentation.
    @Suppress("MagicNumber")
    private fun LinearLayout.configLine(title: String, value: String, copy: Boolean = false, wrap: Boolean = false) {
        val row = horizontal(Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        row.addView(vertical().apply {
            addView(label(title))
            addView(mono(value, 13, R.color.vpn0wn_ink).apply {
                setSingleLine(!wrap)
                ellipsize = if (wrap) null else android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (copy) {
            row.addView(iconButton(R.drawable.ic_vpn0wn_copy, str(R.string.vpn0wn_copy_value, title)) { copyToClipboard(title, value) })
        }
        addView(row)
        addView(View(requireContext()).apply { setBackgroundColor(color(R.color.vpn0wn_rule)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun LinearLayout.editLine(
        fieldId: Int,
        title: String,
        hint: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        onChange: (String) -> Unit = {},
    ) {
        addView(editLineView(fieldId, title, hint, value, inputType, onChange))
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun LinearLayout.boxedEditLine(
        fieldId: Int,
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
            addView(label(title).apply { labelFor = fieldId; setPadding(0, 0, 0, dp(8)) })
            addView(EditText(requireContext()).apply {
                setTextAppearance(R.style.Vpn0wnEditorField)
                id = fieldId
                setText(value)
                this.hint = hint
                this.inputType = inputType
                isSaveEnabled = false
                isEnabled = !provisionState.busy
                typeface = monoTypeface()
                this.textSize = textSize.toFloat()
                setTextColor(color(R.color.vpn0wn_ink))
                setHintTextColor(color(R.color.vpn0wn_ink_3))
                setSingleLine(minLines == 1)
                this.minLines = minLines
                gravity = if (minLines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL
                background = rounded(R.color.vpn0wn_paper_3, R.color.vpn0wn_rule_strong, dp(4))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                doAfterTextChanged { if (isAttachedToWindow && isEnabled) onChange(it?.toString().orEmpty()) }
            }, LinearLayout.LayoutParams(-1, WRAP_CONTENT))
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun editLineView(
        fieldId: Int,
        title: String,
        hint: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        onChange: (String) -> Unit = {},
    ): View = vertical().apply {
        setPadding(dp(14), dp(8), dp(14), dp(10))
        addView(label(title).apply { labelFor = fieldId })
        addView(EditText(requireContext()).apply {
            setTextAppearance(R.style.Vpn0wnEditorField)
            id = fieldId
            setText(value)
            this.hint = hint
            this.inputType = inputType
            isSaveEnabled = false
            isEnabled = !provisionState.busy
            typeface = monoTypeface()
            textSize = 13f
            if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER) {
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            }
            setTextColor(color(R.color.vpn0wn_ink))
            setHintTextColor(color(R.color.vpn0wn_ink_3))
            setSingleLine((inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.vpn0wn_rule_strong))
            setPadding(0, dp(2), 0, dp(2))
            doAfterTextChanged { if (isAttachedToWindow && isEnabled) onChange(it?.toString().orEmpty()) }
        })
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): View {
        return horizontal().apply {
            background = rounded(R.color.vpn0wn_paper_2, R.color.vpn0wn_rule_strong, dp(6))
            options.forEach { (key, text) ->
                addView(TextView(requireContext()).apply {
                    setTextAppearance(R.style.Vpn0wnMonoSubText)
                    this.text = text
                    gravity = Gravity.CENTER
                    minHeight = dp(48)
                    typeface = monoTypeface()
                    textSize = 10f
                    isSelected = key == selected
                    setTextColor(color(if (key == selected) R.color.vpn0wn_paper else R.color.vpn0wn_ink_3))
                    background = if (key == selected) rounded(R.color.vpn0wn_ink, R.color.vpn0wn_ink, dp(6)) else null
                    actionAccessibility(RadioButton::class.java.name, key == selected)
                    setPadding(dp(8))
                    setOnClickListener { onSelect(key) }
                    isEnabled = !provisionState.busy
                }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun statTile(title: String, value: String, dot: String, colorRes: Int, active: Boolean, onClick: () -> Unit): View {
        return vertical().apply {
            setBackgroundColor(color(if (active) R.color.vpn0wn_paper_3 else R.color.vpn0wn_paper_2))
            isSelected = active
            actionAccessibility(Button::class.java.name)
            setPadding(dp(14), dp(10), dp(14), 0)
            setOnClickListener { onClick() }
            addView(label("$dot $title".trim(), if (active) colorRes else R.color.vpn0wn_ink_3).apply { textSize = 9f })
            addView(mono(value, 20, if (active) colorRes else R.color.vpn0wn_ink, medium = true).apply { setPadding(0, dp(1), 0, 0) })
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
    private fun dashedButton(text: String, stroke: Int = R.color.vpn0wn_rule_strong, icon: Int? = R.drawable.ic_vpn0wn_plus, onClick: () -> Unit): View {
        return horizontal(Gravity.CENTER).apply {
            background = rounded(R.color.vpn0wn_paper, stroke, dash = true)
            minimumHeight = dp(48)
            contentDescription = text.removePrefix("+ ").trim()
            actionAccessibility(Button::class.java.name)
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
            setTextAppearance(R.style.Vpn0wnMonoSubText)
            this.text = text.uppercase(Locale.ROOT)
            gravity = Gravity.CENTER
            typeface = monoTypeface()
            textSize = 10f
            minHeight = dp(48)
            letterSpacing = 0.06f
            setTextColor(color(R.color.vpn0wn_ink_2))
            background = rounded(R.color.vpn0wn_paper_2, R.color.vpn0wn_rule_strong, dp(4))
            actionAccessibility(Button::class.java.name)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onClick() }
        }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun rowButton(text: String, icon: Int = R.drawable.ic_vpn0wn_plus, onClick: () -> Unit): View = horizontal(Gravity.CENTER_VERTICAL).apply {
        minimumHeight = dp(48)
        actionAccessibility(Button::class.java.name)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(ImageView(requireContext()).apply {
            setImageDrawable(AppCompatResources.getDrawable(requireContext(), icon))
            setColorFilter(color(R.color.vpn0wn_ink_3))
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(10) })
        addView(mono(text.removePrefix("+ ").trim(), 11, R.color.vpn0wn_ink_3).apply { letterSpacing = 0.06f })
        setOnClickListener { onClick() }
    }

    // Local layout/drawing dimensions and paint values preserve the visual specification.
    @Suppress("MagicNumber")
    private fun iconButton(icon: Int, name: String, onClick: () -> Unit): View = ImageButton(requireContext()).apply {
        setImageDrawable(AppCompatResources.getDrawable(requireContext(), icon))
        setColorFilter(color(R.color.vpn0wn_ink_2))
        background = null
        contentDescription = name
        actionAccessibility(ImageButton::class.java.name)
        setPadding(dp(16))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun View.actionAccessibility(role: String, checked: Boolean? = null) {
        isFocusable = true
        context.withStyledAttributes(attrs = intArrayOf(android.R.attr.selectableItemBackground)) {
            val feedback = getDrawable(0)
            val surface = background
            foreground = null
            background = if (surface != null && feedback != null) {
                LayerDrawable(arrayOf(surface, feedback))
            } else {
                feedback ?: surface
            }
        }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = role
                if (checked != null) {
                    info.isCheckable = true
                    info.isChecked = checked
                }
            }
        }
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
        setTextAppearance(R.style.Vpn0wnLabelText)
        typeface = monoTypeface()
        this.text = text.uppercase(Locale.ROOT)
        setTextColor(color(colorRes))
    }

    private fun mono(text: String, size: Int, colorRes: Int, medium: Boolean = false): TextView = TextView(requireContext()).apply {
        setTextAppearance(R.style.Vpn0wnMonoValueText)
        this.text = text
        typeface = if (medium) cachedMediumMonoTypeface else monoTypeface()
        textSize = size.toFloat()
        setTextColor(color(colorRes))
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
        when (val current = screen) {
            Screen.Fleet -> {
                parentFragmentManager.popBackStack()
                return
            }
            is Screen.Detail -> screen = Screen.Fleet
            is Screen.Provision -> provision.back()
            is Screen.User -> screen = Screen.Detail(current.serverId)
        }
        render()
    }

    private companion object {
        const val SSH_STEP = 0
        const val DETECT_STEP = 1
        const val PLAN_STEP = 2
        const val APPLY_STEP = 3
    }

    private fun server(id: String) = servers.find { it.id == id }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun color(res: Int): Int = ContextCompat.getColor(requireContext(), res)
    private fun str(res: Int, vararg args: Any): String = getString(res, *args)
    private fun monoTypeface(): Typeface = cachedMonoTypeface

    private sealed class Screen {
        object Fleet : Screen()
        data class Detail(val serverId: String) : Screen()
        object Provision : Screen()
        data class User(val serverId: String, val userId: String) : Screen()
    }

}
