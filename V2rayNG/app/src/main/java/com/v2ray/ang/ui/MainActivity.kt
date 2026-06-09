package com.v2ray.ang.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.util.CustomSubscriptionHelper
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val isFirstLaunch = !MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_BOOTED, false)
        if (isFirstLaunch) {
            MmkvManager.encodeSettings(AppConfig.PREF_IS_BOOTED, true)
        }
        
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.layoutTest.setOnClickListener { handleStatusClick() }
        binding.ivUpdate.setOnClickListener { importConfigViaSub() }
        binding.ivPing.setOnClickListener { handlePingClick() }
        binding.ivConnect.setOnClickListener { handleFabAction() }

        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()
        updateSubscriptionsOnOpen()
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_PING_AFTER_LOAD)) {
            mainViewModel.testAllRealPing()
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = if (groups.isEmpty()) {
            0
        } else {
            groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        }
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleStatusClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op
        }
    }

    private fun handlePingClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            mainViewModel.testAllRealPing()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
        
        val icon = when {
            content == null -> R.drawable.ic_dot_disconnected
            content.startsWith(getString(R.string.status_connected)) ||
            content.startsWith(getString(R.string.connection_test_available).substring(0, 7)) ||
            content.startsWith("Ping ") ||
            content.startsWith("پینگ ") -> R.drawable.ic_dot_connected
            else -> R.drawable.ic_dot_disconnected
        }
        binding.ivStatusIcon.setImageResource(icon)
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.ivConnect.setImageResource(R.drawable.ic_heart_outline_white)
            return
        }

        if (isRunning) {
            binding.ivConnect.setImageResource(R.drawable.ic_heart_filled_white)
            binding.ivConnect.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.status_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.ivConnect.setImageResource(R.drawable.ic_heart_outline_white)
            binding.ivConnect.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.status_disconnected))
            binding.layoutTest.isFocusable = false
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return when (item.itemId) {
            R.id.login_with_username -> {
                showUsernameLoginDialog()
                true
            }
            R.id.login_as_guest -> {
                initializeFreeModeFromMenu()
                true
            }
            R.id.per_app_proxy_settings -> {
                requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
                true
            }
            R.id.routing_setting -> {
                requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
                true
            }
            else -> false
        }
    }

    private fun showUsernameLoginDialog() {
        val currentUsername = CustomSubscriptionHelper.getCustomUsername().takeIf { it.isNotBlank() }
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.hint_enter_username)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            if (!currentUsername.isNullOrBlank()) {
                setText(currentUsername)
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.title_enter_username)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val username = input.text.toString().trim()
                if (username.isEmpty()) {
                    toast(R.string.toast_username_empty)
                } else {
                    initializeCustomModeFromMenu(username)
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun initializeCustomModeFromMenu(username: String) {
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
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
                    toast(R.string.toast_success)
                    mainViewModel.reloadServerList()
                    setupGroupTab()
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_PING_AFTER_LOAD)) {
                        mainViewModel.testAllRealPing()
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

    private fun initializeFreeModeFromMenu() {
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.toast_loading)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                CustomSubscriptionHelper.switchToFreeMode()
                val freeSubId = CustomSubscriptionHelper.initializeFreeSubscription()
                val updateSuccess = CustomSubscriptionHelper.updateCustomSubscription(freeSubId)
                MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, freeSubId)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    toast(R.string.toast_success)
                    mainViewModel.reloadServerList()
                    setupGroupTab()
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_PING_AFTER_LOAD)) {
                        mainViewModel.testAllRealPing()
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

    internal fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    mainViewModel.testAllRealPing()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun updateSubscriptionsOnOpen() {
        lifecycleScope.launch(Dispatchers.IO) {
            val subscriptions = MmkvManager.decodeSubscriptions()
            var updatedAny = false
            for (sub in subscriptions) {
                val subId = sub.guid
                val subItem = MmkvManager.decodeSubscription(subId) ?: continue
                if (!subItem.enabled) continue
                if (subId == AppConfig.DEFAULT_SUBSCRIPTION_ID) continue
                if (subItem.url.isNullOrEmpty()) continue
                val result = AngConfigManager.updateConfigViaSub(com.v2ray.ang.dto.entities.SubscriptionCache(subId, subItem))
                if (result.configCount > 0) {
                    updatedAny = true
                }
            }
            launch(Dispatchers.Main) {
                if (updatedAny) {
                    mainViewModel.reloadServerList()
                }
            }
        }
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}
