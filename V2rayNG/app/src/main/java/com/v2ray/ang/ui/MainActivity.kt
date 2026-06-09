package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.SearchView
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
        
        // Check if it's the first launch with custom user mode
        val isFirstLaunch = !MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_BOOTED, false)
        if (isFirstLaunch) {
            // Mark that the app has been launched
            MmkvManager.encodeSettings(AppConfig.PREF_IS_BOOTED, true)
            // Show custom user login activity
            startActivity(Intent(this, CustomUserLoginActivity::class.java))
            finish()
            return
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

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }

        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()
        updateSubscriptionsOnOpen()

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

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
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
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.sub_update -> {
            importConfigViaSub()
            true
        }
        else -> super.onOptionsItemSelected(item)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return when (item.itemId) {
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