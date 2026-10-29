package dev.liquidcalendar

import android.app.Application
import dev.liquidcalendar.data.Stores
import dev.liquidcalendar.util.Strings

class CalendarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.app = this
        Stores.init(this)
    }
}
