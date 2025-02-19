/*
 * Copyright (c) 2022 DuckDuckGo
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

package com.duckduckgo.app.sitepermissions.permissionsperwebsite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckduckgo.anvil.annotations.ContributesViewModel
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.location.data.LocationPermissionEntity
import com.duckduckgo.app.location.data.LocationPermissionType
import com.duckduckgo.app.location.data.LocationPermissionsRepository
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.app.sitepermissions.permissionsperwebsite.PermissionsPerWebsiteViewModel.Command.GoBackToSitePermissions
import com.duckduckgo.app.sitepermissions.permissionsperwebsite.PermissionsPerWebsiteViewModel.Command.ShowPermissionSettingSelectionDialog
import com.duckduckgo.app.sitepermissions.permissionsperwebsite.WebsitePermissionSettingType.ALLOW
import com.duckduckgo.app.sitepermissions.permissionsperwebsite.WebsitePermissionSettingType.ASK
import com.duckduckgo.app.sitepermissions.permissionsperwebsite.WebsitePermissionSettingType.ASK_DISABLED
import com.duckduckgo.app.sitepermissions.permissionsperwebsite.WebsitePermissionSettingType.DENY
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.site.permissions.impl.SitePermissionsRepository
import com.duckduckgo.site.permissions.store.sitepermissions.SitePermissionsEntity
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@ContributesViewModel(ActivityScope::class)
class PermissionsPerWebsiteViewModel @Inject constructor(
    private val sitePermissionsRepository: SitePermissionsRepository,
    private val locationPermissionsRepository: LocationPermissionsRepository,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    private val _viewState = MutableStateFlow(ViewState())
    val viewState: StateFlow<ViewState> = _viewState

    private val _commands = Channel<Command>()
    val commands: Flow<Command> = _commands.receiveAsFlow()

    data class ViewState(
        val websitePermissions: List<WebsitePermissionSetting> = listOf(),
    )

    sealed class Command {
        class ShowPermissionSettingSelectionDialog(val setting: WebsitePermissionSetting) : Command()
        object GoBackToSitePermissions : Command()
    }

    fun websitePermissionSettings(url: String) {
        viewModelScope.launch {
            val websitePermissionsSettings = sitePermissionsRepository.getSitePermissionsForWebsite(url)
            val locationSetting = locationPermissionsRepository.getDomainPermission(url)
            val websitePermissions = convertToWebsitePermissionSettings(websitePermissionsSettings, locationSetting)

            _viewState.value = _viewState.value.copy(websitePermissions = websitePermissions)
        }
    }

    private fun convertToWebsitePermissionSettings(
        sitePermissionsEntity: SitePermissionsEntity?,
        locationPermissionEntity: LocationPermissionEntity?,
    ): List<WebsitePermissionSetting> {
        var locationSetting = WebsitePermissionSettingType.mapToWebsitePermissionSetting(locationPermissionEntity?.permission?.name)
        if (locationSetting == ASK && !settingsDataStore.appLocationPermission) {
            locationSetting = ASK_DISABLED
        }

        var cameraSetting = WebsitePermissionSettingType.mapToWebsitePermissionSetting(sitePermissionsEntity?.askCameraSetting)
        if (cameraSetting == ASK && !sitePermissionsRepository.askCameraEnabled) {
            cameraSetting = ASK_DISABLED
        }

        var micSetting = WebsitePermissionSettingType.mapToWebsitePermissionSetting(sitePermissionsEntity?.askMicSetting)
        if (micSetting == ASK && !sitePermissionsRepository.askMicEnabled) {
            micSetting = ASK_DISABLED
        }

        return getSettingsList(locationSetting, cameraSetting, micSetting)
    }

    private fun getSettingsList(
        locationSetting: WebsitePermissionSettingType,
        cameraSetting: WebsitePermissionSettingType,
        micSetting: WebsitePermissionSettingType,
    ): List<WebsitePermissionSetting> {
        return listOf(
            WebsitePermissionSetting(
                R.drawable.ic_location,
                R.string.sitePermissionsSettingsLocation,
                locationSetting,
            ),
            WebsitePermissionSetting(
                R.drawable.ic_camera,
                R.string.sitePermissionsSettingsCamera,
                cameraSetting,
            ),
            WebsitePermissionSetting(
                R.drawable.ic_microphone,
                R.string.sitePermissionsSettingsMicrophone,
                micSetting,
            ),
        )
    }

    fun permissionSettingSelected(setting: WebsitePermissionSetting) {
        viewModelScope.launch {
            _commands.send(ShowPermissionSettingSelectionDialog(setting))
        }
    }

    fun removeWebsitePermissionsSettings(url: String) {
        viewModelScope.launch {
            sitePermissionsRepository.deletePermissionsForSite(url)
            _commands.send(GoBackToSitePermissions)
        }
    }

    fun onPermissionSettingSelected(editedPermissionSetting: WebsitePermissionSetting, url: String) {
        var askLocationSetting = viewState.value.websitePermissions[0].setting
        var askCameraSetting = viewState.value.websitePermissions[1].setting
        var askMicSetting = viewState.value.websitePermissions[2].setting

        when (editedPermissionSetting.title) {
            R.string.sitePermissionsSettingsLocation -> {
                askLocationSetting = when (editedPermissionSetting.setting == ASK && !settingsDataStore.appLocationPermission) {
                    true -> ASK_DISABLED
                    false -> editedPermissionSetting.setting
                }
                updateLocationSetting(editedPermissionSetting.setting, url)
            }
            R.string.sitePermissionsSettingsCamera -> {
                askCameraSetting = when (editedPermissionSetting.setting == ASK && !sitePermissionsRepository.askCameraEnabled) {
                    true -> ASK_DISABLED
                    false -> editedPermissionSetting.setting
                }
                updateSitePermissionsSetting(askCameraSetting, askMicSetting, url)
            }
            R.string.sitePermissionsSettingsMicrophone -> {
                askMicSetting = when (editedPermissionSetting.setting == ASK && !sitePermissionsRepository.askMicEnabled) {
                    true -> ASK_DISABLED
                    false -> editedPermissionSetting.setting
                }
                updateSitePermissionsSetting(askCameraSetting, askMicSetting, url)
            }
        }

        _viewState.value = _viewState.value.copy(websitePermissions = getSettingsList(askLocationSetting, askCameraSetting, askMicSetting))
    }

    private fun updateLocationSetting(locationSetting: WebsitePermissionSettingType, url: String) {
        val locationPermissionType = when (locationSetting) {
            ASK, ASK_DISABLED -> LocationPermissionType.ALLOW_ONCE
            DENY -> LocationPermissionType.DENY_ALWAYS
            ALLOW -> LocationPermissionType.ALLOW_ALWAYS
        }
        viewModelScope.launch {
            locationPermissionsRepository.savePermission(url, locationPermissionType)
        }
    }

    private fun updateSitePermissionsSetting(
        askCameraSetting: WebsitePermissionSettingType,
        askMicSetting: WebsitePermissionSettingType,
        url: String,
    ) {
        val sitePermissionsEntity = SitePermissionsEntity(
            domain = url,
            askCameraSetting = askCameraSetting.toSitePermissionSettingEntityType().name,
            askMicSetting = askMicSetting.toSitePermissionSettingEntityType().name,
        )
        viewModelScope.launch {
            sitePermissionsRepository.savePermission(sitePermissionsEntity)
        }
    }
}
