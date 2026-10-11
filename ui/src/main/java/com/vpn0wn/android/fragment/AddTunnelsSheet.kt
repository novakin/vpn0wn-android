/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */
package com.vpn0wn.android.fragment

import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.vpn0wn.android.R
import androidx.core.content.ContextCompat

class AddTunnelsSheet : BottomSheetDialogFragment() {

    private var behavior: BottomSheetBehavior<FrameLayout>? = null
    private val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit

        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                dismiss()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (savedInstanceState != null) dismiss()
        val view = inflater.inflate(R.layout.add_tunnels_bottom_sheet, container, false)
        if (activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) != true) {
            val qrcode = view.findViewById<View>(R.id.create_from_qrcode)
            qrcode.isEnabled = false
            qrcode.visibility = View.GONE
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val options = listOf(
            Triple(R.id.create_from_qrcode, R.string.create_from_qr_code, R.string.vpn0wn_add_sheet_qr_subtitle),
            Triple(R.id.create_from_file, R.string.create_from_file, R.string.vpn0wn_add_sheet_file_subtitle),
            Triple(R.id.create_empty, R.string.create_empty, R.string.vpn0wn_add_sheet_manual_subtitle)
        )
        for ((id, title, subtitle) in options) {
            val option = view.findViewById<View>(id)
            option.contentDescription = "${getString(title)}. ${getString(subtitle)}"
            option.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
        }
        view.findViewById<View>(R.id.create_empty).setOnClickListener {
            dismiss()
            onRequestCreateConfig()
        }
        view.findViewById<View>(R.id.create_from_file).setOnClickListener {
            dismiss()
            onRequestImportConfig()
        }
        view.findViewById<View>(R.id.create_from_qrcode).setOnClickListener {
            dismiss()
            onRequestScanQRCode()
        }
        val gradientDrawable = GradientDrawable().apply {
            setColor(ContextCompat.getColor(requireContext(), R.color.vpn0wn_paper))
            val radius = resources.getDimension(R.dimen.bottom_sheet_corner_radius)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        view.background = gradientDrawable
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        behavior = dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            peekHeight = 0
            addBottomSheetCallback(bottomSheetCallback)
        }
    }

    override fun onDestroyView() {
        behavior?.removeBottomSheetCallback(bottomSheetCallback)
        behavior = null
        super.onDestroyView()
    }

    private fun onRequestCreateConfig() {
        setFragmentResult(REQUEST_KEY_NEW_TUNNEL, Bundle().apply { putString(REQUEST_METHOD, REQUEST_CREATE) })
    }

    private fun onRequestImportConfig() {
        setFragmentResult(REQUEST_KEY_NEW_TUNNEL, Bundle().apply { putString(REQUEST_METHOD, REQUEST_IMPORT) })
    }

    private fun onRequestScanQRCode() {
        setFragmentResult(REQUEST_KEY_NEW_TUNNEL, Bundle().apply { putString(REQUEST_METHOD, REQUEST_SCAN) })
    }

    companion object {
        const val REQUEST_KEY_NEW_TUNNEL = "request_new_tunnel"
        const val REQUEST_METHOD = "request_method"
        const val REQUEST_CREATE = "request_create"
        const val REQUEST_IMPORT = "request_import"
        const val REQUEST_SCAN = "request_scan"
    }
}
