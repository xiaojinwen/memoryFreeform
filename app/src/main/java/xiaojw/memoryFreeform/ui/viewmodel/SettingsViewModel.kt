package xiaojw.memoryFreeform.ui.viewmodel

import androidx.lifecycle.ViewModel
import xiaojw.memoryFreeform.core.AppState
import xiaojw.memoryFreeform.core.StateManager
import kotlinx.coroutines.flow.StateFlow

/**
 * SettingsViewModel：向 UI 暴露全局状态。
 * 具体的命令执行逻辑在 MainActivity / SingleHandManager。
 */
class SettingsViewModel : ViewModel() {

    val state: StateFlow<AppState> = StateManager.stateFlow

    fun isCornerLeft(): Boolean = state.value.corner == xiaojw.memoryFreeform.core.Corner.LEFT
}