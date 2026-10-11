/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.util

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.vpn0wn.android.Application
import com.vpn0wn.android.activity.SettingsActivity
import kotlinx.coroutines.CoroutineScope

fun View.applySafeAreaInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val handled = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val safeArea = insets.getInsets(handled)
        view.updatePadding(left = safeArea.left, top = safeArea.top, right = safeArea.right, bottom = safeArea.bottom)
        WindowInsetsCompat.Builder(insets).setInsets(handled, Insets.NONE).build()
    }
    ViewCompat.requestApplyInsets(this)
}

fun Context.resolveAttribute(@AttrRes attrRes: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attrRes, typedValue, true)
    return typedValue.data
}

val Any.applicationScope: CoroutineScope
    get() = Application.getCoroutineScope()

val Preference.activity: SettingsActivity
    get() = context as? SettingsActivity
        ?: error("Failed to resolve SettingsActivity")

val Preference.lifecycleScope: CoroutineScope
    get() = activity.lifecycleScope
