package pl.fehu.imageresizer

import android.app.Application

/** Application entry point; owns the process-wide [AppContainer]. */
class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
