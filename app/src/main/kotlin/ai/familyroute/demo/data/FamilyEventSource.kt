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

/**
 * 대본 재생: 시나리오 script를 주행 거리(기본 경로 km)에 맞춰 보낸다.
 * [check]를 차량 상태가 바뀔 때마다 부르면 지난 지점의 이벤트를 순서대로 내보낸다.
 */
class ScriptedFamilySource(private val script: List<ai.familyroute.demo.domain.ScriptJson>) : FamilyEventSource {
    private val _events = MutableSharedFlow<FamilyEvent>(extraBufferCapacity = 32)
    override val events: Flow<FamilyEvent> = _events.asSharedFlow()
    private var fired = 0

    /** 테스트처럼 바로 처리하고 싶을 때 쓰는 콜백 */
    var direct: ((FamilyEvent) -> Unit)? = null

    fun check(baseKm: Double) {
        while (fired < script.size && baseKm >= script[fired].atKm) {
            val e = script[fired].event
            fired++
            direct?.invoke(e) ?: _events.tryEmit(e)
        }
    }

    fun reset() {
        fired = 0
    }

    val firedCount: Int get() = fired
}

/** 동승자 폰(같은 Wi-Fi)에서 들어온 이벤트 */
class PhoneFamilySource : FamilyEventSource {
    private val _events = MutableSharedFlow<FamilyEvent>(extraBufferCapacity = 16)
    override val events: Flow<FamilyEvent> = _events.asSharedFlow()

    fun inject(event: FamilyEvent) {
        _events.tryEmit(event)
    }
}
