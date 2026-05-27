package com.andsi.airlyrics.ui.pages.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.andsi.airlyrics.LyricsStorage
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.MainActivity.KeyedOptionItem
import com.andsi.airlyrics.core.settings.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint

internal fun MainActivity.createLyricsSettingsPage(): View {
    val container = pageContainer(animateChanges = false)
    val selectedSource = LyricsSettingsStore.getLyricsSource(this)
    val autoSearch = LyricsSettingsStore.isAutoSearchOnlineEnabled(this)
    val autoSave = LyricsSettingsStore.isAutoSaveLocalEnabled(this)

    container.addView(settingsBackHeader("歌词获取设置", "本地歌词优先，当前音乐绑定导入，找不到时再按你的设置联网搜索。"))

    container.addView(createCurrentLyricsCard())

    container.addView(
        card {
            addView(bigText("搜索策略"))
            addView(normalText("使用顺序固定为：手动导入歌词 > 本地缓存歌词 > 联网搜索歌词。"))

            val autoSearchButton = actionButton(if (autoSearch) "无本地歌词时自动联网搜索：开启" else "无本地歌词时自动联网搜索：关闭") { }
            autoSearchButton.setOnClickListener {
                val enabled = !LyricsSettingsStore.isAutoSearchOnlineEnabled(this@createLyricsSettingsPage)
                LyricsSettingsStore.setAutoSearchOnlineEnabled(this@createLyricsSettingsPage, enabled)
                autoSearchButton.text = if (enabled) "无本地歌词时自动联网搜索：开启" else "无本地歌词时自动联网搜索：关闭"
                reloadFloatingLyrics()
                Toast.makeText(this@createLyricsSettingsPage, if (enabled) "已开启联网搜索" else "已关闭联网搜索", Toast.LENGTH_SHORT).show()
                playLocalRefreshFeedback(autoSearchButton, null, "已更新")
            }
            addView(autoSearchButton)

            val autoSaveButton = actionButton(if (autoSave) "自动保存联网歌词：开启" else "自动保存联网歌词：关闭") { }
            autoSaveButton.setOnClickListener {
                val enabled = !LyricsSettingsStore.isAutoSaveLocalEnabled(this@createLyricsSettingsPage)
                LyricsSettingsStore.setAutoSaveLocalEnabled(this@createLyricsSettingsPage, enabled)
                autoSaveButton.text = if (enabled) "自动保存联网歌词：开启" else "自动保存联网歌词：关闭"
                Toast.makeText(this@createLyricsSettingsPage, if (enabled) "已开启自动保存" else "已关闭自动保存", Toast.LENGTH_SHORT).show()
                playLocalRefreshFeedback(autoSaveButton, null, "已更新")
            }
            addView(autoSaveButton)
        }
    )

    container.addView(
        card {
            addView(bigText("联网搜索源"))
            val sourceStatus = normalText("当前：${LyricsSettingsStore.getLyricsSourceTitle(this@createLyricsSettingsPage)}")
            val sourceFeedback = TextView(this@createLyricsSettingsPage).apply {
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
                            LyricsSettingsStore.setLyricsSource(this@createLyricsSettingsPage, option.key)
                            sourceStatus.text = "当前：${option.title}"
                            reloadFloatingLyrics()
                            Toast.makeText(this@createLyricsSettingsPage, "歌词源已切换为：${option.title}", Toast.LENGTH_SHORT).show()
                            playLocalRefreshFeedback(sourceGrid, sourceFeedback, "已保存")
                        }
                    )
                }
            )
            addView(sourceGrid)
            LyricsSettingsStore.sourceOptions.forEach { option ->
                addView(smallHint("${option.title}：${option.description}"))
            }
        }
    )

    container.addView(
        card {
            addView(bigText("本地歌词目录"))
            addView(normalText("保存目录：${LyricsStorage.getLyricsDirDisplayPath(this@createLyricsSettingsPage)}"))
            addView(smallHint("手动导入歌词、自动保存歌词和歌词索引都会使用这个位置。"))
            addView(actionButton("选择歌词保存目录") {
                selectLyricsDirLauncher.launch(null)
            })
            addView(actionButton("复制歌词保存目录") {
                showLyricsDir()
            })
        }
    )

    container.addView(createRecentLyricsCard())

    return scroll(container, animateChildren = false)
}

private fun MainActivity.createCurrentLyricsCard(): View {
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
            body.addView(normalText("当前没有可识别的播放媒体。"))
            body.addView(smallHint("先播放音乐并在媒体流页面选择播放器，再回来导入或管理歌词。"))
            return
        }

        body.addView(normalText(media.displayText))
        body.addView(settingRow("歌词来源", localInfo?.sourceText ?: "暂无本地歌词"))
        body.addView(settingRow("匹配文件", localInfo?.friendlyTitle ?: "未绑定"))

        body.addView(actionButton("为当前音乐导入歌词") {
            importLyricsLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
        })

        if (localInfo != null) {
            body.addView(actionButton(if (localInfo.source == LyricsStorage.SOURCE_DOWNLOADED) "移除已下载歌词" else "移除本地歌词") {
                AlertDialog.Builder(this@createCurrentLyricsCard)
                    .setTitle("移除当前音乐歌词？")
                    .setMessage("${media.displayText}\n\n会删除本地歌词文件和索引记录。之后如果允许联网搜索，这首歌可以重新查找歌词。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除") { _, _ ->
                        deleteLyricsForCurrentMedia(media)
                    }
                    .show()
            })
        }

        body.addView(actionButton("重新联网搜索歌词") {
            AlertDialog.Builder(this@createCurrentLyricsCard)
                .setTitle("重新联网搜索歌词？")
                .setMessage("会绕过当前本地歌词重新搜索。找到后会覆盖保存为新的本地缓存。")
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索") { _, _ ->
                    reloadFloatingLyricsFromOnline()
                }
                .show()
        })
    }

    populate()

    return card {
        addView(LinearLayout(this@createCurrentLyricsCard).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bigText("当前音乐歌词").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            })
            addView(TextView(this@createCurrentLyricsCard).apply {
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
                    playLocalRefreshFeedback(target = body, feedback = feedback, message = "已刷新")
                }
            })
        })
        addView(body)
    }
}

private fun MainActivity.createRecentLyricsCard(): View {
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
            listBody.addView(normalText("还没有保存过歌词。播放歌曲并成功匹配，或为当前音乐导入歌词后，这里会出现 .lrc 文件。"))
        } else {
            recentLyrics.forEach { item ->
                listBody.addView(localLyricsRow(item))
            }
        }
    }

    populate()

    return card {
        addView(LinearLayout(this@createRecentLyricsCard).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bigText("最近的本地歌词").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            })
            addView(TextView(this@createRecentLyricsCard).apply {
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
                    val count = LyricsStorage.listRecentLyrics(this@createRecentLyricsCard, limit = 8).size
                    playLocalRefreshFeedback(listBody, feedback, if (count > 0) "已刷新 $count 首" else "已刷新")
                }
            })
        })
        addView(listBody)
    }
}

private fun MainActivity.playLocalRefreshFeedback(target: View, feedback: TextView?, message: String) {
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
