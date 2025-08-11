package com.example.ratesve

data class CurrencyRates(
    val bcvRate: String?,
    val euroRate: String?, // Added Euro
    val binanceRate: String?,
    val satoshiRate: String?,
    val timestamp: Long = System.currentTimeMillis()
)
