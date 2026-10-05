package com.andsi.airlyrics.ui.state

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showFullText
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.pages.settings.showLanguageDialog
import com.andsi.airlyrics.ui.pages.settings.showFullUpdateLogDialog
import java.util.UUID
import kotlinx.coroutines.launch

internal fun MainUiHost.rememberAuxiliaryDialog(kind: String, data: Bundle = Bundle()) {
    interactions.write("aux.$kind") { putAll(data) }
    interactions.write("aux.order") {
        val order = getStringArrayList("items") ?: arrayListOf()
        if (kind !in order) order.add(kind)
        putStringArrayList("items", order)
    }
}

internal fun MainUiHost.isAuxiliaryDialogShowing(kind: String): Boolean = auxiliaryDialogs[kind]?.isShowing == true

internal fun MainUiHost.forgetAuxiliaryDialog(kind: String) {
    auxiliaryDialogs.remove(kind)
    interactions.read("aux.$kind")?.getString("file")?.let { readerContent.discard(it) }
    interactions.removePrefix("aux.$kind")
    interactions.write("aux.order") {
        putStringArrayList("items", (getStringArrayList("items") ?: arrayListOf()).apply { remove(kind) })
    }
}

internal fun MainUiHost.rememberReader(kind: String, title: String?, text: String, allowScroll: Boolean = false) {
    if (interactions.read("aux.$kind") != null) return
    val id = UUID.randomUUID().toString()
    readerContent.save(id, text)
    rememberAuxiliaryDialog(kind, Bundle().apply {
        putString("file", id)
        putString("title", title)
        putBoolean("allowScroll", allowScroll)
    })
}

internal fun MainUiHost.showPersistentInfo(titleRes: Int, textRes: Int) {
    if (isAuxiliaryDialogShowing("info")) return
    rememberAuxiliaryDialog("info", Bundle().apply {
        putInt("titleRes", titleRes)
        putInt("textRes", textRes)
    })
    auxiliaryDialogs["info"] = showAirDialog(title = getString(titleRes), message = getString(textRes), scrollStateKey = "aux.info.scroll",
        onUserDismiss = { forgetAuxiliaryDialog("info") })
}

internal fun MainUiHost.restoreAuxiliaryDialogs() {
    val order = interactions.read("aux.order")?.getStringArrayList("items")?.toList().orEmpty()
    auxiliaryRestoreJob?.cancel()
    auxiliaryRestoreJob = activity.lifecycleScope.launch {
        for (kind in order) {
            val saved = interactions.read("aux.$kind") ?: continue
            when (kind) {
                "language" -> showLanguageDialog(this@restoreAuxiliaryDialogs)
                "changelog" -> showFullUpdateLogDialog()
                "info" -> showPersistentInfo(saved.getInt("titleRes"), saved.getInt("textRes"))
                "reader" -> {
                    val file = saved.getString("file") ?: continue
                    val text = readerContent.load(file)
                    if (interactions.read("aux.$kind")?.getString("file") != file) continue
                    if (text == null) {
                        forgetAuxiliaryDialog(kind)
                        showMessage(R.string.ui_read_failed)
                    } else activity.withStarted {
                        if (interactions.read("aux.$kind")?.getString("file") == file) {
                            showFullText(saved.getString("title"), text, saved.getBoolean("allowScroll"))
                        }
                    }
                }
            }
        }
    }
}
