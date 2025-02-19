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

package com.duckduckgo.mobile.android.vpn.service.state

import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.mobile.android.vpn.VpnFeature
import com.duckduckgo.mobile.android.vpn.VpnFeaturesRegistry
import com.duckduckgo.mobile.android.vpn.model.AlwaysOnState
import com.duckduckgo.mobile.android.vpn.model.VpnServiceState.DISABLED
import com.duckduckgo.mobile.android.vpn.model.VpnServiceState.ENABLED
import com.duckduckgo.mobile.android.vpn.model.VpnServiceState.ENABLING
import com.duckduckgo.mobile.android.vpn.model.VpnServiceStateStats
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.ERROR
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.RESTART
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.REVOKED
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.SELF_STOP
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor.VpnRunningState
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor.VpnRunningState.INVALID
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor.VpnState
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor.VpnStopReason
import com.duckduckgo.mobile.android.vpn.store.VpnDatabase
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import logcat.logcat

@ContributesBinding(AppScope::class)
class RealVpnStateMonitor @Inject constructor(
    private val database: VpnDatabase,
    private val vpnFeaturesRegistry: VpnFeaturesRegistry,
    private val dispatcherProvider: DispatcherProvider,
) : VpnStateMonitor {

    override fun getStateFlow(vpnFeature: VpnFeature): Flow<VpnState> {
        return database.vpnServiceStateDao().getStateStats().map { mapState(it) }
            .filter {
                // we only care about the following states
                (it.state == VpnRunningState.ENABLED) || (it.state == VpnRunningState.ENABLING) || (it.state == VpnRunningState.DISABLED)
            }
            .onEach { logcat { "service state value $it" } }
            .map { vpnState ->
                val isFeatureEnabled = vpnFeaturesRegistry.isFeatureRegistered(vpnFeature)

                if (!isFeatureEnabled) {
                    vpnState.copy(state = VpnRunningState.DISABLED)
                } else {
                    vpnState
                }
            }
            .onStart {
                val vpnState = mapState(database.vpnServiceStateDao().getLastStateStats())
                VpnState(
                    state = if (vpnFeaturesRegistry.isFeatureRegistered(vpnFeature)) VpnRunningState.ENABLED else VpnRunningState.DISABLED,
                    alwaysOnState = vpnState.alwaysOnState,
                    stopReason = vpnState.stopReason,
                ).also { emit(it) }
            }.flowOn(dispatcherProvider.io())
            .distinctUntilChanged()
    }

    private fun mapState(lastState: VpnServiceStateStats?): VpnState {
        val stoppingReason = when (lastState?.stopReason) {
            RESTART -> VpnStopReason.RESTART
            SELF_STOP -> VpnStopReason.SELF_STOP
            REVOKED -> VpnStopReason.REVOKED
            ERROR -> VpnStopReason.ERROR
            else -> VpnStopReason.UNKNOWN
        }
        val runningState = when (lastState?.state) {
            ENABLING -> VpnRunningState.ENABLING
            ENABLED -> VpnRunningState.ENABLED
            DISABLED -> VpnRunningState.DISABLED
            else -> VpnRunningState.INVALID
        }
        val alwaysOnState = when (lastState?.alwaysOnState) {
            AlwaysOnState.ALWAYS_ON_ENABLED -> VpnStateMonitor.AlwaysOnState.ALWAYS_ON_ENABLED
            AlwaysOnState.ALWAYS_ON_ENABLED_LOCKED_DOWN -> VpnStateMonitor.AlwaysOnState.ALWAYS_ON_LOCKED_DOWN
            else -> VpnStateMonitor.AlwaysOnState.DEFAULT
        }
        return VpnState(runningState, stoppingReason, alwaysOnState)
    }
}
