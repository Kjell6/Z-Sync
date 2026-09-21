package de.kjell.zencompanion

import android.app.Application
import de.kjell.zencompanion.data.AppContextHolder
import de.kjell.zencompanion.favicon.FaviconLoader

class ZenCompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.initialize(this)
        FaviconLoader.initialize(this)
    }
}
