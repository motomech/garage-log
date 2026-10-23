package com.motomech.garagelog

import android.app.Application
import com.motomech.garagelog.data.Backup
import com.motomech.garagelog.data.DocFiles
import com.motomech.garagelog.data.Repo
import com.motomech.garagelog.data.Updates
import com.motomech.garagelog.tracking.Notifs

class GarageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifs.createChannels(this)
        Notifs.scheduleDaily(this)
        Backup.schedule(this)
        Updates.schedule(this)
        Thread { DocFiles.cleanOrphans(this, Repo.current) }.start()
    }
}
