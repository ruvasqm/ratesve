package com.example.ratesve

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class RatesVeProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        scheduleCurrencyFetchWork(context)
    }

    override fun onEnabled(context: Context) {
        scheduleCurrencyFetchWork(context)
        Log.d("WidgetProvider", "Widget enabled, work scheduled.")
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CurrencyFetchWorker.WORK_NAME)
        Log.d("WidgetProvider", "Last widget disabled, work cancelled.")
    }

    companion object {
        internal fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.rates_ve_layout)

            // Create an Intent to launch MainActivity
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root_layout, pendingIntent)

            // Set themed background
            views.setInt(R.id.widget_root_layout, "setBackgroundResource", R.drawable.widget_background_themed)

            // Set themed text colors
            val textColorRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android S and above, rely on ?android:attr/textColorPrimary in XML
                // However, RemoteViews might not always honor this directly for day/night.
                // We can explicitly set it if issues persist.
                // For now, assume XML handles it, but keep this logic for potential future use.
                if ((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                    R.color.widget_text_color_night
                } else {
                    R.color.widget_text_color_day
                }
            } else {
                 // For older versions, explicitly set colors
                if ((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                    R.color.widget_text_color_night
                } else {
                    R.color.widget_text_color_day
                }
            }
            val textColor = ContextCompat.getColor(context, textColorRes)
            views.setTextColor(R.id.bcv_value, textColor)
            views.setTextColor(R.id.binance_value, textColor)
            views.setTextColor(R.id.satoshi_value, textColor)


            val prefs = context.getSharedPreferences("CurrencyWidget", Context.MODE_PRIVATE)
            val bcv = prefs.getString("bcv_value", "N/A")
            val binance = prefs.getString("binance_value", "N/A")
            val satoshi = prefs.getString("satoshi_value", "N/A")

            // BCV Rate
            if (bcv == "N/A") {
                views.setViewVisibility(R.id.bcv_layout, View.GONE)
            } else {
                views.setViewVisibility(R.id.bcv_layout, View.VISIBLE)
                views.setTextViewText(R.id.bcv_value, bcv)
            }

            // Binance Rate
            if (binance == "N/A") {
                views.setViewVisibility(R.id.binance_layout, View.GONE)
            } else {
                views.setViewVisibility(R.id.binance_layout, View.VISIBLE)
                views.setTextViewText(R.id.binance_value, binance)
            }

            // Satoshi Rate
            if (satoshi == "N/A") {
                views.setViewVisibility(R.id.satoshi_layout, View.GONE)
            } else {
                views.setViewVisibility(R.id.satoshi_layout, View.VISIBLE)
                views.setTextViewText(R.id.satoshi_value, satoshi)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d("WidgetProvider", "Widget $appWidgetId updated.")
        }

        private fun scheduleCurrencyFetchWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val currencyFetchRequest =
                PeriodicWorkRequestBuilder<CurrencyFetchWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                CurrencyFetchWorker.WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                currencyFetchRequest
            )
        }
    }
}