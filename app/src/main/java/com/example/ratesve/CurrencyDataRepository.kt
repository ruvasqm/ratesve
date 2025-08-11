package com.example.ratesve

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.util.Log // Added for logging

object CurrencyDataRepository {

    // SharedPreferences details from RatesVeProvider
    private const val REGULAR_PREFS_FILE_NAME = "CurrencyWidget"
    private const val KEY_BCV_RATE = "bcv_value"
    private const val KEY_EURO_RATE = "euro_value" // Added Euro key
    private const val KEY_BINANCE_RATE = "binance_value"
    private const val KEY_SATOSHI_RATE = "satoshi_value"
    private const val KEY_TIMESTAMP = "timestamp_key"
    private const val KEY_LAST_KNOWN_RATES_JSON = "last_known_rates_json"

    // For API Key (used to decide if Satoshi rate should be shown/fetched)
    private const val ENCRYPTED_PREFS_FILE_NAME = "secret_shared_prefs"
    private const val API_KEY_ALIAS = "api_key" // As used in MainActivity

    private val _ratesLiveData = MutableLiveData<CurrencyRates?>()
    val ratesLiveData: LiveData<CurrencyRates?> = _ratesLiveData

    private var isInitialized = false
    private lateinit var appContext: Context
    private lateinit var regularPrefs: SharedPreferences
    private var encryptedPrefs: SharedPreferences? = null
    private val gson = Gson()

    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        regularPrefs = appContext.getSharedPreferences(REGULAR_PREFS_FILE_NAME, Context.MODE_PRIVATE)

        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            encryptedPrefs = EncryptedSharedPreferences.create(
                ENCRYPTED_PREFS_FILE_NAME,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("CurrencyDataRepo", "Error initializing EncryptedPrefs: ${e.message}", e)
        }

        isInitialized = true
        loadInitialRates()
    }

    fun updateRates(newRates: CurrencyRates) {
        if (!isInitialized) {
            Log.e("CurrencyDataRepo", "Repository not initialized. Call initialize() first.")
            return
        }
        _ratesLiveData.postValue(newRates)

        val ratesJson = gson.toJson(newRates)
        regularPrefs.edit {
            putString(KEY_LAST_KNOWN_RATES_JSON, ratesJson)
            putString(KEY_BCV_RATE, newRates.bcvRate)
            putString(KEY_EURO_RATE, newRates.euroRate) // Save Euro rate
            putString(KEY_BINANCE_RATE, newRates.binanceRate)
            putString(KEY_SATOSHI_RATE, newRates.satoshiRate)
            putLong(KEY_TIMESTAMP, newRates.timestamp)
            apply()
        }
         Log.d("CurrencyDataRepo", "Rates updated and saved. JSON: $ratesJson")
    }

    fun loadInitialRates() {
        if (!isInitialized) {
            Log.w("CurrencyDataRepo", "Attempted to load initial rates before initialization.")
            return
        }

        val ratesJson = regularPrefs.getString(KEY_LAST_KNOWN_RATES_JSON, null)
        if (ratesJson != null) {
            try {
                val loadedRates: CurrencyRates? = gson.fromJson(ratesJson, object : TypeToken<CurrencyRates>() {}.type)
                _ratesLiveData.postValue(loadedRates)
                Log.d("CurrencyDataRepo", "Loaded rates from JSON: $loadedRates")
                return
            } catch (e: Exception) {
                Log.e("CurrencyDataRepo", "Error loading rates from JSON: ${e.message}", e)
            }
        }

        val bcv = regularPrefs.getString(KEY_BCV_RATE, null)
        val euro = regularPrefs.getString(KEY_EURO_RATE, null) // Load Euro rate
        val binance = regularPrefs.getString(KEY_BINANCE_RATE, null)
        val satoshi = regularPrefs.getString(KEY_SATOSHI_RATE, null)
        val timestamp = regularPrefs.getLong(KEY_TIMESTAMP, 0L)

        if (bcv != null || euro != null || binance != null || satoshi != null) {
            val rates = CurrencyRates(bcv, euro, binance, satoshi, if(timestamp == 0L) System.currentTimeMillis() else timestamp)
            _ratesLiveData.postValue(rates)
            Log.d("CurrencyDataRepo", "Loaded rates from individual legacy keys: $rates")
        } else {
            _ratesLiveData.postValue(null)
            Log.d("CurrencyDataRepo", "No rate data found in SharedPreferences.")
        }
    }

    fun getApiKey(): String? {
        if (!isInitialized) {
            Log.w("CurrencyDataRepo", "Attempted to get API key before initialization.")
            return null
        }
        return encryptedPrefs?.getString(API_KEY_ALIAS, null)
    }

    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrEmpty()
    }
}
