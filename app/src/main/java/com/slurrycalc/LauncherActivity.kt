package com.slurrycalc

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Лёгкий прозрачный лаунчер (не использует Compose вообще).
 *
 * На некоторых устройствах прямое открытие Compose-Activity из лаунчера
 * приводит к мгновенному падению процесса до отрисовки первого кадра.
 * Отдельная нативная activity-«трамплин» надёжно стартует MainActivity
 * и сразу завершается.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent().setClassName(packageName, "com.slurrycalc.ui.MainActivity")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // если по какой-то причине не запустилась ui.MainActivity — пробуем старое имя
            startActivity(Intent().setClassName(packageName, "com.slurrycalc.MainActivity"))
        }
        finish()
        overridePendingTransition(0, 0)
    }
}

