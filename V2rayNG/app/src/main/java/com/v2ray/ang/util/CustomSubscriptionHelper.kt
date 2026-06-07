package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * Helper class for managing custom subscriptions with usernames
 */
object CustomSubscriptionHelper {

    /**
     * Gets the subscription URL for a given username
     * @param username The username to build the subscription URL for
     * @return The complete subscription URL
     */
    fun getSubscriptionUrl(username: String): String {
        return "${AppConfig.CUSTOM_SUBSCRIPTION_BASE_URL}/$username.txt"
    }

    /**
     * Gets the free subscription URL
     * @return The free subscription URL
     */
    fun getFreeSubscriptionUrl(): String {
        return "${AppConfig.CUSTOM_SUBSCRIPTION_BASE_URL}/free.txt"
    }

    /**
     * Checks if the app is using custom user mode
     * @return True if using custom user mode
     */
    fun isCustomUserMode(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_CUSTOM_USER, false)
    }

    /**
     * Gets the current custom username
     * @return The custom username, or empty string if not set
     */
    fun getCustomUsername(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_USERNAME).orEmpty()
    }

    /**
     * Sets the custom username and marks the app as using custom user mode
     * @param username The username to set
     */
    fun setCustomUsername(username: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_USERNAME, username)
        MmkvManager.encodeSettings(AppConfig.PREF_IS_CUSTOM_USER, true)
    }

    /**
     * Switches to free mode
     */
    fun switchToFreeMode() {
        MmkvManager.encodeSettings(AppConfig.PREF_IS_CUSTOM_USER, false)
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_USERNAME, "")
    }

    /**
     * Gets the free subscription ID
     * @return The free subscription ID
     */
    fun getFreeSubscriptionId(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_FREE_SUBSCRIPTION_ID) ?: "free"
    }

    /**
     * Sets the free subscription ID
     * @param id The subscription ID
     */
    fun setFreeSubscriptionId(id: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_FREE_SUBSCRIPTION_ID, id)
    }

    /**
     * Gets the paid subscription ID for the current username
     * @return The paid subscription ID
     */
    fun getPaidSubscriptionId(): String {
        val username = getCustomUsername()
        return MmkvManager.decodeSettingsString(AppConfig.PREF_PAID_SUBSCRIPTION_ID + "_$username") ?: username
    }

    /**
     * Sets the paid subscription ID
     * @param id The subscription ID
     */
    fun setPaidSubscriptionId(id: String) {
        val username = getCustomUsername()
        MmkvManager.encodeSettings(AppConfig.PREF_PAID_SUBSCRIPTION_ID + "_$username", id)
    }

    /**
     * Creates and initializes the free subscription
     * @return The subscription ID
     */
    fun initializeFreeSubscription(): String {
        val freeSubId = getFreeSubscriptionId()
        val existingSubscription = MmkvManager.decodeSubscription(freeSubId)
        
        if (existingSubscription == null) {
            val freeSubItem = SubscriptionItem(
                remarks = "رایگان",
                url = getFreeSubscriptionUrl(),
                enabled = true,
                autoUpdate = false
            )
            MmkvManager.encodeSubscription(freeSubId, freeSubItem)
        }
        
        return freeSubId
    }

    /**
     * Creates and initializes the custom user subscription
     * @param username The username to create subscription for
     * @return The subscription ID
     */
    fun initializeCustomSubscription(username: String): String {
        val subId = username
        val existingSubscription = MmkvManager.decodeSubscription(subId)
        
        if (existingSubscription == null) {
            val customSubItem = SubscriptionItem(
                remarks = username,
                url = getSubscriptionUrl(username),
                enabled = true,
                autoUpdate = false
            )
            MmkvManager.encodeSubscription(subId, customSubItem)
            setPaidSubscriptionId(subId)
        }
        
        return subId
    }

    /**
     * Updates and loads configs from the custom subscription
     * @param subId The subscription ID to update
     * @return True if update was successful
     */
    fun updateCustomSubscription(subId: String): Boolean {
        try {
            val subItem = MmkvManager.decodeSubscription(subId) ?: return false
            val result = AngConfigManager.updateConfigViaSub(
                com.v2ray.ang.dto.entities.SubscriptionCache(subId, subItem)
            )
            return result.configCount > 0
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update custom subscription", e)
            return false
        }
    }
}
