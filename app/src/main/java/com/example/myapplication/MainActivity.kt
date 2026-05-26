package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var locked = false

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
        layout.addView(selectLyricsDirButton)
        layout.addView(importLyricsButton)
        layout.addView(lyricsDirButton)
        layout.addView(hideButton)

        setContentView(layout)
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