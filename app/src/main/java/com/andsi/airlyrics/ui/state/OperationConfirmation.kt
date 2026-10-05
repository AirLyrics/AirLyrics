package com.andsi.airlyrics.ui.state

import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.model.MainUiHost

internal fun MainUiHost.confirmOperation(
    action: ConfirmationAction,
    title: String,
    message: String,
    positiveText: String
) {
    confirmations.request(action, title, message, positiveText)
    restoreOperationConfirmation()
}

internal fun MainUiHost.restoreOperationConfirmation() {
    val prompt = confirmations.pending ?: return
    if (activeConfirmationId == prompt.id) return
    activeConfirmationId = prompt.id
    showAirDialog(
        title = prompt.title, message = prompt.message,
        positiveText = prompt.positiveText, negativeText = getString(R.string.ui_cancel),
        onUserDismiss = {
            confirmations.cancel(prompt.id)
            if (activeConfirmationId == prompt.id) activeConfirmationId = null
        },
        onPositive = {
            if (activeConfirmationId == prompt.id) activeConfirmationId = null
            confirmations.confirm(prompt.id)
        }
    )
}
