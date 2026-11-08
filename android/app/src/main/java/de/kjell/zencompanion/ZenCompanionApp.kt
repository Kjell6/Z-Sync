package de.kjell.zencompanion

import android.app.Application
import de.kjell.zencompanion.data.AppContextHolder
import de.kjell.zencompanion.data.BrowserHistory
import de.kjell.zencompanion.data.ContentBlocking
import de.kjell.zencompanion.data.SitePermissions
import de.kjell.zencompanion.favicon.FaviconLoader

class ZenCompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.initialize(this)
        FaviconLoader.initialize(this)
        ContentBlocking.start(this)
        SitePermissions.start(this)
        BrowserHistory.start(this)
    }
}
