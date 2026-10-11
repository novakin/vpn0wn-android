package com.vpn0wn.android.util

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [ToolsInstallerAdmissionTest.LibraryShadow::class])
class ToolsInstallerAdmissionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun unrelatedAndNearMatchApplicationsCannotEnterPrivilegedInstallation() {
        for (packageName in listOf(
            "org.example.unrelated",
            "com.vpn0wn.unrelated",
            "com.vpn0wn.android.release",
            "com.vpn0wn.android.debug.extra",
            "com.vpn0wn.android.debuggable",
            "com.wireguard.android",
            "com.wireguard.android.debug",
        )) {
            val context = context(packageName)
            val shell = RecordingRootShell(context)
            try {
                ToolsInstaller(context, shell).install()
                fail("Expected package admission refusal")
            } catch (_: SecurityException) {
            }
            assertEquals(0, shell.commands)
            assertFalse(File(context.codeCacheDir, "bin").exists())
        }
    }

    @Test
    fun selectedReleaseAndDebugApplicationsReachSafeConflictObservation() {
        for (packageName in listOf("com.vpn0wn.android", "com.vpn0wn.android.debug")) {
            val context = context(packageName)
            val privateTools = File(context.codeCacheDir, "bin").apply { assertTrue(mkdir()) }
            for (name in listOf("wg", "wg-quick")) File(privateTools, name).writeText("synthetic $name")
            val shell = RecordingRootShell(context)
            assertEquals(ToolsInstaller.BLOCKED, ToolsInstaller(context, shell).install())
            assertEquals(1, shell.commands)
            for (name in listOf("wg", "wg-quick")) {
                assertEquals("synthetic $name", File(privateTools, name).readText())
            }
        }
    }

    @Test
    fun concurrentColdProbeWaitsForBackendExtractionAndLeavesToolsAvailable() {
        val context = context("com.vpn0wn.android.debug")
        val shell = RecordingRootShell(context)
        val installer = ToolsInstaller(context, shell)
        LibraryShadow.entered = CountDownLatch(1)
        LibraryShadow.release = CountDownLatch(1)
        LibraryShadow.extractions.set(0)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val backend = executor.submit { installer.ensureToolsAvailable() }
            assertTrue(LibraryShadow.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val probeStarted = CountDownLatch(1)
            val probeThread = AtomicReference<Thread>()
            val probe = executor.submit<Int> {
                probeThread.set(Thread.currentThread())
                probeStarted.countDown()
                installer.areInstalled()
            }
            assertTrue(probeStarted.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (!probe.isDone && probeThread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            val probeWaited = !probe.isDone && probeThread.get().state == Thread.State.BLOCKED
            LibraryShadow.release.countDown()
            backend.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertEquals(ToolsInstaller.BLOCKED, probe.get(WAIT_SECONDS, TimeUnit.SECONDS))
            assertTrue("The probe must wait for the backend's cold extraction", probeWaited)
            assertEquals(2, LibraryShadow.extractions.get())
            installer.ensureToolsAvailable()
            assertFalse(installer.extract())
            assertEquals(1, shell.commands)
            for (name in listOf("wg", "wg-quick")) {
                val tool = File(context.codeCacheDir, "bin/$name")
                assertEquals("synthetic $name", tool.readText())
                assertTrue(tool.canExecute())
                assertFalse(File(context.codeCacheDir, "bin/$name.tmp").exists())
            }
        } finally {
            LibraryShadow.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private fun context(packageName: String): Context {
        val codeCache = folder.newFolder(packageName)
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getPackageName() = packageName
            override fun getCodeCacheDir() = codeCache
        }
    }

    private class RecordingRootShell(context: Context) : RootShell(context) {
        var commands = 0

        override fun run(output: MutableCollection<String>?, command: String): Int {
            commands++
            return ToolsInstaller.BLOCKED
        }
    }

    @Implements(value = SharedLibraryLoader::class, isInAndroidSdk = false)
    class LibraryShadow private constructor() {
        companion object {
            lateinit var entered: CountDownLatch
            lateinit var release: CountDownLatch
            val extractions = AtomicInteger()

            @JvmStatic
            @Implementation
            fun extractLibrary(context: Context, libName: String, destination: File): Boolean {
                assertEquals(File(context.codeCacheDir, "bin"), destination.parentFile)
                destination.writeText("synthetic $libName")
                if (extractions.incrementAndGet() == 1) {
                    entered.countDown()
                    assertTrue(release.await(WAIT_SECONDS, TimeUnit.SECONDS))
                }
                return true
            }
        }
    }

    companion object {
        private const val WAIT_SECONDS = 5L
    }
}
