/* SPDX-License-Identifier: Apache-2.0 */
package com.vpn0wn.android.model

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vpn0wn.android.Application
import com.vpn0wn.android.backend.Tunnel
import com.vpn0wn.android.util.UserKnobs
import org.json.JSONObject
import java.io.IOException

internal data class NetworkAutomationState(
    val enabled: Boolean = false,
    val episode: Long = 0,
    val defaultName: String? = null,
    val resolved: Boolean = false,
    val revision: Long = 0,
    val frontier: Long = 0,
    val selection: Long = 0,
    val serial: Long = 0,
    val manual: Receipt? = null,
    val attempt: Receipt? = null,
    val paused: Boolean = false,
    val stop: StopStamp? = null,
    val acknowledgedStop: StopStamp? = null,
    val exit: Exit = Exit.NONE,
) {
    enum class Exit { NONE, PENDING, COMPLETE, FAILED, SUPERSEDED }
    enum class Outcome { PENDING, UP, DOWN, NO_TRANSPORT, FAILED, UNRESOLVED }
    data class Receipt(
        val name: String,
        val requested: String,
        val absolute: String?,
        val revision: Long,
        val frontier: Long,
        val selection: Long,
        val serial: Long,
        val outcome: Outcome = Outcome.PENDING,
    )
    data class StopStamp(val process: String, val pid: Int, val timestamp: Long, val reason: Int)

    fun fence(names: Set<String>): NetworkAutomationState {
        if (!enabled || names.none { it == defaultName || it == manual?.name || it == attempt?.name }) return this
        return copy(
            resolved = if (defaultName in names) false else resolved,
            revision = revision + 1,
            manual = manual?.let { if (it.name in names) it.copy(outcome = Outcome.UNRESOLVED) else it },
            attempt = attempt?.let { if (it.name in names) it.copy(outcome = Outcome.UNRESOLVED) else it },
        )
    }

    fun suppressesRestore(name: String): Boolean {
        if (!enabled) return false
        if (paused || !resolved || name != defaultName) return true
        val admitted = manual
        if (admitted != null && (admitted.name != name || admitted.outcome != Outcome.UP)) return true
        return attempt?.let { it.name == name && it.outcome != Outcome.UP } == true
    }

    fun equivalentAttempt(name: String): Boolean = attempt?.let {
        it.outcome in setOf(Outcome.PENDING, Outcome.FAILED, Outcome.UNRESOLVED) && it.name == name && it.revision == revision &&
            (it.requested == "UP" || it.selection == selection)
    } == true

    companion object {
        private const val VERSION = 1
        private const val MAX_RECORD_LENGTH = 8192
        private const val MAX_PROCESS_NAME_LENGTH = 255
        private val KEY = stringPreferencesKey("network_automation_v1")

        fun read(preferences: Preferences): NetworkAutomationState = preferences[KEY]?.let(::decode) ?: NetworkAutomationState()

        suspend fun update(change: (NetworkAutomationState) -> NetworkAutomationState): NetworkAutomationState {
            var result: NetworkAutomationState? = null
            Application.getPreferencesDataStore().edit { preferences ->
                result = change(read(preferences))
                preferences[KEY] = encode(requireNotNull(result))
            }
            return requireNotNull(result)
        }

        suspend fun disable(liveReadyNames: Set<String>): NetworkAutomationState {
            var result: NetworkAutomationState? = null
            Application.getPreferencesDataStore().edit { preferences ->
                val state = read(preferences)
                val receipts = listOfNotNull(state.manual, state.attempt)
                val affected = receipts.map { it.name }.toSet() + setOfNotNull(state.defaultName.takeUnless { state.resolved })
                val retained = affected.filterTo(mutableSetOf()) { name ->
                    val latest = receipts.filter { it.name == name }.maxByOrNull { it.serial }
                    name in liveReadyNames && (name != state.defaultName || state.resolved) &&
                        latest?.outcome == Outcome.UP && latest.absolute == "UP"
                }
                UserKnobs.setRunningTunnels(preferences, preferences[UserKnobs.RUNNING_TUNNELS].orEmpty() - (affected - retained))
                result = state.copy(enabled = false, manual = null, attempt = null, exit = Exit.PENDING)
                preferences[KEY] = encode(requireNotNull(result))
            }
            return requireNotNull(result)
        }

        internal fun encode(state: NetworkAutomationState): String = JSONObject().apply {
            put("version", VERSION)
            put("enabled", state.enabled)
            put("episode", state.episode)
            put("default", state.defaultName)
            put("resolved", state.resolved)
            put("revision", state.revision)
            put("frontier", state.frontier)
            put("selection", state.selection)
            put("serial", state.serial)
            put("manual", state.manual?.json())
            put("attempt", state.attempt?.json())
            put("paused", state.paused)
            put("stop", state.stop?.json())
            put("acknowledgedStop", state.acknowledgedStop?.json())
            put("exit", state.exit.name)
        }.toString()

        internal fun decode(value: String): NetworkAutomationState {
            return try {
                require(value.length <= MAX_RECORD_LENGTH)
                val json = JSONObject(value)
                require(json.number("version") == VERSION.toLong())
                NetworkAutomationState(
                    enabled = json.boolean("enabled"), episode = json.number("episode"),
                    defaultName = json.optionalName("default"), resolved = json.boolean("resolved"),
                    revision = json.number("revision"), frontier = json.number("frontier"),
                    selection = json.number("selection"), serial = json.number("serial"), manual = json.optionalObject("manual")?.receipt(),
                    attempt = json.optionalObject("attempt")?.receipt(), paused = json.boolean("paused"),
                    stop = json.optionalObject("stop")?.stop(), acknowledgedStop = json.optionalObject("acknowledgedStop")?.stop(),
                    exit = Exit.valueOf(json.text("exit")),
                ).also { require(it.episode >= 0 && it.revision >= 0 && it.frontier >= 0 && it.selection >= 0 && it.serial >= 0) }
            } catch (failure: org.json.JSONException) {
                throw IOException("Invalid network automation record", failure)
            } catch (failure: IllegalArgumentException) {
                throw IOException("Invalid network automation record", failure)
            }
        }

        private fun JSONObject.optionalObject(key: String): JSONObject? = if (isNull(key)) null else getJSONObject(key)

        private fun JSONObject.text(key: String): String = (get(key) as? String) ?: throw IllegalArgumentException("Expected string")

        private fun JSONObject.boolean(key: String): Boolean = (get(key) as? Boolean) ?: throw IllegalArgumentException("Expected boolean")

        private fun JSONObject.number(key: String): Long = get(key).let {
            require(it is Int || it is Long)
            (it as Number).toLong()
        }

        private fun JSONObject.integer(key: String): Int = number(key).also {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        }.toInt()

        private fun JSONObject.optionalName(key: String): String? = if (isNull(key)) null else text(key).also {
            require(!Tunnel.isNameInvalid(it))
        }

        private fun Receipt.json() = JSONObject().apply {
            put("name", name)
            put("requested", requested)
            put("absolute", absolute)
            put("revision", revision)
            put("frontier", frontier)
            put("selection", selection)
            put("serial", serial)
            put("outcome", outcome.name)
        }

        private fun JSONObject.receipt() = Receipt(
            requireNotNull(optionalName("name")), text("requested"), if (isNull("absolute")) null else text("absolute"),
            number("revision"), number("frontier"), number("selection"), number("serial"), Outcome.valueOf(text("outcome")),
        ).also {
            require(it.requested in setOf("UP", "DOWN", "TOGGLE", "TRANSPORT"))
            require(it.absolute == null || it.absolute in setOf("UP", "DOWN"))
            require(it.revision >= 0 && it.frontier >= 0 && it.selection >= 0 && it.serial >= 0)
        }

        private fun StopStamp.json() = JSONObject().apply {
            put("process", process)
            put("pid", pid)
            put("timestamp", timestamp)
            put("reason", reason)
        }

        private fun JSONObject.stop() = StopStamp(text("process"), integer("pid"), number("timestamp"), integer("reason")).also {
            require(it.process.length <= MAX_PROCESS_NAME_LENGTH && it.pid > 0 && it.timestamp > 0)
        }
    }
}
