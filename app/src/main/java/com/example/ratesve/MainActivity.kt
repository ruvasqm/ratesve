package com.example.ratesve

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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

class MainActivity : AppCompatActivity() {

    private lateinit var encryptedPrefs: SharedPreferences
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private lateinit var tvApiKeyStatus: TextView
    private lateinit var etApiKey: EditText
    private lateinit var btnSaveApiKey: Button
    private lateinit var btnEditApiKey: Button
    private lateinit var btnManualRefresh: Button

    private var authAction: AuthAction? = null

    enum class AuthAction {
        EDIT_API_KEY,
        MANUAL_REFRESH
    }

    companion object {
        private const val API_KEY_ALIAS = "api_key"
        private const val PREFS_FILE_NAME = "secret_shared_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvApiKeyStatus = findViewById(R.id.tvApiKeyStatus)
        etApiKey = findViewById(R.id.apiKeyEditText)
        btnSaveApiKey = findViewById(R.id.saveApiKeyButton)
        btnEditApiKey = findViewById(R.id.btnEditApiKey)
        btnManualRefresh = findViewById(R.id.btnManualRefresh)
        val tvAppVersion: TextView = findViewById(R.id.tvAppVersion)
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        tvAppVersion.text = "Version: $versionName (Build: $versionCode) - pix"

        executor = ContextCompat.getMainExecutor(this)

        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            encryptedPrefs = EncryptedSharedPreferences.create(
                PREFS_FILE_NAME,
                masterKeyAlias,
                this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing EncryptedSharedPreferences", e)
            Toast.makeText(this, "Error setting up secure storage.", Toast.LENGTH_LONG).show()
            // Consider disabling features that require the API key
            updateUiForKeyState(false)
            return // Stop further setup if secure storage fails
        }

        setupBiometricPrompt()
        updateUiForKeyState(encryptedPrefs.contains(API_KEY_ALIAS))

        btnSaveApiKey.setOnClickListener {
            val apiKey = etApiKey.text.toString()
            if (apiKey.isNotBlank()) {
                try {
                    encryptedPrefs.edit { putString(API_KEY_ALIAS, apiKey) }
                    Toast.makeText(this, "API Key saved securely", Toast.LENGTH_SHORT).show()
                    etApiKey.text.clear()
                    updateUiForKeyState(true)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error saving API Key", e)
                    Toast.makeText(this, "Error saving API Key.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "API Key cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }

        btnEditApiKey.setOnClickListener {
            authAction = AuthAction.EDIT_API_KEY
            biometricPrompt.authenticate(promptInfo)
        }

        btnManualRefresh.setOnClickListener {
            if (encryptedPrefs.contains(API_KEY_ALIAS)) {
                authAction = AuthAction.MANUAL_REFRESH
                biometricPrompt.authenticate(promptInfo)
            } else {
                Toast.makeText(this, "API Key not set. Please set it first.", Toast.LENGTH_LONG).show()
                updateUiForKeyState(false) // Ensure UI guides user to set key
            }
        }
    }

    private fun setupBiometricPrompt() {
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    when (authAction) {
                        AuthAction.EDIT_API_KEY -> {
                            val savedApiKey = encryptedPrefs.getString(API_KEY_ALIAS, "")
                            etApiKey.setText(savedApiKey)
                            updateUiForKeyState(false) // Show input field for editing
                            Toast.makeText(applicationContext, "Edit your API Key.", Toast.LENGTH_SHORT).show()
                        }
                        AuthAction.MANUAL_REFRESH -> {
                            Toast.makeText(applicationContext, "Authentication successful. Fetching rates...", Toast.LENGTH_SHORT).show()
                            triggerManualRateUpdate()
                        }
                        null -> {
                            // Should not happen if authAction is always set before calling authenticate
                            Log.w("MainActivity", "AuthAction was null after authentication success.")
                        }
                    }
                    authAction = null // Reset action
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.e("MainActivity", "Authentication error: $errorCode - $errString")
                    Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                    authAction = null // Reset action
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w("MainActivity", "Authentication failed")
                    Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                    authAction = null // Reset action
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
            tvApiKeyStatus.text =
                getString(R.string.api_key_needed_for_satoshi_rate_please_enter_and_save_your_key)
            etApiKey.visibility = View.VISIBLE
            btnSaveApiKey.visibility = View.VISIBLE
            btnEditApiKey.visibility = View.GONE
        }
    }

    private fun triggerManualRateUpdate() {
        // Enqueue the CurrencyFetchWorker for a one-time update
        val workRequest = OneTimeWorkRequestBuilder<CurrencyFetchWorker>().build()
        WorkManager.getInstance(applicationContext).enqueue(workRequest)
        Toast.makeText(this, "Manual rates update triggered.", Toast.LENGTH_SHORT).show()
    }
}
