package ai.familyroute.demo.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.EventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** 가족 제안과 도착 이야기를 차량 Speech Service의 오프라인 엔진으로 읽는다. */
class CarTts(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val engine = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val _status = MutableStateFlow("차량 음성 연결 중")
    val status = _status.asStateFlow()
    private var ready = false
    private var closed = false
    private var pending: String? = null
    private val events = object : EventListener {
        override fun onReady() { Log.i(TAG, "SDK onReady") }
        override fun onStart() { _status.value = "Pleos 차량 음성 안내 중"; Log.i(TAG, "SDK playback started") }
        override fun onDone() { _status.value = "Pleos 차량 음성 연결됨"; Log.i(TAG, "SDK playback completed") }
        override fun onStop() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onError(error: ErrorType) { main.post { if (!closed) { _status.value = "차량 음성을 사용할 수 없어요 · 화면 안내를 확인해 주세요"; Log.w(TAG, "SDK synthesis error=$error") } } }
    }
    init {
        try {
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() { main.post {
                    if (!closed) try {
                        engine.addEventListener(events)
                        // 차량의 on-device 엔진은 국가 코드까지 포함한 ko_KR을 요구한다.
                        engine.setLocale(Locale.KOREA)
                        ready = true
                        _status.value = "Pleos 차량 음성 연결됨"
                        Log.i(TAG, "SDK initialized ON_DEVICE")
                        pending?.let { pending = null; speak(it) }
                    } catch (e: Exception) { fail(e) }
                } }
                override fun onFailure(errorType: ErrorType) { main.post { if (!closed) { ready = false; pending = null; _status.value = "차량 음성 연결 실패 · 화면 안내를 확인해 주세요"; Log.w(TAG, "SDK initialize error=$errorType") } } }
            })
        } catch (e: Exception) { fail(e) }
    }
    fun speak(text: String) {
        if (closed) return
        val clean = text.replace(Regex("[✨🎉💌📍🚗🏁📸❤️🤤🔥👏🤩⚡🔒🔊▶◀✕]"), "").trim()
        if (clean.isBlank()) return
        if (!ready) { pending = clean; return }
        try { engine.stop(); engine.speak(clean) } catch (e: Exception) { fail(e) }
    }
    private fun fail(e: Exception) { ready = false; pending = null; _status.value = "차량 음성 연결 실패 · 화면 안내를 확인해 주세요"; Log.w(TAG, "SDK failure", e) }
    fun stop() { pending = null; if (!closed) runCatching { engine.stop() } }
    fun close() { if (closed) return; stop(); closed = true; main.removeCallbacksAndMessages(null); runCatching { engine.removeEventListener(events) }; runCatching { engine.release() } }
    private companion object { const val TAG = "FamilyRoutePleosTts" }
}
