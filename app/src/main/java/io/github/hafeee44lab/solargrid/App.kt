package io.github.hafeee44lab.solargrid

import android.app.Application
import io.github.hafeee44lab.solargrid.data.AppGraph
import io.github.hafeee44lab.solargrid.service.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.get(this)
        Notifier.createChannels(this)
    }
}
