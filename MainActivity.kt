package com.example.threeplatformcheckin

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("checkin_settings", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 48, 40, 32) }
        root.addView(TextView(this).apply { text = "三平台签到助手"; textSize = 26f })
        root.addView(TextView(this).apply { text = "手机后台定时检查 · 每 24 小时"; textSize = 14f })
        listOf("小黑盒" to "enable_xiaoheihe", "快手" to "enable_kuaishou", "抖音" to "enable_douyin").forEach { (label, key) ->
            root.addView(CheckBox(this).apply { text = label; textSize = 18f; isChecked = prefs.getBoolean(key, true); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() } })
        }
        val status = TextView(this).apply { text = "后台任务尚未开启。"; textSize = 14f; setPadding(0, 16, 0, 0) }
        root.addView(TextView(this).apply { text = "\n说明：当前版本只有后台调度与通知，尚未接入三家平台的官方签到接口，因此不会声称已签到。"; textSize = 14f })
        root.addView(Button(this).apply { text = "开启每日后台检查"; setOnClickListener { CheckinScheduler.schedule(this@MainActivity); status.text = "每日后台检查已开启（不代表平台签到成功）。" } })
        root.addView(Button(this).apply { text = "暂停后台检查"; setOnClickListener { WorkManager.getInstance(this@MainActivity).cancelUniqueWork(CheckinScheduler.WORK_NAME); status.text = "后台检查已暂停。" } })
        root.addView(status)
        setContentView(root)
    }
}
