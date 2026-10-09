package com.wireguard.android.util

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ToolsInstallerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        fixture = Fixture(folder.newFolder("installer's sandbox"))
    }

    @Test
    fun compatibleGlobalPairIsReusedWithoutWrites() {
        fixture.installGlobalPair()
        val before = fixture.snapshot()
        assertEquals(ToolsInstaller.YES or ToolsInstaller.SYSTEM, fixture.installer.areInstalled())
        assertEquals(ToolsInstaller.YES or ToolsInstaller.SYSTEM, fixture.installer.install())
        assertEquals(before, fixture.snapshot())
        assertEquals("", fixture.writes())
        assertEquals(File(fixture.local, "wg").path, fixture.shell.backendToolPath)
    }

    @Test
    fun compatibleGlobalPairDoesNotRequireMagisk() {
        fixture.installGlobalPair()
        assertTrue(fixture.modules.delete())
        assertEquals(ToolsInstaller.YES or ToolsInstaller.SYSTEM, fixture.installer.install())
        assertEquals("", fixture.writes())
    }

    @Test
    fun differingGlobalPairIsPreservedAndBlocked() {
        fixture.installGlobalPair()
        File(fixture.xbin, "wg").appendText("different binary")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun partialGlobalPairIsPreservedAndBlocked() {
        fixture.copyTool("wg", fixture.xbin)
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun effectivePathShadowBlocksAnOtherwiseCompatiblePair() {
        fixture.installGlobalPair()
        fixture.executable(File(fixture.commands, "wg"), "#!/bin/sh\nexit 0\n")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun otherSystemDestinationCannotHideBehindCompatiblePair() {
        fixture.installGlobalPair()
        fixture.executable(File(fixture.bin, "wg-quick"), "#!/bin/sh\nexit 1\n")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun destinationDirectoryAndDanglingSymlinkAreConflicts() {
        assertTrue(File(fixture.xbin, "wg").mkdir())
        Files.createSymbolicLink(File(fixture.bin, "wg-quick").toPath(), File(fixture.root, "missing").toPath())
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun pendingOverlayBlocksInstallation() {
        val tools = File(fixture.pending, "wireguard/system/bin").apply { assertTrue(mkdirs()) }
        fixture.copyTool("wg", tools)
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun activeCommandDirectoryReplacementBlocksAdmission() {
        val replacement = File(fixture.modules, "foreign/system/bin/.replace")
        assertTrue(replacement.parentFile!!.mkdirs())
        replacement.writeText("")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun pendingCommandDirectoryReplacementBlocksAdmission() {
        val replacement = File(fixture.pending, "foreign/system/xbin/.replace")
        assertTrue(replacement.parentFile!!.mkdirs())
        replacement.writeText("")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun wholeSystemReplacementBlocksCompatibleReuse() {
        fixture.installGlobalPair()
        val replacement = File(fixture.modules, "foreign/system/.replace")
        assertTrue(replacement.parentFile!!.mkdirs())
        replacement.writeText("")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun conflictingPendingOverlayBlocksCurrentCompatibleReuse() {
        fixture.installGlobalPair()
        val tools = File(fixture.pending, "wireguard/system/xbin").apply { assertTrue(mkdirs()) }
        fixture.executable(File(tools, "wg"), "#!/bin/sh\nexit 1\n")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun compatibleWireGuardOverlayCanBeReusedAndSurvives() {
        fixture.installGlobalPair()
        val tools = File(fixture.modules, "wireguard/system/xbin").apply { assertTrue(mkdirs()) }
        for (name in toolNames) fixture.copyTool(name, tools)
        File(fixture.modules, "wireguard/module.prop").writeText("id=wireguard\nauthor=zx2c4\n")
        val before = fixture.snapshot()
        assertEquals(ToolsInstaller.YES or ToolsInstaller.SYSTEM, fixture.installer.install())
        assertEquals(before, fixture.snapshot())
        assertEquals("", fixture.writes())
    }

    @Test
    fun existingWireGuardOverlayIsNeverReplaced() {
        val tools = File(fixture.modules, "wireguard/system/xbin").apply { assertTrue(mkdirs()) }
        fixture.copyTool("wg", tools)
        File(fixture.modules, "wireguard/keep").writeText("unrelated module")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun preexistingOwnDirectoryIsNeverDeletedOrRecreated() {
        assertTrue(fixture.module.mkdir())
        File(fixture.module, "keep").writeText("preexisting module")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun preexistingOwnFileIsNeverDeleted() {
        fixture.module.writeText("preexisting file")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun preexistingOwnSymlinkAndItsTargetSurvive() {
        val target = File(fixture.root, "unrelated").apply { assertTrue(mkdir()) }
        File(target, "keep").writeText("unrelated target")
        Files.createSymbolicLink(fixture.module.toPath(), target.toPath())
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun danglingOwnSymlinkSurvives() {
        Files.createSymbolicLink(fixture.module.toPath(), File(fixture.root, "missing").toPath())
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun pendingOwnModuleBlocksAdmissionEvenWithoutTools() {
        assertTrue(File(fixture.pending, "vpn0wn").mkdirs())
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun symlinkedOverlayDirectoryIsPreservedAndBlocked() {
        val overlay = File(fixture.modules, "foreign").apply { assertTrue(mkdir()) }
        Files.createSymbolicLink(File(overlay, "system").toPath(), fixture.xbin.toPath())
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun absentMagiskNeverFallsBackToSystemWrites() {
        assertTrue(fixture.modules.delete())
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun disabledMagiskNeverFallsBackToSystemWrites() {
        fixture.disable.writeText("")
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun inaccessibleMagiskNeverFallsBackToSystemWrites() {
        val identity = ProcessBuilder("/usr/bin/id", "-u").start()
        assertNotEquals("Permission controls require an unprivileged JVM", "0", identity.inputStream.bufferedReader().readText().trim())
        assertEquals(0, identity.waitFor())
        Files.setPosixFilePermissions(fixture.modules.toPath(), PosixFilePermissions.fromString("---------"))
        try {
            assertEquals(ToolsInstaller.BLOCKED, fixture.installer.areInstalled())
            assertEquals(ToolsInstaller.BLOCKED, fixture.installer.install())
            assertEquals("", fixture.writes())
        } finally {
            Files.setPosixFilePermissions(fixture.modules.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
        assertFalse(fixture.module.exists())
    }

    @Test
    fun failedRootObservationRemainsRetryableAndWritesNothing() {
        fixture.shell.failObservation = true
        assertEquals(ToolsInstaller.ERROR, fixture.installer.areInstalled())
        assertEquals(ToolsInstaller.ERROR, fixture.installer.install())
        assertEquals("", fixture.writes())
        fixture.shell.failObservation = false
        assertEquals(ToolsInstaller.NO or ToolsInstaller.MAGISK, fixture.installer.areInstalled())
    }

    @Test
    fun newlyAppearingConflictBlocksAnEarlierSafeProbe() {
        assertEquals(ToolsInstaller.NO or ToolsInstaller.MAGISK, fixture.installer.areInstalled())
        fixture.copyTool("wg", fixture.xbin)
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun noRootAuthorizationStillPropagates() {
        fixture.shell.denyRoot = true
        val failure = assertThrows(RootShell.RootShellException::class.java) { fixture.installer.install() }
        assertEquals(RootShell.RootShellException.Reason.NO_ROOT_ACCESS, failure.reason)
        assertEquals("", fixture.writes())
    }

    @Test
    fun atomicDirectoryAdmissionDoesNotCleanUpARacingOwner() {
        fixture.wrap("mkdir", """
            if [ "${'$'}1" = ${fixture.quote(fixture.module.path)} ]; then
                /bin/mkdir "${'$'}1" || exit 1
                printf 'racing owner' > "${'$'}1/keep"
                exit 1
            fi
        """.trimIndent())
        assertEquals(ToolsInstaller.BLOCKED, fixture.installer.install())
        assertEquals("racing owner", File(fixture.module, "keep").readText())
        assertFalse(fixture.writes().contains("rm"))
    }

    @Test
    fun copyFailureCleansOnlyNewlyAdmittedModuleAndAllowsRetry() {
        val foreign = File(fixture.modules, "wireguard").apply { assertTrue(mkdir()) }
        File(foreign, "keep").writeText("foreign module")
        fixture.wrap("cp", "case \"${'$'}2\" in */wg-quick) exit 1;; esac")
        assertEquals(ToolsInstaller.ERROR, fixture.installer.install())
        assertFalse(fixture.module.exists())
        assertEquals("foreign module", File(foreign, "keep").readText())
        fixture.wrap("cp")
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.install())
        fixture.assertStagedTools()
    }

    @Test
    fun metadataWriteFailureCleansOnlyNewlyAdmittedModule() {
        fixture.wrap("mkdir", """
            if [ "${'$'}1" = ${fixture.quote(fixture.module.path)} ]; then
                /bin/mkdir "${'$'}1" || exit 1
                /bin/mkdir "${'$'}1/module.prop" || exit 1
                exit 0
            fi
        """.trimIndent())
        assertEquals(ToolsInstaller.ERROR, fixture.installer.install())
        assertFalse(fixture.module.exists())
        assertTrue(fixture.writes().contains("rm"))
    }

    @Test
    fun failedCleanupRetainsIncompleteOwnedDirectoryAndBlocksOverwrite() {
        fixture.wrap("cp", "case \"${'$'}2\" in */wg-quick) exit 1;; esac")
        fixture.wrap("rm", "exit 1")
        assertEquals(ToolsInstaller.ERROR, fixture.installer.install())
        assertTrue(fixture.module.isDirectory)
        assertTrue(File(fixture.module, "system/xbin/wg").isFile)
        assertFalse(File(fixture.module, "system/xbin/wg-quick").exists())
        fixture.wrap("cp")
        fixture.wrap("rm")
        fixture.clearWrites()
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun stagedVerificationRejectsCorruptedCopies() {
        fixture.wrap("cp", "/bin/cp \"${'$'}@\" || exit 1; printf 'corrupted' >> \"${'$'}2\"; exit 0")
        assertEquals(ToolsInstaller.ERROR, fixture.installer.install())
        assertFalse(fixture.module.exists())
    }

    @Test
    fun failedSelinuxLabelStopsStagingAndCleansOwnedDirectory() {
        fixture.executable(File(fixture.commands, "chcon"), "#!/bin/sh\nexit 1\n")
        assertEquals(ToolsInstaller.ERROR, fixture.installer.install())
        assertFalse(fixture.module.exists())
    }

    @Test
    fun successfulStagingIsReconciledWithoutAnotherWrite() {
        assertEquals(ToolsInstaller.NO or ToolsInstaller.MAGISK, fixture.installer.areInstalled())
        assertEquals(File(fixture.local, "wg").path, fixture.shell.backendToolPath)
        assertEquals("", fixture.writes())
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.install())
        fixture.assertStagedTools()
        val before = fixture.snapshot()
        val writes = fixture.writes()
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.areInstalled())
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.install())
        assertEquals(before, fixture.snapshot())
        assertEquals(writes, fixture.writes())
    }

    @Test
    fun lostResponseKeepsCommittedModuleAndNextProbeReconciles() {
        fixture.shell.dropResponse = true
        assertEquals(ToolsInstaller.ERROR, fixture.installer.install())
        fixture.assertStagedTools()
        val writes = fixture.writes()
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.areInstalled())
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.install())
        assertEquals(writes, fixture.writes())
    }

    @Test
    fun unrelatedMetadataCannotClaimStagedModuleOwnership() {
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.install())
        File(fixture.module, "module.prop").writeText("id=wireguard\nauthor=zx2c4\n")
        fixture.clearWrites()
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun disabledStagedModuleCannotReportSuccessfulInstallation() {
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.install())
        File(fixture.module, "disable").writeText("")
        fixture.clearWrites()
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun stagedModuleWithServiceHookIsPreservedAndBlocked() = assertExtraStagedPayload("service.sh")

    @Test
    fun stagedModuleWithBootHookIsPreservedAndBlocked() = assertExtraStagedPayload("post-fs-data.sh")

    @Test
    fun stagedModuleWithHiddenRootEntryIsPreservedAndBlocked() = assertExtraStagedPayload(".foreign")

    @Test
    fun stagedModuleWithHiddenBinaryEntryIsPreservedAndBlocked() = assertExtraStagedPayload("system/xbin/.foreign")

    @Test
    fun stagedModuleWithExtraSelectedBinaryIsPreservedAndBlocked() = assertExtraStagedPayload("system/xbin/foreign")

    @Test
    fun stagedModuleWithAnotherSystemPayloadIsPreservedAndBlocked() = assertExtraStagedPayload("system/bin/foreign")

    private fun assertExtraStagedPayload(relativePath: String) {
        assertEquals(ToolsInstaller.YES or ToolsInstaller.MAGISK, fixture.installer.install())
        val extra = File(fixture.module, relativePath)
        extra.parentFile!!.mkdirs()
        extra.writeText("preexisting foreign content")
        fixture.clearWrites()
        fixture.assertBlockedAndUnchanged()
    }

    @Test
    fun legacyPackageGateStillRejectsUnrelatedApps() {
        val context = object : ContextWrapper(fixture.context) {
            override fun getPackageName(): String = "org.example.unrelated"
        }
        val installer = ToolsInstaller(context, fixture.shell)
        assertThrows(SecurityException::class.java) { installer.install() }
        assertEquals("", fixture.writes())
    }

    private class Fixture(val root: File) {
        val local = File(root, "cache/bin").apply { assertTrue(mkdirs()) }
        val xbin = File(root, "system/xbin").apply { assertTrue(mkdirs()) }
        val bin = File(root, "system/bin").apply { assertTrue(mkdirs()) }
        val modules = File(root, "adb/modules").apply { assertTrue(mkdirs()) }
        val pending = File(root, "adb/modules_update")
        val module = File(modules, "vpn0wn")
        val disable = File(root, ".disable_magisk")
        val commands = File(root, "commands").apply { assertTrue(mkdir()) }
        private val writeLog = File(root, "writes")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getCodeCacheDir(): File = local.parentFile!!
            override fun getPackageName(): String = "com.wireguard.android"
        }
        val shell: OfflineRootShell
        val installer: ToolsInstaller

        init {
            for (name in toolNames) executable(File(local, name), "#!/bin/sh\nexit 0\n# bundled $name fixture\n")
            for (name in listOf("mkdir", "cp", "chmod", "touch", "rm")) wrap(name)
            Files.createSymbolicLink(File(commands, "cmp").toPath(), File("/usr/bin/cmp").toPath())
            executable(File(commands, "chcon"), "#!/bin/sh\nexit 0\n")
            shell = OfflineRootShell(context, "$local:$commands:$xbin:$bin")
            installer = ToolsInstaller(context, shell, arrayOf(xbin, bin), xbin, modules, pending, disable)
        }

        fun executable(file: File, content: String) {
            file.writeText(content)
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwx------"))
        }

        fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

        fun wrap(name: String, fault: String = "") {
            executable(File(commands, name), "#!/bin/sh\nprintf '%s\\n' '$name' >> ${quote(writeLog.path)}\n$fault\nexec /bin/$name \"${'$'}@\"\n")
        }

        fun copyTool(name: String, destination: File) {
            File(local, name).copyTo(File(destination, name))
            Files.setPosixFilePermissions(File(destination, name).toPath(), PosixFilePermissions.fromString("rwx------"))
        }

        fun installGlobalPair() {
            for (name in toolNames) copyTool(name, xbin)
        }

        fun writes(): String = if (writeLog.exists()) writeLog.readText() else ""

        fun clearWrites() {
            assertTrue(writeLog.delete())
        }

        fun snapshot(): Map<String, String> = Files.walk(root.toPath()).use { paths ->
            paths.filter { it.toFile() != writeLog }.map { path ->
                val content = when {
                    Files.isSymbolicLink(path) -> "link:${Files.readSymbolicLink(path)}"
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "directory"
                    else -> "file:${Files.readAllBytes(path).contentToString()}"
                }
                root.toPath().relativize(path).toString() to content
            }.toList().toMap()
        }

        fun assertBlockedAndUnchanged() {
            val before = snapshot()
            assertEquals(ToolsInstaller.BLOCKED, installer.areInstalled())
            assertEquals(ToolsInstaller.BLOCKED, installer.install())
            assertEquals(before, snapshot())
            assertEquals("", writes())
        }

        fun assertStagedTools() {
            val metadata = File(module, "module.prop").readText()
            assertTrue(metadata.startsWith("id=vpn0wn\n"))
            assertTrue(metadata.contains("author=vpn0wn\n"))
            assertTrue(metadata.contains("WireGuard LLC"))
            assertEquals(0L, File(module, "auto_mount").length())
            for (name in toolNames) {
                val staged = File(module, "system/xbin/$name")
                assertArrayEquals(File(local, name).readBytes(), staged.readBytes())
                assertTrue(staged.canExecute())
                assertFalse(File(xbin, name).exists())
                assertFalse(File(bin, name).exists())
            }
            assertFalse(File(modules, "wireguard/system/xbin/wg").exists())
        }
    }

    private class OfflineRootShell(context: Context, private val path: String) : RootShell(context) {
        var failObservation = false
        var denyRoot = false
        var dropResponse = false
        var backendToolPath = ""
            private set

        override fun run(output: MutableCollection<String>?, command: String): Int {
            if (denyRoot) throw RootShellException(RootShellException.Reason.NO_ROOT_ACCESS)
            if (failObservation) throw IOException("synthetic observation failure")
            val builder = ProcessBuilder("/bin/sh", "-c", "$command; status=${'$'}?; command -v wg; exit \"${'$'}status\"")
                .redirectErrorStream(true)
            builder.environment()["PATH"] = path
            val process = builder.start()
            try {
                assertTrue("Offline shell timed out", process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val lines = process.inputStream.bufferedReader().readLines()
                backendToolPath = lines.last()
                output?.addAll(lines.dropLast(1))
                if (dropResponse) {
                    dropResponse = false
                    throw IOException("synthetic lost response")
                }
                return process.exitValue()
            } finally {
                process.destroyForcibly()
            }
        }
    }

    companion object {
        private const val SHELL_TIMEOUT_SECONDS = 10L
        private val toolNames = listOf("wg", "wg-quick")
    }
}
