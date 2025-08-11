package com.example.ratesve

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
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
import android.app.PendingIntent
import java.text.SimpleDateFormat
import java.util.Date

class CurrencyFetchWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val decimalFormat = DecimalFormat("0.00").apply {
        decimalFormatSymbols = java.text.DecimalFormatSymbols(Locale.US)
    }

    companion object {
        const val WORK_NAME = "CurrencyFetchWork"
    }

    override suspend fun doWork(): Result {
        Log.d("CurrencyWorker", "Starting currency fetch work.")
        var bcvRateValue: Double? = null
        var euroRateValue: Double? = null
        var binanceRateValue: Double? = null
        var satoshiRateValue: Double? = null

        try {
            val (bcv, euro) = fetchBcvRates()
            bcvRateValue = bcv
            euroRateValue = euro
            Log.d("CurrencyWorker", "BCV Rate: $bcvRateValue, Euro Rate: $euroRateValue")
        } catch (e: Exception) {
            Log.e("CurrencyWorker", "Error fetching BCV rates: ${e.message}", e)
        }

        try {
            binanceRateValue = fetchBinanceRate()
            Log.d("CurrencyWorker", "Binance Rate: $binanceRateValue")
        } catch (e: Exception) {
            Log.e("CurrencyWorker", "Error fetching Binance rate: ${e.message}", e)
        }

        if (CurrencyDataRepository.hasApiKey()) {
            try {
                satoshiRateValue = fetchSatoshiRate()
                Log.d("CurrencyWorker", "Satoshi Rate: $satoshiRateValue")
            } catch (e: Exception) {
                Log.e("CurrencyWorker", "Error fetching Satoshi rate: ${e.message}", e)
            }
        } else {
            Log.i("CurrencyWorker", "API key not found. Skipping Satoshi rate fetch.")
        }

        val currentRates = CurrencyRates(
            bcvRate = bcvRateValue?.let { decimalFormat.format(it) },
            euroRate = euroRateValue?.let { decimalFormat.format(it) }, // Pass euroRate
            binanceRate = binanceRateValue?.let { decimalFormat.format(it) },
            satoshiRate = satoshiRateValue?.let { decimalFormat.format(it) }
        )

        CurrencyDataRepository.updateRates(currentRates)
        Log.d("CurrencyWorker", "Updated CurrencyDataRepository with new rates.")

        // Update the widget UI - now it can use currentRates directly if desired
        updateAllWidgets(
            currentRates.bcvRate,
            currentRates.euroRate, // Use from currentRates
            currentRates.binanceRate,
            currentRates.satoshiRate
        )

        return Result.success()
    }

    private fun updateAllWidgets(bcvFormatted: String?, euroFormatted: String?, binanceFormatted: String?, satoshiFormatted: String?) {
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val componentName = ComponentName(applicationContext, RatesVeProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        if (appWidgetIds.isEmpty()) {
            Log.d("CurrencyWorker", "No widgets to update.")
            return
        }
        Log.d("CurrencyWorker", "Updating ${appWidgetIds.size} widget(s).")

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(applicationContext.packageName, R.layout.rates_ve_layout)
            
            views.setTextViewText(R.id.bcv_value, bcvFormatted ?: "N/A")
            views.setViewVisibility(R.id.bcv_layout, if (bcvFormatted == null) View.GONE else View.VISIBLE)

            views.setTextViewText(R.id.euro_value, euroFormatted ?: "N/A")
            views.setViewVisibility(R.id.euro_layout, if (euroFormatted == null) View.GONE else View.VISIBLE)

            views.setTextViewText(R.id.binance_value, binanceFormatted ?: "N/A")
            views.setViewVisibility(R.id.binance_layout, if (binanceFormatted == null) View.GONE else View.VISIBLE)

            views.setTextViewText(R.id.satoshi_value, satoshiFormatted ?: "N/A")
            views.setViewVisibility(R.id.satoshi_layout, if (satoshiFormatted == null) View.GONE else View.VISIBLE)


            val intent = Intent(applicationContext, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(applicationContext, appWidgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root_layout, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun fetchBcvRates(): Array<Double?> {
        val request = Request.Builder()
            .url("https://www.bcv.org.ve")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val html = response.body?.string() ?: return arrayOf(null, null)

            val doc = Jsoup.parse(html)
            // Verify these selectors if BCV site changes
            val dollarElement = doc.selectFirst("#dolar strong")
            val euroElement = doc.selectFirst("#euro strong")

            val bcvText = dollarElement?.text()?.trim()
            val euroText = euroElement?.text()?.trim()
            
            Log.d("CurrencyWorker", "BCV HTML Scrape - Dollar Text: '$bcvText', Euro Text: '$euroText'")

            return arrayOf(
                bcvText?.replace(",", ".")?.toDoubleOrNull(),
                euroText?.replace(",", ".")?.toDoubleOrNull()
            )
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
            if (!response.isSuccessful) throw IOException("Unexpected code $response for Binance")

            val responseBody = if ("gzip".equals(response.header("Content-Encoding"), ignoreCase = true)) {
                response.body?.source()?.let { source ->
                    GzipSource(source).buffer().readUtf8()
                }
            } else {
                response.body?.string()
            } ?: return null
            
            Log.d("CurrencyWorker", "Binance Response: $responseBody")

            val binanceResponse = gson.fromJson(responseBody, BinanceResponse::class.java)
            return binanceResponse.data?.firstOrNull()?.adv?.price?.toDoubleOrNull()
        }
    }

    private fun fetchSatoshiRate(): Double? {
        val apiKey = CurrencyDataRepository.getApiKey()
        if (apiKey.isNullOrEmpty()) {
            Log.i("CurrencyWorker", "CoinMarketCap API key not found. Skipping Satoshi rate fetch.")
            return null
        }

        val request = Request.Builder()
            .url("https://pro-api.coinmarketcap.com/v2/cryptocurrency/quotes/latest?id=1") // BTC ID is 1
            .addHeader("X-CMC_PRO_API_KEY", apiKey)
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e("CurrencyWorker", "CoinMarketCap API Error: ${response.code} ${response.message}. Body: $errorBody")
                throw IOException("Unexpected code ${response.code} from CoinMarketCap. Body: $errorBody")
            }
            val json = response.body?.string() ?: return null
            Log.d("CurrencyWorker", "CoinMarketCap Response: $json")

            val satoshiResponse = gson.fromJson(json, SatoshiResponse::class.java)
            val btcPriceUsd = satoshiResponse.data?.get("1")?.quote?.usd?.price
            return if (btcPriceUsd != null && btcPriceUsd > 0) {
                 (100_000_000.0 / btcPriceUsd)  // Satoshis per USD.
            } else {
                null
            }
        }
    }

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
}
