package com.example.ratesve
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.GzipSource
import okio.buffer
import org.jsoup.Jsoup
import java.io.IOException
import java.text.DecimalFormat
import java.util.Locale
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class CurrencyFetchWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val decimalFormat = DecimalFormat("0.00").apply {
        decimalFormatSymbols = java.text.DecimalFormatSymbols(Locale.US) // Ensure dot as decimal separator
    }

    companion object {
        const val WORK_NAME = "CurrencyFetchWork"
        private const val API_KEY_ALIAS = "api_key"
        private const val PREFS_FILE_NAME = "secret_shared_prefs"
    }

    override suspend fun doWork(): Result {
        var bcvRate: Double? = null
        var binanceRate: Double? = null
        var satoshiRate: Double? = null

        try {
            bcvRate = fetchBcvRate()
        } catch (e: Exception) {
            Log.e("CurrencyWorker", "Error fetching BCV rate: ${e.message}", e)
        }

        try {
            binanceRate = fetchBinanceRate()
        } catch (e: Exception) {
            Log.e("CurrencyWorker", "Error fetching Binance rate: ${e.message}", e)
        }

        try {
            satoshiRate = fetchSatoshiRate()
        } catch (e: Exception) {
            Log.e("CurrencyWorker", "Error fetching Satoshi rate: ${e.message}", e)
        }

        val prefs = applicationContext.getSharedPreferences("CurrencyWidget", Context.MODE_PRIVATE)
        with(prefs.edit()) {
            putString("bcv_value", bcvRate?.let { decimalFormat.format(it) } ?: "N/A")
            putString("binance_value", binanceRate?.let { decimalFormat.format(it) } ?: "N/A")
            putString("satoshi_value", satoshiRate?.let { decimalFormat.format(it) } ?: "N/A")
            apply()
        }

        // Update the widget
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val componentName = ComponentName(applicationContext, RatesVeProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(applicationContext.packageName, R.layout.rates_ve_layout)
            views.setTextViewText(R.id.bcv_value, bcvRate?.let { decimalFormat.format(it) } ?: "N/A")
            views.setTextViewText(R.id.binance_value, binanceRate?.let { decimalFormat.format(it) } ?: "N/A")
            views.setTextViewText(R.id.satoshi_value, satoshiRate?.let { decimalFormat.format(it) } ?: "N/A")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        return Result.success()
    }

    private fun fetchBcvRate(): Double? {
        val request = Request.Builder()
            .url("https://www.bcv.org.ve")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val html = response.body?.string() ?: return null

            val doc = Jsoup.parse(html)
            val element = doc.select("div.centrado>strong").last()
            val text = element?.text()?.trim()

            return text?.replace(",", ".")?.toDoubleOrNull()
        }
    }

    private fun fetchBinanceRate(): Double? {
        val jsonMediaType = "application/json".toMediaTypeOrNull()
        val requestBody = """
            {
                "asset": "USDT",
                "fiat": "VES",
                "merchantCheck": false,
                "page": 1,
                "payTypes": ["PagoMovil"],
                "publisherType": null,
                "rows": 1,
                "tradeType": "SELL"
            }
        """.trimIndent().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search")
            .post(requestBody)
            .addHeader("Accept", "*/*")
            .addHeader("Accept-Encoding", "gzip, deflate, br")
            .addHeader("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
            .addHeader("Content-Type", "application/json")
            .addHeader("Host", "p2p.binance.com")
            .addHeader("Origin", "https://p2p.binance.com")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            val responseBody = if ("gzip".equals(response.header("Content-Encoding"), ignoreCase = true)) {
                response.body?.source()?.let { source ->
                    GzipSource(source).buffer().readUtf8()
                }
            } else {
                response.body?.string()
            } ?: return null

            val binanceResponse = gson.fromJson(responseBody, BinanceResponse::class.java)
            return binanceResponse.data?.firstOrNull()?.adv?.price?.toDoubleOrNull()
        }
    }

    private fun fetchSatoshiRate(): Double? {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val sharedPreferences = EncryptedSharedPreferences.create(
            PREFS_FILE_NAME,    // Corrected: 1st argument String (file name)
            masterKeyAlias,     // Corrected: 2nd argument String (master key alias)
            applicationContext, // Corrected: 3rd argument Context
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val apiKey = sharedPreferences.getString(API_KEY_ALIAS, null)

        if (apiKey.isNullOrEmpty()) {
            Log.i("CurrencyWorker", "CoinMarketCap API key not found. Skipping Satoshi rate fetch.")
            return null
        }

        val request = Request.Builder()
            .url("https://pro-api.coinmarketcap.com/v2/cryptocurrency/quotes/latest?id=1")
            .addHeader("X-CMC_PRO_API_KEY", apiKey)
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("CurrencyWorker", "CoinMarketCap API Error: ${response.code} ${response.message}")
                 // You might want to inspect response.body?.string() for more details from the API
                throw IOException("Unexpected code ${response.code} from CoinMarketCap")
            }
            val json = response.body?.string() ?: return null

            val satoshiResponse = gson.fromJson(json, SatoshiResponse::class.java)
            val btcPriceUsd = satoshiResponse.data?.get("1")?.quote?.usd?.price ?: return null
            return 100_000_000.0 / btcPriceUsd
        }
    }

    //region Data Classes for JSON Parsing
    data class BinanceResponse(
        val data: List<BinanceData>?
    )

    data class BinanceData(
        val adv: BinanceAdv?
    )

    data class BinanceAdv(
        val price: String?
    )

    data class SatoshiResponse(
        val data: Map<String, CryptoData>?
    )

    data class CryptoData(
        val quote: QuoteData?
    )

    data class QuoteData(
        @SerializedName("USD")
        val usd: UsdQuote?
    )

    data class UsdQuote(
        val price: Double?
    )
    //endregion
}
