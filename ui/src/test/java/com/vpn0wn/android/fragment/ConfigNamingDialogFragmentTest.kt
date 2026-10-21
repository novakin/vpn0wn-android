package com.vpn0wn.android.fragment

import android.app.Application
import android.content.DialogInterface
import android.graphics.Path
import android.os.Bundle
import android.os.Looper
import android.text.TextPaint
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.vpn0wn.android.R
import com.vpn0wn.android.configStore.ConfigStore
import com.vpn0wn.android.model.TunnelManager
import com.vpn0wn.android.model.TunnelTestEnvironment
import com.vpn0wn.config.Config as TunnelConfig
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigNamingDialogFragmentTest {
    @get:Rule val folder = TemporaryFolder()

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeNamingFieldUsesPackagedMonoGlyphsAndSharedFeatures() = namingTest {
        name.setText("home-lab_01")
        val actual = TextPaint(name.paint)
        val expected = TextPaint(name.paint).apply {
            typeface = requireNotNull(ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular))
            fontFeatureSettings = "ss01, zero, tnum"
        }
        assertEquals("Configured shared features", "ss01, zero, tnum", actual.fontFeatureSettings)
        for (sample in listOf("home-lab_01", "0123456789", "a0O1Il")) {
            fun outline(paint: TextPaint): FloatArray {
                val path = Path()
                paint.getTextPath(sample, 0, sample.length, 0f, 0f, path)
                return path.approximate(0.01f)
            }
            val actualPath = outline(actual)
            val expectedPath = outline(expected)
            assertTrue("Native actual outline exists", actualPath.isNotEmpty())
            assertTrue("Native packaged control outline exists", expectedPath.isNotEmpty())
            assertArrayEquals("Packaged Mono glyphs: $sample", expectedPath, actualPath, 0.01f)
            assertEquals("Packaged Mono advance: $sample", expected.measureText(sample), actual.measureText(sample), 0.01f)
        }
        assertEquals("Font check preserves field content", "home-lab_01", name.text.toString())
        assertTrue("Font check keeps native dialog", dialog.isShowing)
        assertTrue("Font check creates no tunnel", environment.store.configs.isEmpty())
        assertEquals(0, store.calls.get())
    }

    @Test fun invalidNameRetainsEditableDialogAndProfile() = namingTest {
        submit("")
        settle()
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.tunnel_error_invalid_name), error.error.toString())
        assertTrue(name.isEnabled)
        assertTrue(create.isEnabled)
        assertTrue(environment.store.configs.isEmpty())
        assertEquals(0, store.calls.get())
    }

    @Test fun duplicateNameRetainsDialogWithoutReplacingExistingProfile() = namingTest(existing = true) {
        submit("existing")
        settle()
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.tunnel_error_already_exists, "existing"), error.error.toString())
        assertEquals(51821, environment.store.configs.getValue("existing").getInterface().listenPort.get())
        assertEquals(0, store.calls.get())
    }

    @Test fun correctedNameImportsOriginalScannedProfileThenDismisses() = namingTest {
        submit("")
        settle()
        submit("scanned")
        settle()
        assertFalse(dialog.isShowing)
        assertEquals(setOf("scanned"), environment.store.configs.keys.toSet())
        assertEquals(profile.toWgQuickString(), environment.store.configs.getValue("scanned").toWgQuickString())
        assertEquals(1, store.calls.get())
    }

    @Test fun failedWriteAllowsSameProfileRetry() = namingTest {
        store.failCreate = true
        submit("scanned")
        settle()
        assertTrue(dialog.isShowing)
        assertEquals("Injected create failure", error.error.toString())
        assertTrue(environment.store.configs.isEmpty())
        store.failCreate = false
        submit("scanned")
        settle()
        assertFalse(dialog.isShowing)
        assertEquals(profile.toWgQuickString(), environment.store.configs.getValue("scanned").toWgQuickString())
        assertEquals(2, store.calls.get())
    }

    @Test fun repeatedSubmitCannotQueueCreationWhileStoreIsPending() = namingTest {
        store.blockCreate = true
        submit("scanned")
        assertTrue(withContext(Dispatchers.IO) { store.entered.await(5, TimeUnit.SECONDS) })
        assertTrue(dialog.isShowing)
        assertFalse(create.isEnabled)
        assertFalse(name.isEnabled)
        create.performClick()
        testScope.runCurrent()
        store.release.countDown()
        settle()
        assertEquals(1, store.calls.get())
        assertEquals(setOf("scanned"), environment.store.configs.keys.toSet())
        assertFalse(dialog.isShowing)
    }

    @Test fun cancelWithoutSubmissionCreatesNothing() = namingTest {
        name.setText("scanned")
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        testScope.runCurrent()
        assertFalse(dialog.isShowing)
        assertTrue(environment.store.configs.isEmpty())
        assertEquals(0, store.calls.get())
    }

    @Test fun dismissalDuringFailedWriteCannotPublishIntoOldDialog() = namingTest {
        store.blockCreate = true
        store.failCreate = true
        submit("scanned")
        assertTrue(withContext(Dispatchers.IO) { store.entered.await(5, TimeUnit.SECONDS) })
        fragment.dismiss()
        activity.supportFragmentManager.executePendingTransactions()
        store.release.countDown()
        settle()
        assertFalse(dialog.isShowing)
        assertNull(error.error)
        assertTrue(environment.store.configs.isEmpty())
    }

    @Test fun successfulWriteAfterStateSaveDismissesOnResumeWithoutRepeatingCreate() = namingTest {
        store.blockCreate = true
        submit("scanned")
        assertTrue(withContext(Dispatchers.IO) { store.entered.await(5, TimeUnit.SECONDS) })
        controller.pause().saveInstanceState(Bundle()).stop()
        assertTrue(fragment.isStateSaved)
        store.release.countDown()
        settle()
        assertEquals(setOf("scanned"), environment.store.configs.keys.toSet())
        assertTrue(fragment.isAdded)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        testScope.runCurrent()
        activity.supportFragmentManager.executePendingTransactions()
        assertFalse(fragment.isAdded)
        assertEquals(1, store.calls.get())
    }

    @Test fun failedWriteWhileStoppedRemainsEditableOnResume() = namingTest {
        store.blockCreate = true
        store.failCreate = true
        submit("scanned")
        assertTrue(withContext(Dispatchers.IO) { store.entered.await(5, TimeUnit.SECONDS) })
        controller.pause().saveInstanceState(Bundle()).stop()
        store.release.countDown()
        settle()
        controller.start().resume().visible()
        testScope.runCurrent()
        assertTrue(dialog.isShowing)
        assertTrue(create.isEnabled)
        assertTrue(name.isEnabled)
        assertEquals("Injected create failure", error.error.toString())
        store.failCreate = false
        submit("scanned")
        settle()
        assertFalse(dialog.isShowing)
        assertEquals(2, store.calls.get())
        assertEquals(setOf("scanned"), environment.store.configs.keys.toSet())
    }

    private fun namingTest(existing: Boolean = false, block: suspend Fixture.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val environment = TunnelTestEnvironment(folder.root, backgroundScope)
        val store = CreateStore(environment.store)
        val manager = TunnelManager(store)
        com.vpn0wn.android.Application::class.java.getDeclaredField("tunnelManager").apply { isAccessible = true }
            .set(environment.application, manager)
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        try {
            if (existing) environment.store.configs["existing"] = TunnelTestEnvironment.profile(51821)
            manager.getTunnels()
            controller.setup()
            val profile = TunnelTestEnvironment.profile()
            val fragment = ConfigNamingDialogFragment.newInstance(profile.toWgQuickString())
            fragment.showNow(activity.supportFragmentManager, "naming")
            runCurrent()
            Fixture(this, environment, store, controller, fragment, profile).block()
        } finally {
            store.release.countDown()
            val activityJob = activity.lifecycleScope.coroutineContext[Job]!!
            val fragmentJobs = activity.supportFragmentManager.fragments.map { it.lifecycleScope.coroutineContext[Job]!! }
            controller.pause().stop().destroy()
            (fragmentJobs + activityJob).forEach { it.cancelAndJoin() }
            environment.close()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(
        val testScope: TestScope,
        val environment: TunnelTestEnvironment,
        val store: CreateStore,
        val controller: ActivityController<HostActivity>,
        val fragment: ConfigNamingDialogFragment,
        val profile: TunnelConfig,
    ) {
        val activity = controller.get()
        val dialog = fragment.requireDialog() as AlertDialog
        val name = dialog.findViewById<EditText>(R.id.tunnel_name_text)!!
        val error = dialog.findViewById<TextInputLayout>(R.id.tunnel_name_text_layout)!!
        val create = dialog.getButton(DialogInterface.BUTTON_POSITIVE)

        fun submit(value: String) {
            name.setText(value)
            create.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            testScope.runCurrent()
        }

        suspend fun settle() {
            testScope.runCurrent()
            val scopes = listOf(activity.lifecycleScope, fragment.lifecycleScope)
            scopes.flatMap { it.coroutineContext[Job]!!.children.toList() }.forEach { it.join() }
            shadowOf(Looper.getMainLooper()).idle()
            testScope.runCurrent()
            activity.supportFragmentManager.executePendingTransactions()
        }
    }

    private class CreateStore(private val delegate: ConfigStore) : ConfigStore by delegate {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var blockCreate = false
        @Volatile var failCreate = false

        override fun create(name: String, config: TunnelConfig): TunnelConfig {
            calls.incrementAndGet()
            entered.countDown()
            if (blockCreate && !release.await(5, TimeUnit.SECONDS)) throw IOException("Create fixture timed out")
            if (failCreate) throw IOException("Injected create failure")
            return delegate.create(name, config)
        }
    }

    class HostActivity : AppCompatActivity()
}
