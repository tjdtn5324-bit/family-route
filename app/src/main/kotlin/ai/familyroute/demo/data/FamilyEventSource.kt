package ai.familyroute.demo.data

import ai.familyroute.demo.domain.FamilyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 가족 이벤트 공급원. 지금은 개발자 패널 수동 주입만 있다.
 * ScriptedFamilySource(Phase 3), 나중에 동승자 폰 연결이 같은 인터페이스로 붙는다.
 */
interface FamilyEventSource {
    val events: Flow<FamilyEvent>
}

class DevPanelFamilySource : FamilyEventSource {
    private val _events = MutableSharedFlow<FamilyEvent>(extraBufferCapacity = 16)
    override val events: Flow<FamilyEvent> = _events.asSharedFlow()

    fun inject(event: FamilyEvent) {
        _events.tryEmit(event)
    }
}
