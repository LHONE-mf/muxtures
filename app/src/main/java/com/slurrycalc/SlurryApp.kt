package com.slurrycalc

import android.app.Application
import com.slurrycalc.data.DataHolder

/**
 * Инициализация БД (Room + миграция SharedPreferences → Room) при старте процесса.
 * Миграция синхронная, но выполняется до первого кадра UI — данные маленькие (<1 МБ),
 * задержка единицы миллисекунд. Старые prefs при этом не трогаются.
 */
class SlurryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataHolder.init(this)
    }
}
