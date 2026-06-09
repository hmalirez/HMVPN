package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityCustomUserLoginBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.CustomSubscriptionHelper
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.TestServiceMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity for custom user login/registration on first launch
 */
class CustomUserLoginActivity : HelperBaseActivity() {
    private val binding by lazy {
        ActivityCustomUserLoginBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_custom_user_login))

        binding.btnSignUp.setOnClickListener {
            showUsernameDialog()
        }

        binding.btnFree.setOnClickListener {
            initializeFreeMode()
        }
    }

    private fun showUsernameDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.hint_enter_username)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.title_enter_username)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val username = input.text.toString().trim()
                if (username.isEmpty()) {
                    toast(R.string.toast_username_empty)
                } else {
                    initializeCustomMode(username)
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun initializeCustomMode(username: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setMessage(R.string.toast_validating)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!CustomSubscriptionHelper.isUsernameValid(username)) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        toast(R.string.toast_username_invalid)
                    }
                    return@launch
                }

                CustomSubscriptionHelper.setCustomUsername(username)
                val subId = CustomSubscriptionHelper.initializeCustomSubscription(username)
                val updateSuccess = CustomSubscriptionHelper.updateCustomSubscription(subId)
                MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subId)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (updateSuccess) {
                        toast(R.string.toast_success)
                        startAutoPingAndSort(subId)
                    } else {
                        toast(R.string.toast_failure)
                        navigateToMain()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    toast(R.string.toast_failure)
                }
            }
        }
    }

    private fun initializeFreeMode() {
        // Show loading
        val progressDialog = AlertDialog.Builder(this)
            .setMessage(R.string.toast_loading)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Set free mode
                CustomSubscriptionHelper.switchToFreeMode()
                
                // Initialize free subscription
                val freeSubId = CustomSubscriptionHelper.initializeFreeSubscription()
                
                // Update configs from free subscription
                val updateSuccess = CustomSubscriptionHelper.updateCustomSubscription(freeSubId)
                
                // Set the subscription as the current one
                MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, freeSubId)
                
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (updateSuccess) {
                        toast(R.string.toast_success)
                        // Trigger auto-ping and sort
                        startAutoPingAndSort(freeSubId)
                    } else {
                        toast(R.string.toast_failure)
                        navigateToMain()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    toast(R.string.toast_failure)
                    e.printStackTrace()
                }
            }
        }
    }

    private fun startAutoPingAndSort(subId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val serverGuids = MmkvManager.decodeServerList(subId)

                if (serverGuids.isNotEmpty()) {
                    MmkvManager.clearAllTestDelayResults(serverGuids)

                    MessageUtil.sendMsg2TestService(
                        this@CustomUserLoginActivity,
                        TestServiceMessage(
                            key = AppConfig.MSG_MEASURE_CONFIG_START,
                            subscriptionId = subId,
                            serverGuids = serverGuids
                        )
                    )

                    delay(35000)

                    val serverDelays = mutableListOf<Pair<String, Long>>()
                    serverGuids.forEach { key ->
                        val delay = MmkvManager.decodeServerAffiliationInfo(key)?.testDelayMillis ?: 0L
                        serverDelays.add(Pair(key, if (delay <= 0L) 999999L else delay))
                    }
                    serverDelays.sortBy { it.second }

                    val sortedServerList = serverDelays.map { it.first }.toMutableList()
                    MmkvManager.encodeServerList(sortedServerList, subId)
                    MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subId)
                }

                withContext(Dispatchers.Main) {
                    SettingsChangeManager.makeSetupGroupTab()
                    navigateToMain()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    navigateToMain()
                }
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
