package com.example.TCECS

import android.app.Application

class SocietyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SocietyApplication
            private set
    }
}

