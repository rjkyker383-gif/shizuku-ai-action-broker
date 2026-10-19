package com.rjkyker.shizukuai.broker

import android.app.Application
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver

class BrokerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Listen for DataWedge RESULT_ACTION as soon as the process starts.
        DataWedgeResultReceiver.ensureRegistered(this)
    }
}
