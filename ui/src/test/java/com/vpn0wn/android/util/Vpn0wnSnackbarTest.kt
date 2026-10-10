/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.util

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.vpn0wn.android.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class Vpn0wnSnackbarTest {
    @Test
    fun themedTintCannotOverrideCustomBackgroundAndHideMessage() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
        try {
            val content = FrameLayout(activity)
            activity.setContentView(content)
            val snackbar = Snackbar.make(content, "Synthetic validation error", Snackbar.LENGTH_INDEFINITE)
            snackbar.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.tunnl_ink)))
            snackbar.vpn0wnStyle()
            assertNull(ReflectionHelpers.getField<ColorStateList>(snackbar.view, "backgroundTint"))
            val background = snackbar.view.background as GradientDrawable
            assertEquals(ContextCompat.getColor(activity, R.color.tunnl_paper_2), background.color!!.defaultColor)
            val text = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            assertEquals(ContextCompat.getColor(activity, R.color.tunnl_ink), text.currentTextColor)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
