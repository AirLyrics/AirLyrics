package com.andsi.airlyrics.floating

import android.content.Context
import android.content.Intent
import android.content.IntentFilter

internal object FloatingWindowStateBroadcast {
    private const val ACTION_QUICK_CONTROL_CHANGED = "com.andsi.airlyrics.QUICK_CONTROL_CHANGED"
    private const val ACTION_WINDOW_VISIBILITY_CHANGED = "com.andsi.airlyrics.WINDOW_VISIBILITY_CHANGED"

    private const val EXTRA_WINDOW_VISIBLE = "windowVisible"
    private const val EXTRA_LOCKED = "locked"
    private const val EXTRA_CLICK_THROUGH = "clickThrough"

    data class State(
        val visible: Boolean,
        val locked: Boolean,
        val clickThrough: Boolean
    )

    fun windowStateFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(ACTION_WINDOW_VISIBILITY_CHANGED)
            addAction(ACTION_QUICK_CONTROL_CHANGED)
        }
    }

    fun windowVisibilityChangedIntent(context: Context, state: State): Intent {
        return stateIntent(context, ACTION_WINDOW_VISIBILITY_CHANGED, state)
    }

    fun quickControlChangedIntent(context: Context, state: State): Intent {
        return stateIntent(context, ACTION_QUICK_CONTROL_CHANGED, state)
    }

    fun readState(intent: Intent?): State? {
        val action = intent?.action ?: return null
        if (action != ACTION_WINDOW_VISIBILITY_CHANGED &&
            action != ACTION_QUICK_CONTROL_CHANGED
        ) {
            return null
        }
        if (!intent.hasExtra(EXTRA_WINDOW_VISIBLE) ||
            !intent.hasExtra(EXTRA_LOCKED) ||
            !intent.hasExtra(EXTRA_CLICK_THROUGH)
        ) return null

        return State(
            visible = intent.getBooleanExtra(EXTRA_WINDOW_VISIBLE, false),
            locked = intent.getBooleanExtra(EXTRA_LOCKED, false),
            clickThrough = intent.getBooleanExtra(EXTRA_CLICK_THROUGH, false)
        )
    }

    private fun stateIntent(
        context: Context,
        action: String,
        state: State
    ): Intent {
        return Intent(action).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_WINDOW_VISIBLE, state.visible)
            putExtra(EXTRA_LOCKED, state.locked)
            putExtra(EXTRA_CLICK_THROUGH, state.clickThrough)
        }
    }
}
