package com.example.ratesve

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.Executor
import androidx.core.content.edit
import com.example.ratesve.R.string.api_key_is_set
import java.security.GeneralSecurityException // Added for specific exception handling

class MainActivity : AppCompatActivity() {

    private lateinit var encryptedPrefs: SharedPreferences
    private lateinit var regularPrefs: SharedPreferences
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private lateinit var tvApiKeyStatus: TextView
    private lateinit var etApiKey: EditText
    private lateinit var btnSaveApiKey: Button
    private lateinit var btnEditApiKey: Button
    private lateinit var btnManualRefresh: Button
    private lateinit var switchNotificationWidget: SwitchCompat

    private var authAction: AuthAction? = null

    enum class AuthAction {
        EDIT_API_KEY,
        MANUAL_REFRESH
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startRateNotificationService()
            } else {
                Toast.makeText(this, "Notification permission denied. Widget cannot be shown.", Toast.LENGTH_LONG).show()
                switchNotificationWidget.isChecked = false
                regularPrefs.edit { putBoolean(NOTIFICATION_WIDGET_ENABLED_KEY, false) }
            }
        }

    companion object {
        private const val API_KEY_ALIAS = "api_key"
        private const val PREFS_FILE_NAME = "secret_shared_prefs"
        private const val REGULAR_PREFS_FILE_NAME = "app_settings_prefs"
        private const val NOTIFICATION_WIDGET_ENABLED_KEY = "notification_widget_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvApiKeyStatus = findViewById(R.id.tvApiKeyStatus)
        etApiKey = findViewById(R.id.apiKeyEditText)
        btnSaveApiKey = findViewById(R.id.saveApiKeyButton)
        btnEditApiKey = findViewById(R.id.btnEditApiKey)
        btnManualRefresh = findViewById(R.id.btnManualRefresh)
        switchNotificationWidget = findViewById(R.id.switchNotificationWidget)
        val tvAppVersion: TextView = findViewById(R.id.tvAppVersion)
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        tvAppVersion.text = "Version: $versionName (Build: $versionCode) - pix"

        executor = ContextCompat.getMainExecutor(this)
        regularPrefs = getSharedPreferences(REGULAR_PREFS_FILE_NAME, Context.MODE_PRIVATE)

        initializeEncryptedPrefs()

        setupBiometricPrompt()

        if (::encryptedPrefs.isInitialized) {
            // Attempt a benign read to trigger decryption error if keys are mismatched
            try {
                encryptedPrefs.contains(API_KEY_ALIAS) // This might throw GeneralSecurityException
                updateUiForKeyState(encryptedPrefs.contains(API_KEY_ALIAS))
            } catch (e: GeneralSecurityException) {
                Log.e("MainActivity", "Failed to access EncryptedSharedPreferences (likely due to key mismatch). Clearing and re-initializing.", e)
                handleEncryptedPrefsInitializationError(e, true) // Force clear and re-init
                updateUiForKeyState(false) // Assume no key after error and reset
            }
        } else {
            updateUiForKeyState(false)
            Toast.makeText(this, "Secure storage unavailable. API key functionality disabled.", Toast.LENGTH_LONG).show()
        }
        setupNotificationWidgetToggle()

        if (switchNotificationWidget.isChecked) {
            handleNotificationToggle(true)
        }

        btnSaveApiKey.setOnClickListener {
            if (!::encryptedPrefs.isInitialized) {
                 Toast.makeText(this, "Secure storage unavailable. Cannot save API key.", Toast.LENGTH_SHORT).show()
                 return@setOnClickListener
            }
            val apiKey = etApiKey.text.toString()
            try {
                if (apiKey.isNotBlank()) {
                    encryptedPrefs.edit { putString(API_KEY_ALIAS, apiKey) }
                    Toast.makeText(this, "API Key saved securely", Toast.LENGTH_SHORT).show()
                    updateUiForKeyState(true)
                } else {
                    encryptedPrefs.edit { remove(API_KEY_ALIAS) }
                    Toast.makeText(this, "API Key cleared", Toast.LENGTH_SHORT).show()
                    updateUiForKeyState(false)
                }
                etApiKey.text.clear()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error saving API Key", e)
                Toast.makeText(this, "Error saving API Key.", Toast.LENGTH_SHORT).show()
            }
        }

        btnEditApiKey.setOnClickListener {
             if (!::encryptedPrefs.isInitialized) {
                 Toast.makeText(this, "Secure storage unavailable.", Toast.LENGTH_SHORT).show()
                 return@setOnClickListener
            }
            authAction = AuthAction.EDIT_API_KEY
            biometricPrompt.authenticate(promptInfo)
        }

        btnManualRefresh.setOnClickListener {
            authAction = AuthAction.MANUAL_REFRESH
            biometricPrompt.authenticate(promptInfo)
        }
    }

    private fun initializeEncryptedPrefs(forceClear: Boolean = false) {
        try {
            if (forceClear) {
                Log.i("MainActivity", "Forcing clear of EncryptedSharedPreferences: $PREFS_FILE_NAME")
                applicationContext.getSharedPreferences(PREFS_FILE_NAME, MODE_PRIVATE).edit().clear().apply()
            }

            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            encryptedPrefs = EncryptedSharedPreferences.create(
                PREFS_FILE_NAME,
                masterKeyAlias,
                this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
             // Test read after initialization
            encryptedPrefs.contains(API_KEY_ALIAS) // This line will trigger GeneralSecurityException if keys are bad

        } catch (e: GeneralSecurityException) {
            Log.e("MainActivity", "GeneralSecurityException during EncryptedSharedPreferences initialization.", e)
            handleEncryptedPrefsInitializationError(e, !forceClear) // If not already forced, try clearing now
        } catch (e: Exception) { // Catch other potential exceptions
            Log.e("MainActivity", "Generic Exception during EncryptedSharedPreferences initialization.", e)
            handleEncryptedPrefsInitializationError(e, !forceClear)
        }
    }

    private fun handleEncryptedPrefsInitializationError(e: Exception, tryReInitializing: Boolean) {
        Log.e("MainActivity", "Handling EncryptedSharedPreferences error. Attempting to clear and re-initialize.", e)
        Toast.makeText(this, "App data reset due to an issue. Please re-enter API key if you had one.", Toast.LENGTH_LONG).show()
        // Clear the problematic SharedPreferences file
        applicationContext.getSharedPreferences(PREFS_FILE_NAME, MODE_PRIVATE).edit().clear().apply()

        if (tryReInitializing) {
            initializeEncryptedPrefs(forceClear = true) // Attempt to re-initialize after clearing
        } else {
             Log.e("MainActivity", "Failed to re-initialize EncryptedSharedPreferences even after clearing.", e)
             Toast.makeText(this, "Critical error setting up secure storage. API key features disabled.", Toast.LENGTH_LONG).show()
        }
    }


    private fun setupBiometricPrompt() {
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    when (authAction) {
                        AuthAction.EDIT_API_KEY -> {
                            if (!::encryptedPrefs.isInitialized) {
                                 Toast.makeText(applicationContext, "Secure storage unavailable.", Toast.LENGTH_SHORT).show()
                                 authAction = null
                                 return
                            }
                            val savedApiKey = encryptedPrefs.getString(API_KEY_ALIAS, "")
                            etApiKey.setText(savedApiKey)
                            updateUiForKeyState(false)
                            Toast.makeText(applicationContext, "Edit your API Key.", Toast.LENGTH_SHORT).show()
                        }
                        AuthAction.MANUAL_REFRESH -> {
                            Toast.makeText(applicationContext, "Authentication successful. Fetching rates...", Toast.LENGTH_SHORT).show()
                            triggerManualRateUpdate()
                        }
                        null -> { /* No action */ }
                    }
                    authAction = null
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                    authAction = null
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                    authAction = null
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authenticate")
            .setSubtitle("Use your screen lock or biometrics to proceed")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
    }

     private fun updateUiForKeyState(keyExists: Boolean) {
        if (keyExists) {
            tvApiKeyStatus.text = getString(api_key_is_set)
            etApiKey.visibility = View.GONE
            btnSaveApiKey.visibility = View.GONE
            btnEditApiKey.visibility = View.VISIBLE
        } else {
            tvApiKeyStatus.text = getString(R.string.api_key_not_set_satoshi_optional)
            etApiKey.visibility = View.VISIBLE
            etApiKey.setHint(R.string.enter_coinmarketcap_api_key)
            btnSaveApiKey.visibility = View.VISIBLE
            btnEditApiKey.visibility = View.GONE
        }
    }

    private fun triggerManualRateUpdate() {
        val workRequest = OneTimeWorkRequestBuilder<CurrencyFetchWorker>().build()
        WorkManager.getInstance(applicationContext).enqueue(workRequest)
        Toast.makeText(this, "Manual rates update triggered.", Toast.LENGTH_SHORT).show()
    }

    private fun setupNotificationWidgetToggle() {
        switchNotificationWidget.isChecked = regularPrefs.getBoolean(NOTIFICATION_WIDGET_ENABLED_KEY, false)
        switchNotificationWidget.setOnCheckedChangeListener { _, isChecked ->
            regularPrefs.edit { putBoolean(NOTIFICATION_WIDGET_ENABLED_KEY, isChecked) }
            handleNotificationToggle(isChecked)
        }
    }

    private fun handleNotificationToggle(enable: Boolean) {
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                when {
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED -> {
                        startRateNotificationService()
                    }
                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                        Toast.makeText(this, "Notification permission is needed to show rates in notification.", Toast.LENGTH_LONG).show()
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            } else {
                startRateNotificationService()
            }
        } else {
            stopRateNotificationService()
        }
    }

    private fun startRateNotificationService() {
        val intent = Intent(this, RateNotificationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Rate notification service started.", Toast.LENGTH_SHORT).show()
    }

    private fun stopRateNotificationService() {
        val intent = Intent(this, RateNotificationService::class.java)
        stopService(intent)
        Toast.makeText(this, "Rate notification service stopped.", Toast.LENGTH_SHORT).show()
    }
}
