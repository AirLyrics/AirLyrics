package com.andsi.airlyrics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var locked = false

    private val mediaSourceRefreshHandler = Handler(Looper.getMainLooper())
    private var mediaSourceDialog: AlertDialog? = null
    private var mediaSourceAdapter: ArrayAdapter<String>? = null
    private var dialogSourceControllers: List<MediaController> = emptyList()

    private val mediaSourceRefreshRunnable = object : Runnable {
        override fun run() {
            refreshMediaSourceDialog()

            if (mediaSourceDialog?.isShowing == true) {
                mediaSourceRefreshHandler.postDelayed(this, 500)
            }
        }
    }

    private val importLyricsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            val intent = Intent(this, FloatingLyricsService::class.java).apply {
                action = FloatingLyricsService.ACTION_IMPORT_LYRICS
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

    private val selectLyricsDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult

            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            LyricsStorage.saveLyricsDirUri(this, uri)

            Toast.makeText(
                this,
                "已设置歌词保存目录",
                Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        val notificationAccessButton = Button(this).apply {
            text = "开启通知访问权限"
            setOnClickListener {
                startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val permissionButton = Button(this).apply {
            text = "申请悬浮窗权限"
            setOnClickListener {
                requestOverlayPermission()
            }
        }

        val showButton = Button(this).apply {
            text = "显示悬浮歌词"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(this@MainActivity, FloatingLyricsService::class.java).apply {
                        action = FloatingLyricsService.ACTION_SHOW
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }

        val lockButton = Button(this).apply {
            text = "锁定穿透：关闭"
            setOnClickListener {
                locked = !locked
                text = if (locked) "锁定穿透：开启" else "锁定穿透：关闭"

                val intent = Intent(this@MainActivity, FloatingLyricsService::class.java).apply {
                    action = if (locked) {
                        FloatingLyricsService.ACTION_LOCK
                    } else {
                        FloatingLyricsService.ACTION_UNLOCK
                    }
                }

                startService(intent)
            }
        }


        val selectMediaSourceButton = Button(this).apply {
            text = "选择歌词来源"
            setOnClickListener {
                showMediaSourceDialog()
            }
        }

        val selectLyricsDirButton = Button(this).apply {
            text = "选择歌词保存目录"
            setOnClickListener {
                selectLyricsDirLauncher.launch(null)
            }
        }

        val importLyricsButton = Button(this).apply {
            text = "导入本地歌词"
            setOnClickListener {
                importLyricsLauncher.launch(
                    arrayOf("text/*", "application/octet-stream", "*/*")
                )
            }
        }

        val lyricsDirButton = Button(this).apply {
            text = "查看歌词保存目录"
            setOnClickListener {
                showLyricsDir()
            }
        }

        val hideButton = Button(this).apply {
            text = "隐藏悬浮歌词"
            setOnClickListener {
                val intent = Intent(this@MainActivity, FloatingLyricsService::class.java).apply {
                    action = FloatingLyricsService.ACTION_HIDE
                }

                startService(intent)
            }
        }

        layout.addView(permissionButton)
        layout.addView(notificationAccessButton)
        layout.addView(showButton)
        layout.addView(lockButton)
        layout.addView(selectMediaSourceButton)
        layout.addView(selectLyricsDirButton)
        layout.addView(importLyricsButton)
        layout.addView(lyricsDirButton)
        layout.addView(hideButton)

        setContentView(layout)

        autoSelectMediaSourceOnceIfNeeded()
    }


    private fun showMediaSourceDialog() {
        dialogSourceControllers = getMediaSourceControllersForDialog()

        if (dialogSourceControllers.isEmpty()) {
            Toast.makeText(
                this,
                "没有检测到活跃媒体，请先开启通知访问权限并播放音乐",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        mediaSourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            buildMediaSourceItems(dialogSourceControllers)
        )

        mediaSourceDialog = AlertDialog.Builder(this)
            .setTitle("选择歌词来源")
            .setAdapter(mediaSourceAdapter) { _, which ->
                if (which !in dialogSourceControllers.indices) return@setAdapter

                val selectedPackageName = dialogSourceControllers[which].packageName

                MediaSourceStore.saveSelectedPackage(this, selectedPackageName)
                notifyFloatingServiceSourceChanged(selectedPackageName)

                Toast.makeText(
                    this,
                    "已选择：${getAppName(selectedPackageName)}",
                    Toast.LENGTH_SHORT
                ).show()

                mediaSourceDialog?.dismiss()
            }
            .setOnDismissListener {
                mediaSourceRefreshHandler.removeCallbacks(mediaSourceRefreshRunnable)
                mediaSourceDialog = null
                mediaSourceAdapter = null
                dialogSourceControllers = emptyList()
            }
            .show()

        mediaSourceRefreshHandler.removeCallbacks(mediaSourceRefreshRunnable)
        mediaSourceRefreshHandler.postDelayed(mediaSourceRefreshRunnable, 500)
    }

    private fun refreshMediaSourceDialog() {
        if (mediaSourceDialog?.isShowing != true) return

        val adapter = mediaSourceAdapter ?: return
        dialogSourceControllers = getMediaSourceControllersForDialog()

        if (dialogSourceControllers.isEmpty()) {
            mediaSourceDialog?.dismiss()
            Toast.makeText(
                this,
                "当前没有活跃媒体",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        adapter.clear()
        adapter.addAll(buildMediaSourceItems(dialogSourceControllers))
        adapter.notifyDataSetChanged()
    }

    private fun getMediaSourceControllersForDialog(): List<MediaController> {
        return getActiveMediaControllers()
            .filter { controller ->
                controller.metadata != null || controller.playbackState != null
            }
    }

    private fun buildMediaSourceItems(controllers: List<MediaController>): List<String> {
        val selectedPackage = MediaSourceStore.getSelectedPackage(this)

        return controllers.map { controller ->
            val title = controller.metadata
                ?.getString(MediaMetadata.METADATA_KEY_TITLE)
                .orEmpty()
                .ifBlank { "未知歌曲" }

            val artist = controller.metadata
                ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""

            val stateText = getPlaybackStateText(controller.playbackState?.state)
            val connectedText = if (controller.packageName == selectedPackage) "，已连接" else ""

            val appName = getAppName(controller.packageName)
            val mediaText = if (artist.isNotBlank()) "$title - $artist" else title

            "$appName（$stateText$connectedText）\n$mediaText"
        }
    }

    private fun getPlaybackStateText(state: Int?): String {
        return when (state) {
            PlaybackState.STATE_PLAYING -> "播放中"
            PlaybackState.STATE_PAUSED -> "暂停中"
            PlaybackState.STATE_STOPPED -> "已停止"
            PlaybackState.STATE_BUFFERING -> "缓冲中"
            PlaybackState.STATE_CONNECTING -> "连接中"
            PlaybackState.STATE_FAST_FORWARDING -> "快进中"
            PlaybackState.STATE_REWINDING -> "快退中"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "切到下一首"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "切到上一首"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "切换队列"
            PlaybackState.STATE_NONE -> "无播放状态"
            PlaybackState.STATE_ERROR -> "播放异常"
            else -> "状态未知"
        }
    }

    private fun autoSelectMediaSourceOnceIfNeeded() {
        if (MediaSourceStore.getSelectedPackage(this) != null) return

        val controllers = getActiveMediaControllers()
            .filter { it.metadata != null || it.playbackState != null }

        val controller = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull() ?: return

        val packageName = controller.packageName
        MediaSourceStore.saveSelectedPackage(this, packageName)
        notifyFloatingServiceSourceChanged(packageName)

        Toast.makeText(
            this,
            "已自动选择：${getAppName(packageName)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun getActiveMediaControllers(): List<MediaController> {
        return try {
            val mediaSessionManager =
                getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            mediaSessionManager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Toast.makeText(this, "需要先开启通知访问权限", Toast.LENGTH_LONG).show()
            emptyList()
        } catch (e: Exception) {
            Toast.makeText(this, "读取媒体来源失败", Toast.LENGTH_LONG).show()
            emptyList()
        }
    }

    private fun notifyFloatingServiceSourceChanged(packageName: String?) {
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_SELECT_MEDIA_SOURCE
            putExtra(FloatingLyricsService.EXTRA_SOURCE_PACKAGE, packageName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun showLyricsDir() {
        val path = LyricsStorage.getLyricsDirDisplayPath(this)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("歌词保存目录", path)
        )

        Toast.makeText(
            this,
            "歌词保存目录已复制",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        mediaSourceRefreshHandler.removeCallbacks(mediaSourceRefreshRunnable)
        mediaSourceDialog?.dismiss()
        mediaSourceDialog = null
        mediaSourceAdapter = null
        dialogSourceControllers = emptyList()
        super.onDestroy()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}