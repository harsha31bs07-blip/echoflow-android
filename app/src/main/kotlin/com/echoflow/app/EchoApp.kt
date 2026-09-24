package com.echoflow.app

import android.app.Application

class EchoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EchoRuntime.init(this)
    }
}
