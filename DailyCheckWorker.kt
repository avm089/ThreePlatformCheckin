package com.example.threeplatformcheckin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val p = applicationContext.getSharedPreferences("checkin_settings", Context.MODE_PRIVATE)
        val enabled = listOf("小黑盒" to "enable_xiaoheihe", "快手" to "enable_kuaishou", "抖音" to "enable_douyin")
            .filter { p.getBoolean(it.second, true) }.map { it.first }
        if (enabled.isNotEmpty()) notify("每日检查已运行", "已检查调度：${enabled.joinToString("、")}。平台官方签到接口尚未接入，未执行真实签到。")
        return Result.success()
    }
    private fun notify(title: String, message: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = "checkin_status"
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(id, "签到状态", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(2001, NotificationCompat.Builder(applicationContext, id).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setAutoCancel(true).build())
    }
}
