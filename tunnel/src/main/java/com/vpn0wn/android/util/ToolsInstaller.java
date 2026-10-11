/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Modified for vpn0wn.
 */

package com.vpn0wn.android.util;

import android.content.Context;
import android.util.Log;

import com.vpn0wn.android.util.RootShell.RootShellException;
import com.vpn0wn.util.NonNullForAll;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;

/**
 * Helper to reuse compatible WireGuard tools or stage an app-owned Magisk module.
 */

@NonNullForAll
public final class ToolsInstaller {
    public static final int ERROR = 0x0;
    public static final int BLOCKED = 0x10;
    public static final int MAGISK = 0x4;
    public static final int NO = 0x2;
    public static final int SYSTEM = 0x8;
    public static final int YES = 0x1;
    private static final String[] EXECUTABLES = {"wg", "wg-quick"};
    private static final File[] INSTALL_DIRS = {
            new File("/system/xbin"),
            new File("/system/bin"),
    };
    @Nullable private static final File INSTALL_DIR = getInstallDir();
    private static final String TAG = "vpn0wn/ToolsInstaller";
    private static final String MODULE_METADATA = "id=vpn0wn\nname=vpn0wn WireGuard Command Line Tools\nversion=1.0\nversionCode=1\nauthor=vpn0wn\ndescription=WireGuard tools from WireGuard LLC, packaged by vpn0wn\nminMagisk=1500\n";

    private final Context context;
    private final File localBinaryDir;
    private final File[] installDirs;
    @Nullable private final File installDir;
    private final File modulesDir;
    private final File pendingModulesDir;
    private final File magiskDisableFile;
    private final Object lock = new Object();
    private final RootShell rootShell;
    @Nullable private Boolean areToolsAvailable;

    public ToolsInstaller(final Context context, final RootShell rootShell) {
        this(context, rootShell, INSTALL_DIRS, INSTALL_DIR, new File("/data/adb/modules"),
                new File("/data/adb/modules_update"), new File("/cache/.disable_magisk"));
    }

    ToolsInstaller(final Context context, final RootShell rootShell, final File[] installDirs,
                   @Nullable final File installDir, final File modulesDir, final File pendingModulesDir,
                   final File magiskDisableFile) {
        localBinaryDir = new File(context.getCodeCacheDir(), "bin");
        this.context = context;
        this.rootShell = rootShell;
        this.installDirs = installDirs.clone();
        this.installDir = installDir;
        this.modulesDir = modulesDir;
        this.pendingModulesDir = pendingModulesDir;
        this.magiskDisableFile = magiskDisableFile;
    }

    @Nullable
    private static File getInstallDir() {
        final String path = System.getenv("PATH");
        if (path == null)
            return INSTALL_DIRS[0];
        final List<String> paths = Arrays.asList(path.split(":"));
        for (final File dir : INSTALL_DIRS) {
            if (paths.contains(dir.getPath()) && dir.isDirectory())
                return dir;
        }
        return null;
    }

    public int areInstalled() throws RootShellException {
        try {
            extract();
        } catch (final IOException ignored) {
            return ERROR;
        }
        return run(probeScript() + "exit " + (NO | MAGISK) + "; )");
    }

    public void ensureToolsAvailable() throws FileNotFoundException {
        synchronized (lock) {
            if (areToolsAvailable == null) {
                try {
                    Log.d(TAG, extract() ? "Tools are now extracted into our private binary dir" :
                            "Tools were already extracted into our private binary dir");
                    areToolsAvailable = true;
                } catch (final IOException e) {
                    Log.e(TAG, "The wg and wg-quick tools are not available", e);
                    areToolsAvailable = false;
                }
            }
            if (!areToolsAvailable)
                throw new FileNotFoundException("Required tools unavailable");
        }
    }

    public boolean extract() throws IOException {
        synchronized (lock) {
            localBinaryDir.mkdirs();
            final File[] files = new File[EXECUTABLES.length];
            final File[] tempFiles = new File[EXECUTABLES.length];
            boolean allExist = true;
            for (int i = 0; i < files.length; ++i) {
                files[i] = new File(localBinaryDir, EXECUTABLES[i]);
                tempFiles[i] = new File(localBinaryDir, EXECUTABLES[i] + ".tmp");
                allExist &= files[i].exists();
            }
            if (allExist)
                return false;
            for (int i = 0; i < files.length; ++i) {
                if (!SharedLibraryLoader.extractLibrary(context, EXECUTABLES[i], tempFiles[i]))
                    throw new FileNotFoundException("Unable to find " + EXECUTABLES[i]);
                if (!tempFiles[i].setExecutable(true, false))
                    throw new IOException("Unable to mark " + tempFiles[i].getAbsolutePath() + " as executable");
                if (!tempFiles[i].renameTo(files[i]))
                    throw new IOException("Unable to rename " + tempFiles[i].getAbsolutePath() + " to " + files[i].getAbsolutePath());
            }
            return true;
        }
    }

    @RestrictTo(Scope.LIBRARY_GROUP)
    public int install() throws RootShellException, IOException {
        final String packageName = context.getPackageName();
        if (!"com.vpn0wn.android".equals(packageName) && !"com.vpn0wn.android.debug".equals(packageName))
            throw new SecurityException("The tools may only be installed system-wide from the main vpn0wn app.");
        extract();
        final StringBuilder script = new StringBuilder(probeScript());
        script.append("mkdir \"$module\" || exit ").append(BLOCKED).append("; ");
        script.append("trap 'rm -rf -- \"$module\"' EXIT; trap 'exit 1' INT TERM; ");
        script.append("mkdir -p \"$module_tools\" || exit 1; ");
        script.append("printf %s ").append(quote(MODULE_METADATA)).append(" > \"$module/module.prop\" || exit 1; ");
        script.append("touch \"$module/auto_mount\" || exit 1; ");
        for (final String name : EXECUTABLES) {
            script.append("cp ").append(quote(new File(localBinaryDir, name).getAbsolutePath()))
                    .append(" \"$module_tools/").append(name).append("\" || exit 1; ");
            script.append("chmod 755 \"$module_tools/").append(name).append("\" || exit 1; ");
            script.append("chcon 'u:object_r:system_file:s0' \"$module_tools/").append(name).append("\" || exit 1; ");
        }
        script.append("staged_matches || exit 1; trap - INT TERM EXIT; exit ").append(YES | MAGISK).append("; )");
        return run(script.toString());
    }

    private String probeScript() {
        final File moduleDir = new File(modulesDir, "vpn0wn");
        final String moduleTools = installDir == null ? "" : new File(moduleDir, "system/" + installDir.getName()).getAbsolutePath();
        final StringBuilder script = new StringBuilder("( ");
        script.append("module=").append(quote(moduleDir.getAbsolutePath())).append("; ");
        script.append("module_tools=").append(quote(moduleTools)).append("; ");
        script.append("exists() { [ -e \"$1\" ] || [ -L \"$1\" ]; }; ");
        script.append("only_entries() { directory=$1; shift; ")
                .append("for entry in \"$directory\"/* \"$directory\"/.[!.]* \"$directory\"/..?*; do ")
                .append("exists \"$entry\" || continue; allowed=0; ")
                .append("for name do [ \"$entry\" != \"$directory/$name\" ] || allowed=1; done; ")
                .append("[ \"$allowed\" = 1 ] || return 1; done; }; ");
        script.append("matches() { [ -f \"$1\" ] && [ ! -L \"$1\" ] && [ -x \"$1\" ] && cmp -s ")
                .append(quote(localBinaryDir.getAbsolutePath())).append("/\"$2\" \"$1\"; }; ");
        script.append("staged_matches() { [ -d \"$module\" ] && [ ! -L \"$module\" ] && ")
                .append("[ -d \"$module/system\" ] && [ ! -L \"$module/system\" ] && ")
                .append("[ -d \"$module_tools\" ] && [ ! -L \"$module_tools\" ] && ")
                .append("[ -f \"$module/module.prop\" ] && [ ! -L \"$module/module.prop\" ] && ")
                .append("[ -f \"$module/auto_mount\" ] && [ ! -L \"$module/auto_mount\" ] && [ ! -s \"$module/auto_mount\" ] && ")
                .append("! exists \"$module/disable\" && ! exists \"$module/remove\" && ! exists \"$module/update\" && ")
                .append("printf %s ").append(quote(MODULE_METADATA)).append(" | cmp -s - \"$module/module.prop\" && ")
                .append("matches \"$module_tools/wg\" wg && matches \"$module_tools/wg-quick\" wg-quick && ")
                .append("only_entries \"$module\" module.prop auto_mount system && ")
                .append("only_entries \"$module/system\" ").append(quote(installDir == null ? "" : installDir.getName())).append(" && ")
                .append("only_entries \"$module_tools\" wg wg-quick; }; ");
        // RootShell's private tools must remain first for backend commands, but cannot prove global installation.
        script.append("case \"$PATH\" in ").append(quote(localBinaryDir.getAbsolutePath() + ":"))
                .append("*) PATH=${PATH#*:};; esac; ");
        script.append("wg=$(command -v wg) || wg=''; quick=$(command -v wg-quick) || quick=''; reused=0; staged=0; ");
        script.append("if [ -n \"$wg\" ] || [ -n \"$quick\" ]; then ")
                .append("matches \"$wg\" wg && matches \"$quick\" wg-quick || exit ").append(BLOCKED)
                .append("; reused=1; fi; ");
        for (final File dir : installDirs) {
            final String directory = quote(dir.getAbsolutePath());
            script.append("if exists ").append(directory).append("; then [ -d ").append(directory)
                    .append(" ] && [ ! -L ").append(directory).append(" ] && [ -r ").append(directory)
                    .append(" ] && [ -x ").append(directory).append(" ] || exit ").append(BLOCKED).append("; fi; ");
            for (final String name : EXECUTABLES) {
                final String destination = quote(new File(dir, name).getAbsolutePath());
                script.append("if exists ").append(destination).append("; then [ \"$reused\" = 1 ] && matches ")
                        .append(destination).append(' ').append(name).append(" || exit ").append(BLOCKED).append("; fi; ");
            }
        }
        script.append("if exists \"$module\"; then staged_matches || exit ").append(BLOCKED).append("; staged=1; fi; ");
        script.append("! exists ").append(quote(new File(pendingModulesDir, "vpn0wn").getAbsolutePath()))
                .append(" || exit ").append(BLOCKED).append("; ");
        script.append("for root in ").append(quote(modulesDir.getAbsolutePath())).append(' ')
                .append(quote(pendingModulesDir.getAbsolutePath())).append("; do ")
                .append("if exists \"$root\"; then [ -d \"$root\" ] && [ ! -L \"$root\" ] && [ -r \"$root\" ] && [ -x \"$root\" ] || exit ")
                .append(BLOCKED).append("; ")
                .append("for overlay in \"$root\"/*; do exists \"$overlay\" || continue; ")
                .append("[ -d \"$overlay\" ] && [ ! -L \"$overlay\" ] && [ -r \"$overlay\" ] && [ -x \"$overlay\" ] || exit ")
                .append(BLOCKED).append("; ")
                .append("if exists \"$overlay/system\"; then [ -d \"$overlay/system\" ] && [ ! -L \"$overlay/system\" ] && ")
                .append("[ -r \"$overlay/system\" ] && [ -x \"$overlay/system\" ] && ")
                .append("! exists \"$overlay/system/.replace\" || exit ").append(BLOCKED).append("; fi; ");
        for (final File dir : installDirs) {
            script.append("if exists \"$overlay/system/").append(dir.getName()).append("\"; then ")
                    .append("[ -d \"$overlay/system/").append(dir.getName()).append("\" ] && ")
                    .append("[ ! -L \"$overlay/system/").append(dir.getName()).append("\" ] && ")
                    .append("[ -r \"$overlay/system/").append(dir.getName()).append("\" ] && ")
                    .append("[ -x \"$overlay/system/").append(dir.getName()).append("\" ] && ")
                    .append("! exists \"$overlay/system/").append(dir.getName()).append("/.replace\" || exit ").append(BLOCKED).append("; fi; ");
            for (final String name : EXECUTABLES) {
                script.append("candidate=\"$overlay/system/").append(dir.getName()).append('/').append(name).append("\"; ")
                        .append("if exists \"$candidate\"; then ")
                        .append("if [ \"$overlay\" = \"$module\" ] && [ \"$staged\" = 1 ]; then matches \"$candidate\" ")
                        .append(name).append(" || exit ").append(BLOCKED).append("; ")
                        .append("else [ \"$reused\" = 1 ] && matches \"$candidate\" ").append(name)
                        .append(" || exit ").append(BLOCKED).append("; fi; fi; ");
            }
        }
        script.append("done; fi; done; ");
        script.append("[ \"$reused\" = 0 ] || exit ").append(YES | SYSTEM).append("; ");
        script.append("[ -d ").append(quote(modulesDir.getAbsolutePath())).append(" ] && [ -w ")
                .append(quote(modulesDir.getAbsolutePath())).append(" ] && ! exists ")
                .append(quote(magiskDisableFile.getAbsolutePath())).append(" || exit ").append(BLOCKED).append("; ");
        if (installDir == null)
            script.append("exit ").append(BLOCKED).append("; ");
        script.append("[ \"$staged\" = 0 ] || exit ").append(YES | MAGISK).append("; ");
        return script.toString();
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private int run(final String script) throws RootShellException {
        try {
            final int result = rootShell.run(null, script);
            if (result == (YES | SYSTEM) || result == (YES | MAGISK) || result == (NO | MAGISK) || result == BLOCKED)
                return result;
            return ERROR;
        } catch (final IOException ignored) {
            return ERROR;
        } catch (final RootShellException e) {
            if (e.isIORelated())
                return ERROR;
            throw e;
        }
    }

}
