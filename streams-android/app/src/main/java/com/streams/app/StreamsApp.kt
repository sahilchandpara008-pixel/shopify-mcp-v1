package com.streams.app

import android.app.Application

class StreamsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
    }
}
