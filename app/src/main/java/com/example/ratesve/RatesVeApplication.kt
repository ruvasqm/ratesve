package com.example.ratesve

import android.app.Application

class RatesVeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CurrencyDataRepository.initialize(this)
    }
}
