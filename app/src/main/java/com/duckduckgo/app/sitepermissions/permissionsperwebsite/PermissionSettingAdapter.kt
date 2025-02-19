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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.ItemSitePermissionSettingSelectionBinding
import com.duckduckgo.app.sitepermissions.permissionsperwebsite.PermissionSettingAdapter.ViewHolder
import com.duckduckgo.site.permissions.store.sitepermissions.SitePermissionAskSettingType
import java.io.Serializable

class PermissionSettingAdapter(private val viewModel: PermissionsPerWebsiteViewModel) : RecyclerView.Adapter<ViewHolder>() {

    private var items: List<WebsitePermissionSetting> = listOf()

    fun updateItems(settings: List<WebsitePermissionSetting>) {
        items = settings
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder =
        ViewHolder(
            ItemSitePermissionSettingSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            viewModel,
        )

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemSitePermissionSettingSelectionBinding,
        private val viewModel: PermissionsPerWebsiteViewModel,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(setting: WebsitePermissionSetting) {
            binding.permissionSettingIcon.setImageResource(setting.icon)
            binding.permissionSettingSelectionTitle.setText(setting.title)
            binding.permissionSetting.setText(setting.setting.toPrettyStringRes())
            binding.root.setOnClickListener {
                viewModel.permissionSettingSelected(setting)
            }
        }
    }
}

data class WebsitePermissionSetting(
    val icon: Int,
    val title: Int,
    val setting: WebsitePermissionSettingType,
) : Serializable

enum class WebsitePermissionSettingType {
    ASK,
    ASK_DISABLED,
    ALLOW,
    DENY,
    ;

    fun toPrettyStringRes(): Int =
        when (this) {
            ASK -> R.string.permissionsPerWebsiteAskSetting
            ASK_DISABLED -> R.string.permissionsPerWebsiteAskDisabledSetting
            ALLOW -> R.string.permissionsPerWebsiteAllowSetting
            DENY -> R.string.permissionsPerWebsiteDenySetting
        }

    fun toSitePermissionSettingEntityType(): SitePermissionAskSettingType =
        when (this) {
            ASK, ASK_DISABLED -> SitePermissionAskSettingType.ASK_EVERY_TIME
            ALLOW -> SitePermissionAskSettingType.ALLOW_ALWAYS
            DENY -> SitePermissionAskSettingType.DENY_ALWAYS
        }

    companion object {
        fun mapToWebsitePermissionSetting(askSettingType: String?): WebsitePermissionSettingType =
            when (askSettingType) {
                SitePermissionAskSettingType.ALLOW_ALWAYS.name -> ALLOW
                SitePermissionAskSettingType.DENY_ALWAYS.name -> DENY
                else -> ASK
            }
    }
}
