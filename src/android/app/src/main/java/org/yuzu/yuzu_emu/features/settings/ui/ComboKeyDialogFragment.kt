// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.settings.ui

import android.app.Dialog
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.utils.ComboKeyManager
import org.yuzu.yuzu_emu.utils.InputHandler

class ComboKeyDialogFragment : DialogFragment() {
    private val settingsViewModel: SettingsViewModel by activityViewModels()

    private var slot = 0
    private var enabled = false
    private var modifierKey = ComboKeyManager.UNBOUND
    private var triggerKey = ComboKeyManager.UNBOUND
    private var outputs = emptySet<NativeButton>()

    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var modifierButton: MaterialButton
    private lateinit var triggerButton: MaterialButton
    private lateinit var outputButton: MaterialButton

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        slot = requireArguments().getInt(ARG_SLOT)
        val saved = ComboKeyManager.getConfig(slot)
        enabled = savedInstanceState?.getBoolean(STATE_ENABLED) ?: saved.enabled
        modifierKey = savedInstanceState?.getInt(STATE_MODIFIER) ?: saved.modifierKey
        triggerKey = savedInstanceState?.getInt(STATE_TRIGGER) ?: saved.triggerKey
        outputs = savedInstanceState?.getIntArray(STATE_OUTPUTS)
            ?.map { NativeButton.from(it) }?.toSet() ?: saved.outputs

        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        enabledSwitch = MaterialSwitch(context).apply {
            text = getString(R.string.combo_key_enable)
            isChecked = enabled
            setOnCheckedChangeListener { _, checked -> enabled = checked }
        }
        root.addView(enabledSwitch)

        root.addView(
            TextView(context).apply {
                text = getString(R.string.combo_key_description)
                setPadding(0, pad / 2, 0, pad / 2)
            }
        )

        modifierButton = addRow(root) { captureKey(true) }
        triggerButton = addRow(root) { captureKey(false) }
        outputButton = addRow(root) { chooseOutputs() }

        // Modifier can be cleared, since an unbound modifier means the trigger works alone
        root.addView(
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.combo_key_clear_modifier)
                setOnClickListener {
                    modifierKey = ComboKeyManager.UNBOUND
                    refresh()
                }
            }
        )

        refresh()

        return MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.combo_key_slot, slot + 1))
            .setView(root)
            .setPositiveButton(R.string.combo_key_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                // Validate before closing, so the dialog stays open on bad input
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (save()) dismiss()
                    }
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ENABLED, enabled)
        outState.putInt(STATE_MODIFIER, modifierKey)
        outState.putInt(STATE_TRIGGER, triggerKey)
        outState.putIntArray(STATE_OUTPUTS, outputs.map { it.int }.toIntArray())
    }

    private fun addRow(root: LinearLayout, onClick: () -> Unit): MaterialButton {
        val button = MaterialButton(requireContext()).apply {
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        root.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return button
    }

    private fun refresh() {
        val none = getString(R.string.combo_key_none)
        modifierButton.text = getString(
            R.string.combo_key_modifier,
            if (modifierKey == ComboKeyManager.UNBOUND) none else ComboKeyManager.keyName(modifierKey)
        )
        triggerButton.text = getString(
            R.string.combo_key_trigger,
            if (triggerKey == ComboKeyManager.UNBOUND) none else ComboKeyManager.keyName(triggerKey)
        )
        outputButton.text = getString(
            R.string.combo_key_output,
            if (outputs.isEmpty()) none else outputs.sortedBy { it.int }.joinToString(" + ") { it.name }
        )
    }

    private fun chooseOutputs() {
        val all = NativeButton.entries
        val checked = BooleanArray(all.size) { outputs.contains(all[it]) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.combo_key_output_title)
            .setMultiChoiceItems(all.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                outputs = all.filterIndexed { i, _ -> checked[i] }.toSet()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun captureKey(isModifier: Boolean) {
        val title = if (isModifier) R.string.combo_key_modifier_title else R.string.combo_key_trigger_title
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(R.string.combo_key_press_button)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnKeyListener { _, _, event ->
                    val fromController =
                        event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                            event.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
                    if (!fromController || event.keyCode == KeyEvent.KEYCODE_BACK) {
                        return@setOnKeyListener false
                    }
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        val keyId = InputHandler.getButtonIdFromEvent(event)
                        if (keyId != 0) {
                            if (isModifier) modifierKey = keyId else triggerKey = keyId
                            refresh()
                            dialog.dismiss()
                        }
                    }
                    // Consume everything so A/Enter doesn't click the cancel button
                    true
                }
                dialog.show()
            }
    }

    private fun save(): Boolean {
        if (triggerKey == ComboKeyManager.UNBOUND || outputs.isEmpty()) {
            if (enabled) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.combo_key_incomplete)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return false
            }
        }
        if (modifierKey != ComboKeyManager.UNBOUND && modifierKey == triggerKey) {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.combo_key_same_key)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return false
        }
        ComboKeyManager.setConfig(
            slot,
            ComboKeyManager.Config(enabled, modifierKey, triggerKey, outputs)
        )
        settingsViewModel.setReloadListAndNotifyDataset(true)
        return true
    }

    companion object {
        const val TAG = "ComboKeyDialogFragment"
        private const val ARG_SLOT = "slot"

        fun newInstance(slot: Int) = ComboKeyDialogFragment().apply {
            arguments = Bundle().apply { putInt(ARG_SLOT, slot) }
        }

        private const val STATE_ENABLED = "enabled"
        private const val STATE_MODIFIER = "modifier"
        private const val STATE_TRIGGER = "trigger"
        private const val STATE_OUTPUTS = "outputs"
    }
}
