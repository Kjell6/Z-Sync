package de.kjell.zencompanion

import android.app.Application
import de.kjell.zencompanion.data.AndroidWhatsNewRepository
import de.kjell.zencompanion.data.AppContextHolder
import de.kjell.zencompanion.data.BrowserHistory
import de.kjell.zencompanion.data.ContentBlocking
import de.kjell.zencompanion.data.SitePermissions
import de.kjell.zencompanion.favicon.FaviconLoader

class ZenCompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything writes preferences: tells an update from a fresh
        // install, so only returning readers get release notes.
        AndroidWhatsNewRepository(this).recordFirstLaunch(BuildConfig.VERSION_NAME)
        AppContextHolder.initialize(this)
        FaviconLoader.initialize(this)
        ContentBlocking.start(this)
        SitePermissions.start(this)
        BrowserHistory.start(this)
    }
}
