package com.dingdinggo.app

import com.dingdinggo.app.ui.HomeStatus
import com.dingdinggo.app.ui.HomeUiState
import com.dingdinggo.app.ui.components.DingMascotMood
import com.dingdinggo.app.ui.components.resolveDingMascotMood
import org.junit.Assert.assertEquals
import org.junit.Test

class DingMascotMoodTest {

    @Test
    fun resolveMood_runningOverridesStatusWhenBusy() {
        val state = HomeUiState(enabled = true, status = HomeStatus.Idle(1000L))
        assertEquals(DingMascotMood.RUNNING, resolveDingMascotMood(state, busy = true))
        assertEquals(
            DingMascotMood.RUNNING,
            resolveDingMascotMood(state.copy(status = HomeStatus.Running), busy = false),
        )
    }

    @Test
    fun resolveMood_mapsFailedSuccessPausedAndReady() {
        assertEquals(
            DingMascotMood.ALERT,
            resolveDingMascotMood(
                HomeUiState(status = HomeStatus.Failed(at = 1000L, stage = "解锁", message = "解锁超时")),
                busy = false,
            ),
        )
        assertEquals(
            DingMascotMood.SUCCESS,
            resolveDingMascotMood(
                HomeUiState(status = HomeStatus.Success(at = 1000L, evidence = "极速打卡")),
                busy = false,
            ),
        )
        assertEquals(
            DingMascotMood.PAUSED,
            resolveDingMascotMood(HomeUiState(enabled = false), busy = false),
        )
        assertEquals(
            DingMascotMood.PAUSED,
            resolveDingMascotMood(HomeUiState(enabled = true, skipToday = true), busy = false),
        )
        assertEquals(
            DingMascotMood.PAUSED,
            resolveDingMascotMood(HomeUiState(enabled = true, onVacation = true), busy = false),
        )
        assertEquals(
            DingMascotMood.READY,
            resolveDingMascotMood(HomeUiState(enabled = true, status = HomeStatus.Idle(1000L)), busy = false),
        )
    }
}
