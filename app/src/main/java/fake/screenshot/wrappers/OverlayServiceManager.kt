package fake.screenshot.wrappers

import android.content.Context
import android.net.Uri
import fake.screenshot.services.ControlOverlayService
import fake.screenshot.services.DisplayOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object OverlayServiceManager {
    private val _isDisplayRunning = MutableStateFlow(false)
    private val _isControlRunning = MutableStateFlow(false)
    private val _mediaList = MutableStateFlow<List<Uri>>(emptyList())
    val isDisplayRunning: StateFlow<Boolean> = _isDisplayRunning.asStateFlow()
    val isControlRunning: StateFlow<Boolean> = _isControlRunning.asStateFlow()
    val mediaList: StateFlow<List<Uri>> = _mediaList.asStateFlow()

    fun start(context: Context) {
        DisplayOverlayService.start(context)
        ControlOverlayService.start(context)
    }

    fun stop(context: Context) {
        DisplayOverlayService.stop(context)
        ControlOverlayService.stop(context)
    }

    fun startControl(context: Context) {
        ControlOverlayService.start(context)
    }

    fun stopControl(context: Context) {
        ControlOverlayService.stop(context)
    }

    fun setDisplayRunning(running: Boolean) {
        _isDisplayRunning.value = running
    }

    fun setControlRunning(running: Boolean) {
        _isControlRunning.value = running
    }

    fun setMediaList(list: List<Uri>) {
        _mediaList.value = list
    }
}