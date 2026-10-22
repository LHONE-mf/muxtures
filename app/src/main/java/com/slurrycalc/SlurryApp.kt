package com.slurrycalc

import android.app.Application
import android.content.MutableContextWrapper
import android.view.ContextThemeWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import com.slurrycalc.data.DataHolder
import java.util.concurrent.Executors

/**
 * Инициализация БД (Room + миграция SharedPreferences → Room).
 *
 * Исправление вылета на Oxygen OS 16 (OnePlus 15): в v1.4.0 миграция и первый
 * запрос к SQLite выполнялись СИНХРОННО в Application.onCreate() на главном потоке.
 * Агрессивный ANR-менеджер ColorOS/Oxygen OS убивал процесс ещё до первого кадра.
 * Теперь инициализация идёт в фоновом single-thread executor, а UI ждёт готовности
 * через StartupState (splash «Инициализация…»).
 */
class SlurryApp : Application() {

    val initFuture by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "slurry-db-init") }
            .submit {
                try {
                    DataHolder.init(this)
                    StartupState.done(null)
                } catch (e: Throwable) {
                    StartupState.fail(e)
                }
            }
    }

    override fun onCreate() {
        super.onCreate()
        // Диагностика: любой необработанный exception пишем в файл — после перезапуска
        // он будет показан на экране ошибки вместо молчаливого вылета.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(filesDir, "crash.log")
                f.writeText(
                    "FATAL ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n" +
                        "thread=${t.name}\n" + android.util.Log.getStackTraceString(e)
                )
            } catch (_: Throwable) { /* не разгоняемся на падении */ }
            previous?.uncaughtException(t, e)
        }
        // ленивый запуск фоновой инициализации (сразу после onCreate)
        initFuture
    }
}

/** Прогресс фоновой инициализации БД для splash-экрана. */
object StartupState {
    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var error: String? = null
        private set

    fun done(msg: String?) { error = msg; ready = true }
    fun fail(e: Throwable) {
        error = e.javaClass.simpleName + ": " + (e.message ?: "")
        ready = true
    }
}

/**
 * Compose LocalContext иногда оборачивает контекст в ContextThemeWrapper/MutableContextWrapper.
 * getSharedPreferences()/Application-касты на них падают или ведут себя непредсказуемо —
 * раскручиваем до настоящего Application.
 */
@Composable
fun appContext(): Application {
    var ctx = LocalContext.current
    while (ctx is MutableContextWrapper) ctx = ctx.baseContext
    while (ctx is ContextThemeWrapper) ctx = ctx.baseContext
    return ctx.applicationContext as Application
}
