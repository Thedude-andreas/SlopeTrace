package com.slopetrace

import android.app.Application
import com.slopetrace.di.AppContainer

class SlopeTraceApp : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }
}
