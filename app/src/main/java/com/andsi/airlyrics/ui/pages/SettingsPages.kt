package com.andsi.airlyrics.ui.pages

import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.andsi.airlyrics.LyricsSettingsStore
import com.andsi.airlyrics.LyricsStorage
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.MainActivity.KeyedOptionItem
import com.andsi.airlyrics.MainActivity.SettingsSubPage
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

internal fun MainActivity.createSettingsPage(): View {
    return when (settingsSubPage) {
        SettingsSubPage.HOME -> createSettingsHomePage()
        SettingsSubPage.SYSTEM -> createSystemSettingsPage()
        SettingsSubPage.LOCAL_LYRICS -> createLocalLyricsSettingsPage()
        SettingsSubPage.ABOUT -> createAboutSettingsPage()
    }
}

internal fun MainActivity.createSettingsHomePage(): View {
    val container = pageContainer()

    container.addView(settingsHomeHeader())

    container.addView(
        settingsCategoryCard(
            title = "系统与权限",
            subtitle = "悬浮窗、通知权限、通知访问权限。",
            status = permissionSummary(),
            accent = colorAccent
        ) {
            settingsSubPage = SettingsSubPage.SYSTEM
            renderCurrentPage()
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "本地歌词",
            subtitle = "歌词源、自动保存、下载目录和最近保存的 .lrc。",
            status = "${LyricsSettingsStore.getLyricsSourceTitle(this)} · ${if (LyricsSettingsStore.isAutoSaveLocalEnabled(this)) "自动保存" else "不自动保存"}",
            accent = colorAccentPink
        ) {
            settingsSubPage = SettingsSubPage.LOCAL_LYRICS
            renderCurrentPage()
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "关于",
            subtitle = "版本号、项目地址、更新记录。",
            status = "AirLyrics ${getAppVersionName()}",
            accent = colorAccentMint
        ) {
            settingsSubPage = SettingsSubPage.ABOUT
            renderCurrentPage()
        }
    )

    container.addView(smallHint("当前页面只显示最高分类，进入分类后再调整具体设置。"))

    return scroll(container)
}

internal fun MainActivity.createSystemSettingsPage(): View {
    val container = pageContainer()
    container.addView(settingsBackHeader("系统与权限", "让悬浮歌词能正常出现、读取媒体状态，并保持前台服务稳定。"))

    container.addView(
        card {
            addView(bigText("权限状态"))
            addView(settingRow("悬浮窗权限", if (Settings.canDrawOverlays(this@createSystemSettingsPage)) "已开启" else "未开启"))
            addView(settingRow("通知权限", if (hasNotificationPermission()) "已开启" else "未开启"))
            addView(settingRow("通知访问权限", if (hasNotificationListenerAccess()) "已开启" else "未开启"))
            addView(smallHint("通知访问权限负责读取媒体控制器；悬浮窗权限负责把歌词盖在其他 App 上。"))
        }
    )

    container.addView(
        card {
            addView(bigText("快捷入口"))
            addView(horizontalButtons(
                "悬浮窗权限" to { requestOverlayPermission() },
                "通知权限" to { requestNotificationPermissionIfNeeded() }
            ))
            addView(actionButton("打开通知访问设置") {
                startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        }
    )

    return scroll(container)
}

internal fun MainActivity.createLocalLyricsSettingsPage(): View {
    val container = pageContainer()
    val selectedSource = LyricsSettingsStore.getLyricsSource(this)
    val autoSave = LyricsSettingsStore.isAutoSaveLocalEnabled(this)
    val recentLyrics = LyricsStorage.listRecentLyrics(this, limit = 8)

    container.addView(settingsBackHeader("本地歌词", "决定歌词从哪里来，也决定找到后要不要收进本地小仓库。"))

    container.addView(
        card {
            addView(bigText("歌词源"))
            addView(normalText("当前：${LyricsSettingsStore.getLyricsSourceTitle(this@createLocalLyricsSettingsPage)}"))
            addView(liveOptionGrid(
                LyricsSettingsStore.sourceOptions.map { option ->
                    KeyedOptionItem(
                        key = option.key,
                        title = option.title,
                        selected = option.key == selectedSource,
                        action = {
                            LyricsSettingsStore.setLyricsSource(this@createLocalLyricsSettingsPage, option.key)
                            reloadFloatingLyrics()
                            Toast.makeText(this@createLocalLyricsSettingsPage, "歌词源已切换为：${option.title}", Toast.LENGTH_SHORT).show()
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
                val enabled = !LyricsSettingsStore.isAutoSaveLocalEnabled(this@createLocalLyricsSettingsPage)
                LyricsSettingsStore.setAutoSaveLocalEnabled(this@createLocalLyricsSettingsPage, enabled)
                autoSaveButton.text = if (enabled) "自动保存：开启" else "自动保存：关闭"
                Toast.makeText(this@createLocalLyricsSettingsPage, if (enabled) "已开启自动保存" else "已关闭自动保存", Toast.LENGTH_SHORT).show()
            }
            addView(autoSaveButton)
        }
    )

    container.addView(
        card {
            addView(bigText("歌词文件夹"))
            addView(normalText("保存目录：${LyricsStorage.getLyricsDirDisplayPath(this@createLocalLyricsSettingsPage)}"))
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

internal fun MainActivity.createAboutSettingsPage(): View {
    val container = pageContainer()
    container.addView(settingsBackHeader("关于", "一些和 AirLyrics 有关的小纸条。"))

    container.addView(
        card {
            addView(bigText("AirLyrics"))
            addView(normalText("版本号：${getAppVersionName()}"))
            addView(normalText("包名：$packageName"))
            addView(actionButton("打开项目地址") {
                openUrl("https://github.com/AndSi-327/android-floating-lyrics")
            })
        }
    )

    container.addView(
        card {
            addView(bigText("更改日志"))
            addView(changelogItem("设置页分级", "系统与权限、本地歌词、关于，进入后再展示具体选项。"))
            addView(changelogItem("歌词源设置", "新增网易云歌词 / 仅本地歌词的来源选择。"))
            addView(changelogItem("本地歌词管理", "新增自动保存开关、保存目录展示和最近下载歌词列表。"))
            addView(changelogItem("轻飘飘视觉", "整体颜色改成奶油底、淡粉蓝按钮和柔软卡片。"))
        }
    )

    return scroll(container)
}
