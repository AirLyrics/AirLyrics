package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.i18n.tr
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.liveOptionGrid
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.model.LyricsSearchSource
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun createLyricsSettingsPage(activity: MainActivity): View  = with(activity) createLyricsSettingsPage@ {
    val container = pageContainer(activity, animateChanges = false)
    val selectedSource = LyricsSettingsStore.getLyricsSource(this)
    val autoSearch = LyricsSettingsStore.isAutoSearchOnlineEnabled(this)
    val autoSave = LyricsSettingsStore.isAutoSaveLocalEnabled(this)

    container.addView(settingsBackHeader("歌词获取设置"))

    container.addView(createCurrentLyricsCard(activity))

    container.addView(
        card(activity) {
            addView(bigText(activity, "搜索策略"))
            addView(normalText(activity, "使用顺序固定为：手动导入歌词 > 本地缓存歌词 > 联网搜索歌词。"))

            val autoSearchButton = actionButton(activity, if (autoSearch) "无本地歌词时自动联网搜索：开启" else "无本地歌词时自动联网搜索：关闭") { }
            autoSearchButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSearch()
                autoSearchButton.text = if (enabled) localizeText("无本地歌词时自动联网搜索：开启") else localizeText("无本地歌词时自动联网搜索：关闭")
                playLocalRefreshFeedback(activity, autoSearchButton, null, "已更新")
            }
            addView(autoSearchButton)

            val autoSaveButton = actionButton(activity, if (autoSave) "自动保存联网歌词：开启" else "自动保存联网歌词：关闭") { }
            autoSaveButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSave()
                autoSaveButton.text = if (enabled) localizeText("自动保存联网歌词：开启") else localizeText("自动保存联网歌词：关闭")
                playLocalRefreshFeedback(activity, autoSaveButton, null, "已更新")
            }
            addView(autoSaveButton)
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, "歌词搜索来源"))
            fun sourceHintText(key: String): String = when (LyricsSearchSource.fromKey(key)) {
                LyricsSearchSource.LOCAL_ONLY -> tr("只读取本地歌词", "Read local lyrics only")
                LyricsSearchSource.NETEASE -> tr("适合中国用户", "Good for Chinese songs")
                LyricsSearchSource.MUSIXMATCH -> tr("适合国际用户，依据您的系统语言来自动获取翻译（如果有的话）", "Good for international songs; uses your system language for translations when available")
            }

            val sourceStatus = normalText(activity, "当前：${LyricsSettingsStore.getLyricsSourceTitle(activity)}")
            val sourceHint = smallHint(activity, sourceHintText(selectedSource))
            val sourceFeedback = TextView(activity).apply {
                text = ""
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccentMint)
                setPadding(0, dp(4), 0, 0)
            }
            addView(sourceStatus)
            addView(sourceHint)
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
                            sourceStatus.text = tr("当前：", "Current: ") + localizeText(option.title)
                            sourceHint.text = localizeText(sourceHintText(option.key))
                            playLocalRefreshFeedback(activity, sourceGrid, sourceFeedback, "已保存")
                        }
                    )
                }
            )
            addView(sourceGrid)
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


private fun karaokeStatusRow(activity: MainActivity, value: String): View = with(activity) karaokeStatusRow@ {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))

        addView(TextView(activity).apply {
            text = localizeText("本地逐字歌词")
            textSize = 15f
            setTextColor(colorTextStrong)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        addView(TextView(activity).apply {
            text = localizeText(value)
            textSize = 13f
            setTextColor(colorTextMuted)
            gravity = Gravity.CENTER_VERTICAL
        })

        addView(TextView(activity).apply {
            text = "!"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colorTextMuted)
            setPadding(0, 0, 0, dp(1))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSurfaceLight)
                setStroke(dp(1), colorStroke)
            }
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                setMargins(dp(8), 0, 0, 0)
            }
            enableSoftPressFeedback(0.9f)
            setOnClickListener {
                activity.showAirInfoDialog(
                    title = tr("本地逐字歌词", "Local word-by-word"),
                    message = tr("逐字歌词只支持手动导入本地 enhanced LRC。", "Word-by-word lyrics only support local enhanced LRC imports.")
                )
            }
        })
    }
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

        val localWordByWord = LyricsStorage.hasKaraokeLyrics(
            context = activity,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs
        )
        val karaokeEnabled = LyricsSettingsStore.isKaraokeLyricsEnabled(activity)
        val karaokeSummary = when {
            localWordByWord && karaokeEnabled -> "可用 · 本地逐字"
            localWordByWord -> "已导入 · 未开启"
            else -> "未导入"
        }
        val offsetMs = LyricsOffsetStore.getOffsetMs(activity, media)

        body.addView(normalText(activity, media.displayText))
        body.addView(settingRow(activity, "歌词来源", localInfo?.sourceText ?: "暂无普通本地歌词"))
        body.addView(settingRow(activity, "普通歌词", localInfo?.friendlyTitle ?: "未绑定"))
        body.addView(karaokeStatusRow(activity, karaokeSummary))
        body.addView(settingRow(activity, "当前偏移", LyricsOffsetStore.description(offsetMs)))
        if (offsetMs != 0L) {
            body.addView(smallHint(activity, "歌词偏移按当前音乐保存，不会修改原始 LRC 或 enhanced LRC 文件。"))
        }

        body.addView(actionButton(activity, "为当前音乐导入歌词") {
            uiActions.importLyricsForCurrentMedia()
        })

        fun confirmDeleteLyrics(label: String, mode: LyricsStorage.DeleteMode, message: String) {
            activity.showAirConfirmDialog(
                title = label,
                message = "${media.displayText}\n\n$message",
                positiveText = tr("移除", "Remove")
            ) {
                uiActions.deleteLyricsForCurrentMedia(media, mode)
            }
        }

        if (localInfo != null) {
            val plainLabel = if (localInfo.source == LyricsStorage.SOURCE_DOWNLOADED) "移除已下载普通歌词" else "移除普通歌词"
            body.addView(actionButton(activity, plainLabel) {
                confirmDeleteLyrics(
                    label = "移除普通歌词？",
                    mode = LyricsStorage.DeleteMode.PLAIN,
                    message = "只会删除这首歌关联的普通 LRC；如果已经导入逐字歌词，会继续保留。之后如果允许联网搜索，可以重新查找普通歌词。"
                )
            })
        }

        if (localWordByWord) {
            body.addView(actionButton(activity, "移除逐字歌词") {
                confirmDeleteLyrics(
                    label = "移除逐字歌词？",
                    mode = LyricsStorage.DeleteMode.KARAOKE,
                    message = "只会删除这首歌关联的本地 enhanced LRC 逐字数据；普通歌词会继续保留。"
                )
            })
        }

        if (localInfo != null && localWordByWord) {
            body.addView(actionButton(activity, "移除全部本地歌词") {
                confirmDeleteLyrics(
                    label = "移除全部本地歌词？",
                    mode = LyricsStorage.DeleteMode.ALL,
                    message = "会同时删除这首歌的普通歌词和逐字歌词。这个操作更彻底，适合想重新绑定歌词时使用。"
                )
            })
        }

        body.addView(actionButton(activity, "重新联网搜索歌词") {
            activity.showAirConfirmDialog(
                title = "重新联网搜索歌词？",
                message = "会绕过当前本地普通歌词重新搜索。找到后会覆盖保存为新的普通歌词缓存；逐字歌词只使用本地导入文件。",
                positiveText = tr("搜索", "Search")
            ) {
                uiActions.reloadFloatingLyricsFromOnline()
            }
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
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })

            val hintText = TextView(activity).apply {
                text = localizeText("点击歌词可以预览或者修改")
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMuted)
                alpha = 0f
                visibility = View.GONE
                setPadding(dp(8), 0, 0, 0)
            }

            addView(TextView(activity).apply {
                text = "!"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(colorTextMuted)
                setPadding(0, 0, 0, dp(1))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorSurfaceLight)
                    setStroke(dp(1), colorStroke)
                }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    setMargins(dp(8), 0, 0, 0)
                }
                enableSoftPressFeedback(0.9f)
                setOnClickListener {
                    if (hintText.visibility == View.VISIBLE) {
                        hintText.animate().alpha(0f).setDuration(120L).withEndAction {
                            hintText.visibility = View.GONE
                        }.start()
                    } else {
                        hintText.visibility = View.VISIBLE
                        hintText.alpha = 0f
                        hintText.animate().alpha(1f).setDuration(160L).start()
                    }
                }
            })
            addView(hintText)
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
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
        text = localizeText(message)
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
