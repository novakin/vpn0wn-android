/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.provisioning

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal fun credentialFileForTest(
    file: File,
    key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
): VpsCredentialFile = VpsCredentialFile(
    file,
    { key },
    rename = { source, destination ->
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
    stat = { it.exists() },
)
