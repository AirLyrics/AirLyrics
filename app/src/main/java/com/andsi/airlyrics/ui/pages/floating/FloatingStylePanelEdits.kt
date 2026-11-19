package com.andsi.airlyrics.ui.pages.floating

import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.ui.components.SettingsSliderRow
import com.andsi.airlyrics.ui.components.SettingsUndoAction
import com.andsi.airlyrics.ui.components.SettingsUndoTarget
import com.andsi.airlyrics.ui.components.canAnimateFloatingControl
import com.andsi.airlyrics.ui.components.endFloatingPanelChanges
import com.andsi.airlyrics.ui.components.panelSwitch
import com.andsi.airlyrics.ui.model.FloatingFocusBubbleHandle
import com.andsi.airlyrics.ui.state.restoreFields
import com.andsi.airlyrics.ui.state.undoChangesTo

/** Keeps one saved change for the open panel and restores its existing controls in place. */
internal class FloatingStylePanelEdits(
    private val page: FloatingPageScope,
    private val restoreDefaults: (FloatingLyricsStyle, FloatingLyricsStyle) -> FloatingLyricsStyle
) {
    private val host = page.host
    private val interaction = host.interactions
    private var pendingUndo = interaction.read("panel")?.getBundle("undo")
    private var undoIsReset = interaction.read("panel")?.getBoolean("undoReset", pendingUndo != null) ?: false
    private var bubble: FloatingFocusBubbleHandle? = null
    private val controls = mutableListOf<Control>()

    private class Control(
        val target: (FloatingLyricsStyle, FloatingLyricsStyle) -> SettingsUndoTarget?,
        val capture: (FloatingLyricsStyle) -> (FloatingLyricsStyle, Boolean) -> Unit,
        val finishMotion: () -> Unit
    )

    fun bindBubble(handle: FloatingFocusBubbleHandle) {
        bubble = handle
        updateActions()
    }

    fun beginContent() {
        finishMotion()
        controls.clear()
    }

    fun finishMotion() {
        bubble?.finishUndoAnimation?.invoke()
        controls.forEach { it.finishMotion() }
    }

    fun <T> bindControl(
        read: (FloatingLyricsStyle) -> T,
        target: (T) -> SettingsUndoTarget,
        finishMotion: () -> Unit = {},
        capture: () -> (T, Boolean) -> Unit
    ) {
        controls.add(Control(
            target = { from, to ->
                val value = read(to)
                if (read(from) == value) null else target(value)
            },
            capture = { before ->
                val initial = read(before)
                val render = capture()
                val apply: (FloatingLyricsStyle, Boolean) -> Unit = { after, isUndo ->
                    val value = read(after)
                    if (initial != value) render(value, isUndo)
                }
                apply
            },
            finishMotion = finishMotion
        ))
    }

    fun <T> edit(write: () -> T): T {
        finishMotion()
        val before = page.style()
        val result = write()
        val after = page.style()
        remember(before, after, isReset = false)
        updateActions(after)
        return result
    }

    fun preview(transform: (FloatingLyricsStyle) -> FloatingLyricsStyle) {
        page.renderFloatingPreview(transform(page.style()))
    }

    fun reset() {
        val before = page.style()
        val defaults = defaultsFor(before)
        if (before == defaults) {
            updateActions(before)
            return
        }
        restore(before, defaults, isUndo = false)
    }

    fun undo() {
        val before = page.style()
        val restored = undoStyle(before) ?: return
        restore(before, restored, isUndo = true)
    }

    private fun restore(before: FloatingLyricsStyle, target: FloatingLyricsStyle, isUndo: Boolean) {
        finishMotion()
        bubble?.view?.endFloatingPanelChanges()
        // Capture the mounted controls before committing and render only fields that changed.
        val applyToControls = controls.map { it.capture(before) }
        if (isUndo) {
            clearUndo()
            bubble?.animateUndo?.invoke()
        }
        host.applyFloatingStyle(target)
        val after = page.style()
        if (!isUndo) remember(before, after, isReset = true)
        updateActions(after)
        applyToControls.forEach { it(after, isUndo) }
        page.refreshFloatingPreview()
    }

    fun updateActions(current: FloatingLyricsStyle = page.style()) {
        val handle = bubble ?: return
        val previous = undoStyle(current)
        val undo = previous?.let {
            val field = controls.mapNotNull { control -> control.target(current, previous) }.singleOrNull()
                ?.takeIf { pendingUndo?.keySet()?.count { key -> key != "customFontId" } == 1 }
            val description = when {
                field != null -> host.getString(R.string.ui_undo_setting_change, field.label, field.value)
                undoIsReset -> host.getString(R.string.ui_undo_reset_to_default)
                else -> host.getString(R.string.ui_undo_last_change)
            }
            SettingsUndoAction(description, field)
        }
        handle.updateActions(current != defaultsFor(current), undo)
    }

    private fun defaultsFor(current: FloatingLyricsStyle): FloatingLyricsStyle =
        restoreDefaults(current, host.floatingStyleDefaults(current.presetName))

    private fun remember(before: FloatingLyricsStyle, after: FloatingLyricsStyle, isReset: Boolean) {
        val snapshot = before.undoChangesTo(after)
        if (snapshot.isEmpty) return
        pendingUndo = snapshot
        undoIsReset = isReset
        interaction.write("panel") {
            putBundle("undo", snapshot)
            putBoolean("undoReset", isReset)
        }
    }

    private fun clearUndo() {
        pendingUndo = null
        undoIsReset = false
        interaction.write("panel") {
            remove("undo")
            remove("undoReset")
        }
    }

    private fun undoStyle(current: FloatingLyricsStyle): FloatingLyricsStyle? {
        val snapshot = pendingUndo ?: return null
        val previous = current.restoreFields(snapshot)
        if (previous == current || (previous.fontId != current.fontId &&
                host.floatingFontOperations.fonts().none { it.id == previous.fontId && it.available })) {
            clearUndo()
            return null
        }
        return previous
    }

    fun slider(
        title: String,
        read: (FloatingLyricsStyle) -> Int,
        min: Int,
        max: Int,
        suffix: String,
        step: Int = 1,
        compact: Boolean = false,
        write: (FloatingLyricsStyle) -> Unit = host::applyFloatingStyle,
        preview: (FloatingLyricsStyle) -> Unit = page::renderFloatingPreview,
        refreshPreview: () -> Unit = page::refreshFloatingPreview,
        withValue: (FloatingLyricsStyle, Int) -> FloatingLyricsStyle
    ): SettingsSliderRow {
        fun format(value: Int) = String.format(host.resources.configuration.locales[0], "%d%s", value, suffix)
        val value = read(page.style())
        val row = SettingsSliderRow(host, title, format(value), compact)
        row.bindRange(
            value, min, max, step, ::format,
            onPreview = { preview(withValue(page.style(), it)) },
            onCommit = { next ->
                val current = page.style()
                // Legacy alpha and size values can round to the same position on this slider.
                if (next != read(current)) edit { write(withValue(current, next)) }
                refreshPreview()
            },
            onCancel = refreshPreview
        )
        bindControl(read, { SettingsUndoTarget(title, format(it)) }, row::finishRangeAnimation) {
            val apply = row.captureRestore()
            val render: (Int, Boolean) -> Unit = { target, _ -> apply(target) }
            render
        }
        return row
    }

    fun toggle(
        title: String,
        read: (FloatingLyricsStyle) -> Boolean,
        write: (Boolean) -> Unit,
        showDependents: (Boolean, Boolean) -> Unit = { _, _ -> }
    ): androidx.appcompat.widget.SwitchCompat {
        var restoring = false
        val view = panelSwitch(host, title, read(page.style())) { checked ->
            if (!restoring) {
                edit { write(checked) }
                showDependents(checked, true)
                page.refreshFloatingPreview()
            }
        }
        bindControl(read, { SettingsUndoTarget(title, page.onOff(it)) }, view::jumpDrawablesToCurrentState) {
            val from = view.isChecked
            { checked, _ ->
                restoring = true
                if (view.isChecked != from) {
                    view.isChecked = from
                    view.jumpDrawablesToCurrentState()
                }
                view.isChecked = checked
                restoring = false
                if (!view.canAnimateFloatingControl()) view.jumpDrawablesToCurrentState()
                showDependents(checked, false)
            }
        }
        return view
    }
}
