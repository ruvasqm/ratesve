package com.example.ratesve

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat // For notification accent color
import androidx.lifecycle.Observer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.createBitmap

class RateNotificationService : Service() {

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "RateNotificationServiceChannel"
    private lateinit var notificationManager: NotificationManager

    private val ratesObserver = Observer<CurrencyRates?> { rates ->
        Log.d("RateNotificationService", "Received rates update: $rates")
        updateNotificationContent(rates)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("RateNotificationService", "onCreate called")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        CurrencyDataRepository.ratesLiveData.observeForever(ratesObserver)
        Log.d("RateNotificationService", "Started observing ratesLiveData.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("RateNotificationService", "onStartCommand called")
        CurrencyDataRepository.initialize(applicationContext)
        CurrencyDataRepository.loadInitialRates()

        val initialRates = CurrencyDataRepository.ratesLiveData.value
        startForeground(NOTIFICATION_ID, createNotification(initialRates))
        Log.d("RateNotificationService", "Service started in foreground. Initial rates: $initialRates")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Current Exchange Rates",
                NotificationManager.IMPORTANCE_HIGH // Low importance to be less intrusive
            ).apply {
                description = "Displays current exchange rates in a persistent notification."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false) // No badge for this type of notification
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(serviceChannel)
            Log.d("RateNotificationService", "Notification channel created/updated.")
        }
    }

    private fun createNotification(rates: CurrencyRates?): Notification {
        val notificationLayout = RemoteViews(packageName, R.layout.notification_horizontal_rates)

        // BCV Rate
        notificationLayout.setTextViewText(R.id.notif_bcv_value, rates?.bcvRate ?: "N/A")
        notificationLayout.setViewVisibility(R.id.notif_bcv_layout, if (rates?.bcvRate != null) View.VISIBLE else View.GONE)

        // Euro Rate
        notificationLayout.setTextViewText(R.id.notif_euro_value, rates?.euroRate ?: "N/A")
        notificationLayout.setViewVisibility(R.id.notif_euro_layout, if (rates?.euroRate != null) View.VISIBLE else View.GONE)

        // Binance Rate
        notificationLayout.setTextViewText(R.id.notif_binance_value, rates?.binanceRate ?: "N/A")
        notificationLayout.setViewVisibility(R.id.notif_binance_layout, if (rates?.binanceRate != null) View.VISIBLE else View.GONE)

        val notificationIntent = Intent(this, MainActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // IMPORTANT: Replace R.drawable.ic_ved with a proper small, monochrome notification icon
        // This icon is for the status bar. DecoratedCustomViewStyle might hide the left-side app icon.
        val smallIconResId = R.drawable.ic_ved // <<< YOU MUST REPLACE THIS!

        val textColorRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android S and above, rely on ?android:attr/textColorPrimary in XML
            // However, RemoteViews might not always honor this directly for day/night.
            // We can explicitly set it if issues persist.
            // For now, assume XML handles it, but keep this logic for potential future use.
            if ((this.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                R.color.widget_text_color_night
            } else {
                R.color.widget_text_color_day
            }
        } else {
            // For older versions, explicitly set colors
            if ((this.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                R.color.widget_text_color_night
            } else {
                R.color.widget_text_color_day
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconResId) // Status bar icon
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setCustomContentView(notificationLayout)
            .setCustomBigContentView(notificationLayout)
            .setOngoing(true)
            .setSilent(true)
            .setColor( ContextCompat.getColor(this, textColorRes)) // Optional: Set accent color
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotificationContent(rates: CurrencyRates?) {
        Log.d("RateNotificationService", "Updating notification content with rates: $rates")
        notificationManager.notify(NOTIFICATION_ID, createNotification(rates))
    }

    override fun onDestroy() {
        super.onDestroy()
        CurrencyDataRepository.ratesLiveData.removeObserver(ratesObserver)
        Log.d("RateNotificationService", "onDestroy called, observer removed.")
    }
}
