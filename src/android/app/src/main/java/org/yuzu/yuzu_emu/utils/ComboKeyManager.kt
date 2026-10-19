// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.view.KeyEvent
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.features.input.model.NpadStyleIndex

/**
 * Combo keys: a physical controller key (trigger), optionally guarded by a modifier key,
 * that presses a custom set of Switch buttons at once.
 *
 * - Modifier unbound: pressing the trigger activates the combo immediately.
 * - Modifier bound: the modifier must be held first, then the trigger pressed.
 */
object ComboKeyManager {
    const val SLOT_COUNT = 5
    const val UNBOUND = 0

    private const val PREFS_NAME = "combo_key"

    data class Config(
        val enabled: Boolean = false,
        val modifierKey: Int = UNBOUND,
        val triggerKey: Int = UNBOUND,
        val outputs: Set<NativeButton> = emptySet()
    ) {
        val isUsable: Boolean
            get() = enabled && triggerKey != UNBOUND && outputs.isNotEmpty()
    }

    private class Slot(var config: Config) {
        var active = false
        var modifierSuppressed = false
        var triggerSuppressed = false
    }

    private val prefs by lazy {
        YuzuApplication.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val slots: List<Slot> by lazy { List(SLOT_COUNT) { Slot(load(it)) } }

    // Physical keys currently held, updated for every event including consumed ones
    private val held = mutableSetOf<Int>()

    // How many active combos hold each output button, so shared outputs release correctly
    private val outputCounts = IntArray(NativeButton.entries.size)
    private var activePort = 0

    fun getConfig(slot: Int): Config = slots[slot].config

    @Synchronized
    fun setConfig(slot: Int, config: Config) {
        deactivate(slots[slot])
        slots[slot].modifierSuppressed = false
        slots[slot].triggerSuppressed = false
        slots[slot].config = config
        save(slot, config)
    }

    private fun load(slot: Int): Config {
        val outputs = (prefs.getString("${slot}_outputs", "") ?: "")
            .split(",")
            .mapNotNull { it.toIntOrNull() }
            .map { NativeButton.from(it) }
            .toSet()
        return Config(
            prefs.getBoolean("${slot}_enabled", false),
            prefs.getInt("${slot}_modifier", UNBOUND),
            prefs.getInt("${slot}_trigger", UNBOUND),
            outputs
        )
    }

    private fun save(slot: Int, config: Config) {
        prefs.edit()
            .putBoolean("${slot}_enabled", config.enabled)
            .putInt("${slot}_modifier", config.modifierKey)
            .putInt("${slot}_trigger", config.triggerKey)
            .putString("${slot}_outputs", config.outputs.joinToString(",") { it.int.toString() })
            .apply()
    }

    fun keyName(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

    fun summary(slot: Int): String {
        val config = getConfig(slot)
        if (!config.isUsable) return ""
        val keys = if (config.modifierKey == UNBOUND) {
            keyName(config.triggerKey)
        } else {
            "${keyName(config.modifierKey)} + ${keyName(config.triggerKey)}"
        }
        return "$keys \u2192 ${config.outputs.sortedBy { it.int }.joinToString("+") { it.name }}"
    }

    /**
     * Feeds a physical key event through the combo logic.
     * @param releaseKey called to undo an already-forwarded press of the given key code.
     * @return true if the event was consumed and must not be forwarded to the game.
     */
    @Synchronized
    fun onKey(keyId: Int, pressed: Boolean, releaseKey: (Int) -> Unit): Boolean {
        if (pressed) held.add(keyId) else held.remove(keyId)

        val usable = slots.filter { it.config.isUsable }
        var consumed = false

        for (slot in usable) {
            val modifier = slot.config.modifierKey
            if (modifier == UNBOUND || keyId != modifier) continue
            if (pressed) {
                if (slot.modifierSuppressed) consumed = true
            } else {
                if (slot.active) deactivate(slot)
                if (slot.modifierSuppressed) {
                    slot.modifierSuppressed = false
                    consumed = true
                }
            }
        }

        val matches = usable.filter { it.config.triggerKey == keyId }
        if (pressed) {
            if (matches.any { it.triggerSuppressed }) return true
            val ready = matches.filter {
                it.config.modifierKey == UNBOUND || held.contains(it.config.modifierKey)
            }
            // A combo with a modifier is more specific than a bare trigger, so it wins
            val chosen = ready.firstOrNull { it.config.modifierKey != UNBOUND } ?: ready.firstOrNull()
            if (chosen != null) {
                chosen.triggerSuppressed = true
                activate(chosen)
                val modifier = chosen.config.modifierKey
                if (modifier != UNBOUND && !chosen.modifierSuppressed) {
                    releaseKey(modifier)
                    chosen.modifierSuppressed = true
                }
                consumed = true
            }
        } else {
            for (slot in matches) {
                if (!slot.triggerSuppressed) continue
                slot.triggerSuppressed = false
                deactivate(slot)
                consumed = true
            }
        }
        return consumed
    }

    private fun activate(slot: Slot) {
        if (slot.active) return
        if (slots.none { it.active }) {
            activePort = when (NativeInput.getStyleIndex(0)) {
                NpadStyleIndex.Handheld -> 8
                else -> 0
            }
        }
        slot.config.outputs.forEach {
            if (outputCounts[it.int]++ == 0) {
                NativeInput.onOverlayButtonEvent(activePort, it, NativeInput.ButtonState.PRESSED)
            }
        }
        slot.active = true
    }

    private fun deactivate(slot: Slot) {
        if (!slot.active) return
        slot.config.outputs.forEach {
            if (--outputCounts[it.int] == 0) {
                NativeInput.onOverlayButtonEvent(activePort, it, NativeInput.ButtonState.RELEASED)
            }
        }
        slot.active = false
    }

    /** Releases everything held by the combos, e.g. when emulation pauses. */
    @Synchronized
    fun reset() {
        slots.forEach {
            deactivate(it)
            it.modifierSuppressed = false
            it.triggerSuppressed = false
        }
        held.clear()
    }
}
