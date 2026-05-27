package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import android.widget.Toast
import com.andsi.airlyrics.LyricsStorage
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.MainActivity.KeyedOptionItem
import com.andsi.airlyrics.core.settings.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.*

internal fun MainActivity.createLyricsSettingsPage(): View {
    val container = pageContainer()
    val selectedSource = LyricsSettingsStore.getLyricsSource(this)
    val autoSave = LyricsSettingsStore.isAutoSaveLocalEnabled(this)
    val recentLyrics = LyricsStorage.listRecentLyrics(this, limit = 8)

    container.addView(settingsBackHeader("歌词获取设置", "决定歌词从哪里来，也决定找到后要不要收进本地小仓库。"))

    container.addView(
        card {
            addView(bigText("歌词源"))
            addView(normalText("当前：${LyricsSettingsStore.getLyricsSourceTitle(this@createLyricsSettingsPage)}"))
            addView(liveOptionGrid(
                LyricsSettingsStore.sourceOptions.map { option ->
                    KeyedOptionItem(
                        key = option.key,
                        title = option.title,
                        selected = option.key == selectedSource,
                        action = {
                            LyricsSettingsStore.setLyricsSource(this@createLyricsSettingsPage, option.key)
                            reloadFloatingLyrics()
                            Toast.makeText(this@createLyricsSettingsPage, "歌词源已切换为：${option.title}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            ))
            LyricsSettingsStore.sourceOptions.forEach { option ->
                addView(smallHint("${option.title}：${option.description}"))
            }
        }
    )

    container.addView(
        card {
            addView(bigText("自动下载到本地"))
            addView(normalText(if (autoSave) "开启后，联网找到歌词会自动保存成 .lrc，下次优先读取本地文件。" else "关闭后，联网找到歌词只用于本次显示，不写入本地目录。"))
            val autoSaveButton = actionButton(if (autoSave) "自动保存：开启" else "自动保存：关闭") { }
            autoSaveButton.setOnClickListener {
                val enabled = !LyricsSettingsStore.isAutoSaveLocalEnabled(this@createLyricsSettingsPage)
                LyricsSettingsStore.setAutoSaveLocalEnabled(this@createLyricsSettingsPage, enabled)
                autoSaveButton.text = if (enabled) "自动保存：开启" else "自动保存：关闭"
                Toast.makeText(this@createLyricsSettingsPage, if (enabled) "已开启自动保存" else "已关闭自动保存", Toast.LENGTH_SHORT).show()
            }
            addView(autoSaveButton)
        }
    )

    container.addView(
        card {
            addView(bigText("歌词文件夹"))
            addView(normalText("保存目录：${LyricsStorage.getLyricsDirDisplayPath(this@createLyricsSettingsPage)}"))
            addView(actionButton("选择歌词保存目录") {
                selectLyricsDirLauncher.launch(null)
            })
            addView(actionButton("导入本地歌词") {
                importLyricsLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            })
            addView(actionButton("复制歌词保存目录") {
                showLyricsDir()
            })
        }
    )

    container.addView(
        card {
            addView(bigText("最近下载的歌词"))
            if (recentLyrics.isEmpty()) {
                addView(normalText("还没有保存过歌词。播放歌曲并成功匹配后，这里会出现最近的 .lrc 文件。"))
            } else {
                recentLyrics.forEach { item ->
                    addView(localLyricsRow(item))
                }
            }
            addView(actionButton("刷新列表") {
                renderCurrentPage()
            })
        }
    )

    return scroll(container)
}
