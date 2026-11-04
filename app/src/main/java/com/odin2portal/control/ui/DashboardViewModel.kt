package com.odin2portal.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odin2portal.control.discovery.DeviceDiscovery
import com.odin2portal.control.domain.DeviceSnapshot
import com.odin2portal.control.hardware.AccessMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardState(
    val busy: Boolean = false,
    val mode: AccessMode = AccessMode.DIRECT_ONLY,
    val result: DeviceSnapshot? = null,
    val error: String? = null
)

class DashboardViewModel : ViewModel() {
    private val discovery = DeviceDiscovery()
    private val mutable = MutableStateFlow(DashboardState())
    val state = mutable.asStateFlow()

    init { refresh(AccessMode.DIRECT_ONLY) }

    /** Intentional user click controls privilege mode. No automatic root request. */
    fun refresh(mode: AccessMode) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, mode = mode, error = null)
        viewModelScope.launch {
            try {
                val snapshot = discovery.capture(mode)
                mutable.value = DashboardState(mode = mode, result = snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(
                    busy = false,
                    error = e.message ?: "Hardware discovery failed"
                )
            }
        }
    }
}
