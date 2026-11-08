/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionService
import com.dimadesu.lifestreamer.services.CameraStreamerService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide notification bar (status bar) and navigation bar for full-screen display
        hideSystemUI()

        // Notification Quit starts a new activity when none is running.
        // Handle it here — onNewIntent is not called for a fresh launch —
        // and skip UI/service bind so we don't race teardown.
        if (intent?.action == CameraStreamerService.ACTION_EXIT_APP) {
            exitApp()
            return
        }

        setContentView(R.layout.main_activity)

        // Note: Permissions (Camera, Microphone, Notifications) are requested by PreviewFragment.
        // Bluetooth permissions are requested on-demand when user taps the BT mic toggle.

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, PreviewFragment())
                .commitNow()
        }

    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun toggleChatOverlay() {
        val targetPackage = "com.kongjjj.overlay"
        val isInstalled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(targetPackage, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(targetPackage, 0)
            }
            true
        } catch (_: Exception) {
            false
        }

        if (!isInstalled) {
            Toast.makeText(this, "未安裝 Chat-overlay 應用程式，請先編譯安裝 Chat-overlay", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val serviceIntent = Intent("com.kongjjj.overlay.ACTION_TOGGLE").apply {
                setClassName(targetPackage, "com.kongjjj.overlay.FloatingViewService")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to toggle FloatingViewService directly, trying MainActivity launch", e)
            try {
                val activityIntent = Intent().apply {
                    setClassName(targetPackage, "com.kongjjj.overlay.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(activityIntent)
            } catch (ex: Exception) {
                Log.e("MainActivity", "Failed to launch Chat-overlay MainActivity", ex)
                Toast.makeText(this, "無法開啟 Chat-overlay 懸浮聊天室", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stopChatOverlayService() {
        try {
            val intent = Intent().apply {
                setClassName("com.kongjjj.overlay", "com.kongjjj.overlay.FloatingViewService")
            }
            stopService(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to stop Chat-overlay service", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle notification tap action to avoid re-creating activity and
        // triggering unnecessary view detach/attach which can race with camera.
        val action = intent.action
        if (action == CameraStreamerService.ACTION_EXIT_APP) {
            exitApp()
        } else if (action == CameraStreamerService.ACTION_OPEN_FROM_NOTIFICATION) {
            // If the PreviewFragment is already present, do nothing. If not,
            // ensure it's added without recreating the fragment stack.
            val current = supportFragmentManager.findFragmentById(R.id.container)
            if (current == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container, PreviewFragment())
                    .commitNow()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stopChatOverlayService()
        }
    }

    fun exitApp() {
        // Unbind first: a started+bound service ignores stopService() until
        // every client unbinds. PreviewViewModel otherwise unbinds only in
        // onCleared(), which runs after the activity is already finishing.
        (supportFragmentManager.findFragmentById(R.id.container) as? PreviewFragment)
            ?.prepareForExit()

        try {
            stopService(Intent(this, CameraStreamerService::class.java))
        } catch (_: Exception) {
            // Service might not be running, that's okay
        }
        try {
            stopService(Intent(this, MediaProjectionService::class.java))
        } catch (_: Exception) {
            // Optional companion service; ignore if absent
        }

        stopChatOverlayService()

        finishAndRemoveTask()
    }
}
