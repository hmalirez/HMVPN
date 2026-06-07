package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.net.HttpURLConnection
import java.net.URL

object CustomSubscriptionHelper {

    private const val MASTER_LIST_CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 hours in milliseconds

    fun getSubscriptionUrl(username: String): String {
        return "${AppConfig.CUSTOM_SUBSCRIPTION_BASE_URL}/$username.txt"
    }

    fun getFreeSubscriptionUrl(): String {
        return "${AppConfig.CUSTOM_SUBSCRIPTION_BASE_URL}/free.txt"
    }

    fun getMasterListUrl(): String {
        return AppConfig.CUSTOM_SUBSCRIPTION_MASTER_LIST_URL
    }

    fun isCustomUserMode(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_CUSTOM_USER, false)
    }

    fun getCustomUsername(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_USERNAME).orEmpty()
    }

    fun setCustomUsername(username: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_USERNAME, username)
        MmkvManager.encodeSettings(AppConfig.PREF_IS_CUSTOM_USER, true)
    }

    fun switchToFreeMode() {
        MmkvManager.encodeSettings(AppConfig.PREF_IS_CUSTOM_USER, false)
        MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_USERNAME, "")
    }

    fun getFreeSubscriptionId(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_FREE_SUBSCRIPTION_ID) ?: "free"
    }

    fun setFreeSubscriptionId(id: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_FREE_SUBSCRIPTION_ID, id)
    }

    fun getPaidSubscriptionId(): String {
        val username = getCustomUsername()
        return MmkvManager.decodeSettingsString(AppConfig.PREF_PAID_SUBSCRIPTION_ID + "_$username") ?: username
    }

    fun setPaidSubscriptionId(id: String) {
        val username = getCustomUsername()
        MmkvManager.encodeSettings(AppConfig.PREF_PAID_SUBSCRIPTION_ID + "_$username", id)
    }

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

    fun isUsernameValid(username: String): Boolean {
        return try {
            val cacheKey = "master_list_cache_${System.currentTimeMillis() / MASTER_LIST_CACHE_DURATION}"
            val cachedLines = MmkvManager.decodeSettingsString(cacheKey)
            val lines = if (!cachedLines.isNullOrEmpty()) {
                cachedLines.split("\n")
            } else {
                val masterListContent = fetchMasterList()
                if (masterListContent != null) {
                    MmkvManager.encodeSettings(cacheKey, masterListContent)
                    masterListContent.split("\n")
                } else {
                    return false
                }
            }

            val normalizedUsername = username.trim().lowercase()
            var foundUrl: String? = null
            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.contains("sub=$normalizedUsername", ignoreCase = true) ||
                    trimmedLine.contains("sub=$normalizedUsername&", ignoreCase = true)) {
                    foundUrl = trimmedLine
                    break
                }
            }

            if (foundUrl != null) {
                MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_USERNAME_URL, foundUrl)
            }

            foundUrl != null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to validate username: $username", e)
            false
        }
    }

    private fun fetchMasterList(): String? {
        return try {
            val url = URL(getMasterListUrl())
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to fetch master list", e)
            null
        }
    }

    fun initializeCustomSubscription(username: String): String {
        val subId = username
        val existingSubscription = MmkvManager.decodeSubscription(subId)

        if (existingSubscription == null) {
            val subscriptionUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_USERNAME_URL)
                ?: getSubscriptionUrl(username)

            val customSubItem = SubscriptionItem(
                remarks = username,
                url = subscriptionUrl,
                enabled = true,
                autoUpdate = false
            )
            MmkvManager.encodeSubscription(subId, customSubItem)
            setPaidSubscriptionId(subId)
        }

        return subId
    }

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
