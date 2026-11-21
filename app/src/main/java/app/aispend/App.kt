package app.aispend

import android.app.Application
import app.aispend.data.Store
import app.aispend.sync.RefreshWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedule(this, Store.get(this).refreshMinutes.value)
    }
}
