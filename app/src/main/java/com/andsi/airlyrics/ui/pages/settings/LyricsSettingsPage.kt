package com.andsi.airlyrics.ui.pages.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.liveOptionGrid
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint

internal fun createLyricsSettingsPage(activity: MainActivity): View  = with(activity) createLyricsSettingsPage@ {
    val container = pageContainer(activity, animateChanges = false)
    val selectedSource = LyricsSettingsStore.getLyricsSource(this)
    val autoSearch = LyricsSettingsStore.isAutoSearchOnlineEnabled(this)
    val autoSave = LyricsSettingsStore.isAutoSaveLocalEnabled(this)

    container.addView(settingsBackHeader("歌词获取设置", "本地歌词优先，当前音乐绑定导入，找不到时再按你的设置联网搜索。"))

    container.addView(createCurrentLyricsCard(activity))

    container.addView(
        card(activity) {
            addView(bigText(activity, "搜索策略"))
            addView(normalText(activity, "使用顺序固定为：手动导入歌词 > 本地缓存歌词 > 联网搜索歌词。"))

            val autoSearchButton = actionButton(activity, if (autoSearch) "无本地歌词时自动联网搜索：开启" else "无本地歌词时自动联网搜索：关闭") { }
            autoSearchButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSearch()
                autoSearchButton.text = if (enabled) "无本地歌词时自动联网搜索：开启" else "无本地歌词时自动联网搜索：关闭"
                Toast.makeText(activity, if (enabled) "已开启联网搜索" else "已关闭联网搜索", Toast.LENGTH_SHORT).show()
                playLocalRefreshFeedback(activity, autoSearchButton, null, "已更新")
            }
            addView(autoSearchButton)

            val autoSaveButton = actionButton(activity, if (autoSave) "自动保存联网歌词：开启" else "自动保存联网歌词：关闭") { }
            autoSaveButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSave()
                autoSaveButton.text = if (enabled) "自动保存联网歌词：开启" else "自动保存联网歌词：关闭"
                Toast.makeText(activity, if (enabled) "已开启自动保存" else "已关闭自动保存", Toast.LENGTH_SHORT).show()
                playLocalRefreshFeedback(activity, autoSaveButton, null, "已更新")
            }
            addView(autoSaveButton)
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, "联网搜索源"))
            val sourceStatus = normalText(activity, "当前：${LyricsSettingsStore.getLyricsSourceTitle(activity)}")
            val sourceFeedback = TextView(activity).apply {
                text = ""
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccentMint)
                setPadding(0, dp(4), 0, 0)
            }
            addView(sourceStatus)
            addView(sourceFeedback)
            lateinit var sourceGrid: LinearLayout
            sourceGrid = liveOptionGrid(
                LyricsSettingsStore.sourceOptions.map { option ->
                    KeyedOptionItem(
                        key = option.key,
                        title = option.title,
                        selected = option.key == selectedSource,
                        action = {
                            uiActions.selectLyricsSource(option.key)
                            sourceStatus.text = "当前：${option.title}"
                            Toast.makeText(activity, "歌词源已切换为：${option.title}", Toast.LENGTH_SHORT).show()
                            playLocalRefreshFeedback(activity, sourceGrid, sourceFeedback, "已保存")
                        }
                    )
                }
            )
            addView(sourceGrid)
            LyricsSettingsStore.sourceOptions.forEach { option ->
                addView(smallHint(activity, "${option.title}：${option.description}"))
            }
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, "本地歌词目录"))
            addView(normalText(activity, "保存目录：${LyricsStorage.getLyricsDirDisplayPath(activity)}"))
            addView(smallHint(activity, "手动导入歌词、自动保存歌词和歌词索引都会使用这个位置。"))
            addView(actionButton(activity, "选择歌词保存目录") {
                uiActions.selectLyricsDirectory()
            })
            addView(actionButton(activity, "复制歌词保存目录") {
                uiActions.copyLyricsDirectory()
            })
        }
    )

    container.addView(createRecentLyricsCard(activity))

    return scroll(activity, container, animateChildren = false)
}

private fun createCurrentLyricsCard(activity: MainActivity): View  = with(activity) createCurrentLyricsCard@ {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val feedback = TextView(this).apply {
        text = ""
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccentMint)
        gravity = Gravity.CENTER_VERTICAL
    }

    fun populate() {
        body.removeAllViews()
        val media = getCurrentMediaSnapshot()
        val localInfo = media?.let {
            LyricsStorage.getLocalLyricsInfo(
                context = this,
                title = it.title,
                artist = it.artist,
                duration = it.durationMs
            )
        }

        if (media == null) {
            body.addView(normalText(activity, "当前没有可识别的播放媒体。"))
            body.addView(smallHint(activity, "先播放音乐并在媒体流页面选择播放器，再回来导入或管理歌词。"))
            return
        }

        body.addView(normalText(activity, media.displayText))
        body.addView(settingRow(activity, "歌词来源", localInfo?.sourceText ?: "暂无本地歌词"))
        body.addView(settingRow(activity, "匹配文件", localInfo?.friendlyTitle ?: "未绑定"))

        body.addView(actionButton(activity, "为当前音乐导入歌词") {
            uiActions.importLyricsForCurrentMedia()
        })

        if (localInfo != null) {
            body.addView(actionButton(activity, if (localInfo.source == LyricsStorage.SOURCE_DOWNLOADED) "移除已下载歌词" else "移除本地歌词") {
                AlertDialog.Builder(activity)
                    .setTitle("移除当前音乐歌词？")
                    .setMessage("${media.displayText}\n\n会删除本地歌词文件和索引记录。之后如果允许联网搜索，这首歌可以重新查找歌词。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除") { _, _ ->
                        uiActions.deleteLyricsForCurrentMedia(media)
                    }
                    .show()
            })
        }

        body.addView(actionButton(activity, "重新联网搜索歌词") {
            AlertDialog.Builder(activity)
                .setTitle("重新联网搜索歌词？")
                .setMessage("会绕过当前本地歌词重新搜索。找到后会覆盖保存为新的本地缓存。")
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索") { _, _ ->
                    uiActions.reloadFloatingLyricsFromOnline()
                }
                .show()
        })
    }

    populate()

    return card(activity) {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bigText(activity, "当前音乐歌词").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            })
            addView(TextView(activity).apply {
                text = "↻"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                enableSoftPressFeedback(0.9f)
                setOnClickListener {
                    animate().rotationBy(360f).setDuration(420L).start()
                    populate()
                    playLocalRefreshFeedback(activity, target = body, feedback = feedback, message = "已刷新")
                }
            })
        })
        addView(body)
    }
}

private fun createRecentLyricsCard(activity: MainActivity): View  = with(activity) createRecentLyricsCard@ {
    val listBody = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val feedback = TextView(this).apply {
        text = ""
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccentMint)
        gravity = Gravity.CENTER_VERTICAL
    }

    fun populate() {
        listBody.removeAllViews()
        val recentLyrics = LyricsStorage.listRecentLyrics(this, limit = 8)
        if (recentLyrics.isEmpty()) {
            listBody.addView(normalText(activity, "还没有保存过歌词。播放歌曲并成功匹配，或为当前音乐导入歌词后，这里会出现 .lrc 文件。"))
        } else {
            recentLyrics.forEach { item ->
                listBody.addView(localLyricsRow(item))
            }
        }
    }

    populate()

    return card(activity) {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bigText(activity, "最近的本地歌词").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            })
            addView(TextView(activity).apply {
                text = "↻"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                enableSoftPressFeedback(0.9f)
                setOnClickListener {
                    animate().rotationBy(360f).setDuration(420L).start()
                    populate()
                    val count = LyricsStorage.listRecentLyrics(activity, limit = 8).size
                    playLocalRefreshFeedback(activity, listBody, feedback, if (count > 0) "已刷新 $count 首" else "已刷新")
                }
            })
        })
        addView(listBody)
    }
}

private fun playLocalRefreshFeedback(activity: MainActivity, target: View, feedback: TextView?, message: String) = with(activity) playLocalRefreshFeedback@ {
    feedback?.apply {
        text = message
        alpha = 0f
        animate().alpha(1f).setDuration(160L).withEndAction {
            postDelayed({ animate().alpha(0f).setDuration(240L).withEndAction { text = "" }.start() }, 900L)
        }.start()
    }

    target.alpha = 0.72f
    target.translationY = dp(3).toFloat()
    target.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(220L)
        .start()
}
