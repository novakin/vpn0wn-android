/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vpn0wn.android.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ProvisioningCommandOutputTest {
    @Test
    fun requireSuccessReturnsSuccessfulOutput() {
        val output = ProvisioningCommandOutput(exitCode = 0, stdout = "ok", stderr = "")

        assertSame(output, output.requireSuccess())
    }

    @Test
    fun requireSuccessOmitsStderrFromUserFacingFailure() {
        val output = ProvisioningCommandOutput(exitCode = 7, stdout = "stdout detail", stderr = "private stderr detail")

        val exception = assertThrows(ProvisioningException::class.java) {
            output.requireSuccess()
        }

        assertEquals("Remote command failed with exit code 7", exception.message)
        assertSame(output, exception.output)
    }

    @Test
    fun requireSuccessOmitsStdoutFromUserFacingFailure() {
        val output = ProvisioningCommandOutput(exitCode = 2, stdout = "private stdout detail", stderr = "")

        val exception = assertThrows(ProvisioningException::class.java) {
            output.requireSuccess()
        }

        assertEquals("Remote command failed with exit code 2", exception.message)
        assertSame(output, exception.output)
    }

    @Test
    fun requireSuccessOmitsDetailWhenOutputIsBlank() {
        val output = ProvisioningCommandOutput(exitCode = 1, stdout = "", stderr = "")

        val exception = assertThrows(ProvisioningException::class.java) {
            output.requireSuccess()
        }

        assertEquals("Remote command failed with exit code 1", exception.message)
        assertSame(output, exception.output)
    }

    @Test
    fun requireSuccessDoesNotExposeLongFailureDetail() {
        val detail = "x".repeat(220)
        val output = ProvisioningCommandOutput(exitCode = 1, stdout = "", stderr = detail)

        val exception = assertThrows(ProvisioningException::class.java) {
            output.requireSuccess()
        }

        assertEquals("Remote command failed with exit code 1", exception.message)
    }
}
