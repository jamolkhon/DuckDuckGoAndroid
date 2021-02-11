/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.browser.logindetection

import androidx.lifecycle.MutableLiveData
import com.duckduckgo.app.fire.fireproofwebsite.data.FireproofWebsiteEntity
import com.duckduckgo.app.fire.fireproofwebsite.data.FireproofWebsiteRepository
import com.duckduckgo.app.global.events.db.UserEventKey
import com.duckduckgo.app.global.events.db.UserEventsStore
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.app.statistics.VariantManager
import com.duckduckgo.app.statistics.loginDetectionExperimentEnabled
import com.duckduckgo.app.statistics.pixels.Pixel

class FireproofDialogsEventHandler constructor(
    private val userEventsStore: UserEventsStore,
    private val pixel: Pixel,
    private val fireproofWebsiteRepository: FireproofWebsiteRepository,
    private val appSettingsPreferencesStore: SettingsDataStore,
    private val variantManager: VariantManager
) {

    sealed class Event {
        data class FireproofWebSiteSuccess(val fireproofWebsiteEntity: FireproofWebsiteEntity) : Event()
        object AskToDisableLoginDetection : Event()
    }

    var event: MutableLiveData<Event> = MutableLiveData()

    suspend fun onFireproofLoginDialogShown() {
        pixel.fire(
            pixel = Pixel.PixelName.FIREPROOF_LOGIN_DIALOG_SHOWN,
            parameters = mapOf(Pixel.PixelParameter.FIRE_EXECUTED to userTriedFireButton().toString())
        )
    }

    suspend fun onUserConfirmedFireproofDialog(domain: String) {
        userEventsStore.removeUserEvent(UserEventKey.FIREPROOF_LOGIN_DIALOG_DISMISSED)
        fireproofWebsiteRepository.fireproofWebsite(domain)?.let {
            pixel.fire(Pixel.PixelName.FIREPROOF_WEBSITE_LOGIN_ADDED, mapOf(Pixel.PixelParameter.FIRE_EXECUTED to userTriedFireButton().toString()))
            event.value = Event.FireproofWebSiteSuccess(fireproofWebsiteEntity = it)
        }
    }

    suspend fun onUserDismissedFireproofLoginDialog() {
        pixel.fire(Pixel.PixelName.FIREPROOF_WEBSITE_LOGIN_DISMISS, mapOf(Pixel.PixelParameter.FIRE_EXECUTED to userTriedFireButton().toString()))
        if (allowUserToDisableFireproofLoginActive()) {
            if (shouldAskToDisableFireproofLogin()) {
                event.value = Event.AskToDisableLoginDetection
            } else {
                userEventsStore.registerUserEvent(UserEventKey.FIREPROOF_LOGIN_DIALOG_DISMISSED)
            }
        }
    }

    suspend fun onDisableLoginDetectionDialogShown() {
        pixel.enqueueFire(
            Pixel.PixelName.FIREPROOF_LOGIN_DISABLE_DIALOG_SHOWN,
            mapOf(Pixel.PixelParameter.FIRE_EXECUTED to userTriedFireButton().toString())
        )
    }

    suspend fun onUserConfirmedDisableLoginDetectionDialog() {
        appSettingsPreferencesStore.appLoginDetection = false
        pixel.enqueueFire(
            Pixel.PixelName.FIREPROOF_LOGIN_DISABLE_DIALOG_DISABLE,
            mapOf(Pixel.PixelParameter.FIRE_EXECUTED to userTriedFireButton().toString())
        )
    }

    suspend fun onUserDismissedDisableLoginDetectionDialog() {
        appSettingsPreferencesStore.appLoginDetection = true
        userEventsStore.removeUserEvent(UserEventKey.FIREPROOF_LOGIN_DIALOG_DISMISSED)
        userEventsStore.registerUserEvent(UserEventKey.FIREPROOF_DISABLE_DIALOG_DISMISSED)
        pixel.enqueueFire(Pixel.PixelName.FIREPROOF_LOGIN_DISABLE_DIALOG_CANCEL, mapOf(Pixel.PixelParameter.FIRE_EXECUTED to userTriedFireButton().toString()))
    }

    private suspend fun userTriedFireButton() = userEventsStore.getUserEvent(UserEventKey.FIRE_BUTTON_EXECUTED) != null

    @Suppress("UnnecessaryVariable")
    private suspend fun allowUserToDisableFireproofLoginActive(): Boolean {
        if (!variantManager.loginDetectionExperimentEnabled()) return false

        val userEnabledLoginDetection = userEventsStore.getUserEvent(UserEventKey.USER_ENABLED_FIREPROOF_LOGIN) != null
        val userDismissedDisableFireproofLoginDialog = userEventsStore.getUserEvent(UserEventKey.FIREPROOF_DISABLE_DIALOG_DISMISSED) != null
        if (userEnabledLoginDetection || userDismissedDisableFireproofLoginDialog) return false

        return true
    }

    @Suppress("UnnecessaryVariable")
    private suspend fun shouldAskToDisableFireproofLogin(): Boolean {
        val userDismissedDialogBefore = userEventsStore.getUserEvent(UserEventKey.FIREPROOF_LOGIN_DIALOG_DISMISSED) != null
        return userDismissedDialogBefore
    }
}
