package ai.familyroute.demo

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.familyroute.demo.car.*
import ai.familyroute.demo.phone.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 차량 화면(2판).
 * 가족과 연결하지 않아도 목적지를 찾아 바로 길안내를 받을 수 있다(혼자 운전).
 * 가족과 연결하면(휴대폰에서 받은 숫자 6자리) 가족이 휴대폰에서 만든 여행을 보고, 제안을 경로에 담고, 출발·도착·마치기를 기록한다.
 * 차량은 사람 계정으로 로그인하지 않는다. 주행 중에는 지도만 보여 주고, 모든 조작은 정차 중에만 열린다.
 */
private enum class Screen { MAP, SEARCH, TRIPS, FAMILY }

private const val ENDED = "이 차량의 가족 연결이 끝났어요. 혼자 운전은 그대로 쓸 수 있어요."
private const val LOCKED = "주행 중에는 화면이 잠겨요\n정차(P)하면 다시 조작할 수 있어요"

/** 혼자 운전하는 길: 가족 여행과 상관없이 이 차에서만 쓰고, 서버에 남지 않는다. 추천 코스로 가면 courseTitle과 들르는 곳이 함께 있다. */
private data class SoloDrive(val place: Place, val route: RouteLine, val stops: List<Place> = emptyList(), val courseTitle: String? = null) {
    fun stopsJson(): String = org.json.JSONArray().also { a -> stops.forEach { a.put(JSONObject().put("name", it.name).put("latitude", it.latitude).put("longitude", it.longitude)) } }.toString()
}

/** 추천 여행을 보는 순서: 화면 이름 → (정렬, 종류) */
private val COURSE_VIEWS = listOf("요즘 인기" to ("trending" to ""), "좋아요순" to ("popular" to ""), "새로 올라온" to ("new" to "COMMUNITY"), "참고 코스" to ("trending" to "REFERENCE"))

/** 한국어 TTS 음성 브리핑 헬퍼. 주행 중 제안 도착 안내와 정차 중 타임캡슐·인스타 스토리 음성 낭독을 지원한다. */
private class CarTts(context: Context) {
    private var ready = false
    private var engine: TextToSpeech? = null

    init {
        try {
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = engine?.setLanguage(Locale.KOREAN)
                    ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                    engine?.setSpeechRate(1.02f)
                }
            }
        } catch (_: Throwable) {
            ready = false
        }
    }

    fun speak(text: String) {
        val cleanText = text.replace(Regex("[✨🎉💌📍🚗🏁📸❤️🤤🔥👏🤩⚡🔒🔊▶◀✕]"), "").trim()
        if (!ready || cleanText.isBlank()) return
        try {
            engine?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "familyroute-tts")
        } catch (_: Throwable) {}
    }

    fun stop() {
        try { engine?.stop() } catch (_: Throwable) {}
    }

    fun close() {
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (_: Throwable) {}
    }
}

/** 차량 위치 센서(LocationManager) 연동 상태 확인. 권한이 없거나 시연 환경이면 서울 시연 기준점으로 안전하게 Fallback한다. */
private fun resolveVehicleOriginLabel(context: Context): String {
    val lm = runCatching { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }.getOrNull()
    return if (lm != null) "차량 위치 센서 연동 · 출발 기준: 서울 (시연)" else "출발 기준: 서울 (시연)"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val api = remember { CarApi() }
            val linkStore = remember { VehicleLink(applicationContext) }
            val parking = remember { ParkingGuard(applicationContext) }
            val tts = remember { CarTts(applicationContext) }
            val originLabel = remember { resolveVehicleOriginLabel(applicationContext) }
            val parked by parking.allowed.collectAsStateWithLifecycle()
            DisposableEffect(parking, tts) { onDispose { parking.close(); tts.close() } }
            val scope = rememberCoroutineScope()

            var link by remember { mutableStateOf(linkStore.load()) }
            var family by remember { mutableStateOf<FamilyInfo?>(null) }
            var trips by remember { mutableStateOf<List<TripSummary>>(emptyList()) }
            var tripId by remember { mutableStateOf<String?>(linkStore.loadTripId()) }
            var trip by remember { mutableStateOf<TripDetail?>(null) }
            var solo by remember { mutableStateOf<SoloDrive?>(linkStore.loadSolo()?.let { SoloDrive(it.place, it.route, it.stops, it.courseTitle) }) }
            var screen by remember { mutableStateOf(Screen.MAP) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            var notice by remember { mutableStateOf("") }
            var offline by remember { mutableStateOf(false) }
            var incoming by remember { mutableStateOf<Idea?>(null) }
            var arrival by remember { mutableStateOf<Arrival?>(null) }
            var confirmFinish by remember { mutableStateOf(false) }
            var confirmUnpair by remember { mutableStateOf(false) }
            var confirmCancelTrip by remember { mutableStateOf(false) }
            var pendingDestination by remember { mutableStateOf<Place?>(null) }
            var code by remember { mutableStateOf("") }
            var carName by remember { mutableStateOf("우리 차") }
            var newFamilyName by remember { mutableStateOf("우리 가족") }
            var newTripTitle by remember { mutableStateOf("우리 가족 주말 여행") }
            var showCreateTrip by remember { mutableStateOf(false) }
            var showQrDialog by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var results by remember { mutableStateOf<List<Place>>(emptyList()) }
            var phoneOrigin by remember { mutableStateOf<String?>(null) }
            var showCourses by remember { mutableStateOf(false) }
            var courseView by remember { mutableStateOf(0) }
            var courses by remember { mutableStateOf<List<Course>?>(null) }
            var openCourse by remember { mutableStateOf<Course?>(null) }
            var courseDraftStops by remember { mutableStateOf<List<Place>>(emptyList()) }
            var courseAddingStop by remember { mutableStateOf(false) }
            var addingStopToSolo by remember { mutableStateOf(false) }
            var tripSearchKind by remember { mutableStateOf("STOP") }
            // 옆 패널은 한 번에 한 가지만 보여 준다: 가족 여행(제안·순서·장소 추가·목록), 가족(초대·구성원·차), 연결 방법.
            var tripsTab by remember { mutableStateOf(0) }
            var familyTab by remember { mutableStateOf(0) }
            var joinTab by remember { mutableStateOf(0) }

            fun updateSolo(next: SoloDrive?) {
                solo = next
                linkStore.saveSolo(next?.let { SavedSoloDrive(it.place, it.route, it.stops, it.courseTitle) })
            }
            fun updateTripId(next: String?) {
                tripId = next
                linkStore.saveTripId(next)
            }
            fun forget(message: String) {
                linkStore.clear(); link = null; family = null; trips = emptyList(); trip = null; tripId = null; incoming = null; arrival = null
                confirmFinish = false; confirmUnpair = false; confirmCancelTrip = false; pendingDestination = null; showQrDialog = false; showCreateTrip = false
                if (screen == Screen.TRIPS) screen = Screen.MAP; notice = message
            }
            /** 한 번에 하나씩 실행하고 실패 이유를 알려 준다. 401이면 가족 연결이 끝난 것이다. */
            fun run(block: suspend () -> Unit) {
                if (busy) return
                busy = true; error = ""
                scope.launch {
                    try { block() }
                    catch (e: CancellationException) { throw e }
                    catch (e: ApiError) { if (e.status == 401 && link != null) forget(ENDED) else error = e.message ?: "" }
                    catch (_: Exception) { error = "연결이 느리거나 끊겼어요. 잠시 후 다시 시도해 주세요." }
                    finally { busy = false }
                }
            }
            fun select(id: String) { updateTripId(id); trip = null; incoming = null; updateSolo(null); screen = Screen.MAP }
            fun openSearch(recommended: Boolean = false, addStopForSolo: Boolean = false) {
                screen = Screen.SEARCH; error = ""; results = emptyList(); query = ""
                showCourses = recommended; openCourse = null; courseAddingStop = false; addingStopToSolo = addStopForSolo
            }
            // 추천 여행 목록: 탭이나 보는 순서가 바뀔 때마다 새로 받는다.
            LaunchedEffect(screen, showCourses, courseView) {
                if (screen != Screen.SEARCH || !showCourses) return@LaunchedEffect
                courses = null
                val (sort, kind) = COURSE_VIEWS[courseView].second
                try { courses = api.courses(sort, kind) } catch (e: CancellationException) { throw e } catch (_: Exception) { courses = emptyList(); error = "추천 여행을 불러오지 못했어요. 다시 눌러 주세요." }
            }

            // 가족과 연결된 동안 3초마다 가족 정보, 여행 목록, 고른 여행을 새로 받는다.
            LaunchedEffect(link?.token) {
                val token = link?.token ?: return@LaunchedEffect
                val seen = mutableSetOf<String>(); val seenReady = mutableSetOf<String>(); var first = true
                while (true) {
                    try {
                        family = api.home(token)
                        trips = api.trips(token)
                        // 가족이 휴대폰에서 '차량 출발 준비 완료(readyForCar)'를 눌렀거나 진행 중이면 자동으로 그 코스를 지도에 띄운다.
                        val newlyReady = trips.firstOrNull { (it.status == "ONGOING" || it.readyForCar) && it.id !in seenReady }
                        if (newlyReady != null) {
                            seenReady.add(newlyReady.id)
                            if (tripId != newlyReady.id || solo != null) {
                                updateTripId(newlyReady.id)
                                updateSolo(null)
                                if (!first) notice = "가족이 준비한 ‘${newlyReady.title}’ 코스를 불러왔어요."
                            }
                        }
                        trips.filter { it.status == "ONGOING" || it.readyForCar }.forEach { seenReady.add(it.id) }
                        // 고른 여행이 없거나 끝났으면 진행 중인 여행 -> 출발 준비 완료된 여행 -> 준비 중인 여행 순으로 고른다.
                        if (tripId == null || trips.none { it.id == tripId && it.status != "DONE" }) {
                            updateTripId(
                                trips.firstOrNull { it.status == "ONGOING" }?.id
                                    ?: trips.firstOrNull { it.status == "PLANNING" && it.readyForCar }?.id
                                    ?: trips.firstOrNull { it.status == "PLANNING" }?.id
                            )
                        }
                        val id = tripId
                        val next = if (id != null) api.trip(token, id) else null
                        val fresh = next?.ideas?.filter { it.status == "PROPOSED" && it.id !in seen }.orEmpty()
                        next?.ideas?.forEach { seen.add(it.id) }
                        if (!first && incoming == null && fresh.isNotEmpty()) incoming = fresh.last()
                        val shown = incoming
                        if (shown != null && next?.ideas?.none { it.id == shown.id && it.status == "PROPOSED" } != false) incoming = null
                        if (!busy) trip = next
                        first = false; offline = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: ApiError) { if (e.status == 401) { forget(ENDED); break } else offline = true }
                    catch (_: Exception) { offline = true }
                    delay(3000)
                }
            }
            // 재부팅이나 터널 재시작으로 임시 휴대폰 주소가 바뀌더라도 QR 코드가 항상 최신 주소를 가리키도록 주기적으로 갱신한다.
            LaunchedEffect(link?.token, screen, showQrDialog) {
                if (link == null) return@LaunchedEffect
                while (true) {
                    val latest = api.phoneAppOrigin()
                    if (latest != null && latest != phoneOrigin) phoneOrigin = latest
                    delay(if (screen == Screen.FAMILY || showQrDialog || phoneOrigin == null) 4000L else 15000L)
                }
            }
            fun arrivalSpeechText(a: Arrival): String {
                val first = a.stories.firstOrNull()
                return if (first != null) {
                    buildString {
                        append("${a.place.name}에 도착했어요. ${first.by}님이 남긴 이야기입니다. ")
                        if (first.reason.isNotBlank()) append("가고 싶었던 이유, ${first.reason}. ")
                        if (first.message.isNotBlank()) append("가족에게 남긴 한마디, ${first.message}.")
                    }
                } else {
                    "${a.place.name}에 도착했어요."
                }
            }
            LaunchedEffect(parked) {
                if (!parked) {
                    if (screen != Screen.MAP || arrival != null || confirmFinish || confirmUnpair || confirmCancelTrip || pendingDestination != null || showQrDialog || openCourse != null || showCreateTrip) {
                        notice = "출발해서 화면을 닫았어요."
                    }
                    screen = Screen.MAP; arrival = null; confirmFinish = false; confirmUnpair = false; confirmCancelTrip = false; pendingDestination = null; showQrDialog = false
                    openCourse = null; courseAddingStop = false; addingStopToSolo = false; showCreateTrip = false
                    tts.stop()
                }
            }
            LaunchedEffect(incoming?.id, parked) {
                val p = incoming
                if (p != null && !parked) {
                    tts.speak("${p.byName}님이 ${p.place.name} 경유지를 제안했어요. 정차 후 확인해 보세요.")
                    delay(4000)
                    incoming = null
                }
            }
            LaunchedEffect(arrival, parked) {
                val a = arrival
                if (a != null && parked) {
                    tts.speak(arrivalSpeechText(a))
                }
            }
            LaunchedEffect(notice) { if (notice.isNotBlank()) { delay(5000); notice = "" } }

            // 지도에 그릴 길: 혼자 운전 중이면 그 길, 아니면 고른 가족 여행의 길.
            val shownSolo = solo
            val mapPoints = shownSolo?.route?.pointsJson ?: trip?.route?.pointsJson
            val mapStops = shownSolo?.stopsJson() ?: trip?.stopsJson() ?: "[]"

            // 차량 화면은 넓어서 글자·버튼이 작아 보인다. 가로 1280dp에 맞춰 전체를 키운다.
            val base = LocalDensity.current
            val zoom = (LocalConfiguration.current.screenWidthDp / 1280f).coerceIn(1f, 1.6f)
            CompositionLocalProvider(LocalDensity provides Density(base.density * zoom, base.fontScale)) {
            Box(Modifier.fillMaxSize().background(Color(0xFFE4DCD3))) {
                FamilyMap(mapPoints, mapStops, parked, Modifier.fillMaxSize())
                val token = link?.token
                Row(Modifier.fillMaxSize()) {
                    // ── 왼쪽 메뉴 ──
                    Column(Modifier.fillMaxHeight().width(124.dp).background(CarColors.Navy).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Image(painterResource(R.drawable.ic_app_brand), contentDescription = "아빠어디가", modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.height(6.dp))
                        NavItem(R.drawable.ic_nav_map, "지도", screen == Screen.MAP) { screen = Screen.MAP; error = "" }
                        NavItem(R.drawable.ic_nav_search, "목적지", screen == Screen.SEARCH, enabled = parked) { openSearch() }
                        if (token != null) NavItem(R.drawable.ic_flag, "가족 여행", screen == Screen.TRIPS, enabled = parked, badge = trip?.ideas?.count { it.status == "PROPOSED" } ?: 0) { screen = Screen.TRIPS; error = ""; results = emptyList() }
                        NavItem(R.drawable.ic_nav_family, "가족", screen == Screen.FAMILY, enabled = parked) { screen = Screen.FAMILY; error = "" }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (screen) {
                            Screen.MAP -> {
                                val t = trip
                                Column(Modifier.align(Alignment.TopStart).padding(18.dp).width(460.dp).heightIn(max = 670.dp).background(CarColors.Navy, RoundedCornerShape(24.dp)).padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (shownSolo != null) {
                                        // ── 혼자 운전 / 추천 여행 길안내 ──
                                        Tag(if (shownSolo.courseTitle != null) "추천 여행 길안내" else "길안내", Color.White, CarColors.Navy)
                                        T(shownSolo.courseTitle ?: shownSolo.place.name, 28, FontWeight.Black, Color.White, maxLines = 2)
                                        T("${"%.1f".format(shownSolo.route.distance / 1000.0)}km · 약 ${formatMinutes(shownSolo.route.duration / 60)}", 20, FontWeight.Bold, CarColors.OnNavySub)
                                        if (parked && shownSolo.stops.isNotEmpty()) {
                                            T("들르는 곳 ${shownSolo.stops.size}/5", 17, FontWeight.Bold, CarColors.OnNavySub)
                                            shownSolo.stops.forEachIndexed { idx, stop ->
                                                Row(Modifier.fillMaxWidth().background(Color(0x22FFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    T("${idx + 1}. ${stop.name}", 19, FontWeight.Bold, Color.White, Modifier.weight(1f), maxLines = 1)
                                                    if (idx > 0) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)).clickable(enabled = !busy) {
                                                        val nextStops = shownSolo.stops.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                                                        run { updateSolo(shownSolo.copy(route = api.soloRoute(shownSolo.place, nextStops), stops = nextStops)); notice = "경유지 순서를 바꿨어요." }
                                                    }.padding(horizontal = 10.dp, vertical = 8.dp)) { T("↑", 18, FontWeight.Bold, Color.White) }
                                                    if (idx < shownSolo.stops.lastIndex) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)).clickable(enabled = !busy) {
                                                        val nextStops = shownSolo.stops.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                                                        run { updateSolo(shownSolo.copy(route = api.soloRoute(shownSolo.place, nextStops), stops = nextStops)); notice = "경유지 순서를 바꿨어요." }
                                                    }.padding(horizontal = 10.dp, vertical = 8.dp)) { T("↓", 18, FontWeight.Bold, Color.White) }
                                                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(CarColors.BadBg).clickable(enabled = !busy) {
                                                        val nextStops = shownSolo.stops.filterIndexed { i, _ -> i != idx }
                                                        run { updateSolo(shownSolo.copy(route = api.soloRoute(shownSolo.place, nextStops), stops = nextStops)); notice = "‘${stop.name}’ 경유지를 뺐어요." }
                                                    }.padding(horizontal = 12.dp, vertical = 8.dp)) { T("빼기", 17, FontWeight.Bold, CarColors.Bad) }
                                                }
                                            }
                                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Tag("도착", Color.White, CarColors.Navy)
                                                T(shownSolo.place.name, 20, FontWeight.Bold, Color.White, Modifier.weight(1f), maxLines = 1)
                                            }
                                        } else if (parked && shownSolo.place.address.isNotBlank()) {
                                            T(shownSolo.place.address, 18, color = CarColors.OnNavySub, maxLines = 2)
                                        }
                                        if (parked) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (shownSolo.stops.size < 5) CarButton("경유지 추가", Modifier.weight(1f), Btn.OnNavyStrong, enabled = !busy) { openSearch(recommended = false, addStopForSolo = true) }
                                                CarButton("목적지 변경", Modifier.weight(1f), Btn.OnNavy, enabled = !busy) { openSearch(false) }
                                            }
                                            CarButton("안내 끝내기", Modifier.fillMaxWidth(), Btn.OnNavy) { updateSolo(null); notice = "길안내를 끝냈어요." }
                                        } else {
                                            val nextStopName = shownSolo.stops.firstOrNull()?.name ?: shownSolo.place.name
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Tag("다음 안내", Color(0xFFFF8A3D), Color.White)
                                                T(nextStopName, 22, FontWeight.Bold, Color.White, Modifier.weight(1f), maxLines = 1)
                                            }
                                            T(LOCKED, 19, FontWeight.Bold, Color.White)
                                        }
                                    } else if (token == null) {
                                        // ── 가족 연결 전: 혼자 운전부터 바로 쓸 수 있게 ──
                                        T("어디로 갈까요?", 30, FontWeight.Black, Color.White)
                                        if (parked) {
                                            CarButton("목적지 찾기", Modifier.fillMaxWidth(), Btn.OnNavyStrong, icon = R.drawable.ic_nav_search) { openSearch() }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                CarButton("추천 여행", Modifier.weight(1f), Btn.OnNavy) { openSearch(true) }
                                                CarButton("가족 연결", Modifier.weight(1f), Btn.OnNavy) { screen = Screen.FAMILY; error = "" }
                                            }
                                        } else T(LOCKED, 19, FontWeight.Bold, Color.White)
                                    } else {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            T(family?.familyName ?: link?.familyName ?: "", 18, FontWeight.Bold, CarColors.OnNavySub, Modifier.weight(1f), maxLines = 1)
                                            if (parked) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)).clickable { showQrDialog = true }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                                T("QR 초대", 16, FontWeight.Bold, Color.White)
                                            }
                                        }
                                        if (t == null) {
                                            val hasActive = trips.any { it.status != "DONE" }
                                            T(if (hasActive) "여행을 골라 주세요" else "준비 중인 여행이 없어요", 30, FontWeight.Black, Color.White)
                                            if (parked) {
                                                if (hasActive) CarButton("가족 여행 고르기", Modifier.fillMaxWidth(), Btn.OnNavyStrong, icon = R.drawable.ic_flag) { showCreateTrip = false; screen = Screen.TRIPS; error = "" }
                                                else CarButton("가족 여행방 만들기", Modifier.fillMaxWidth(), Btn.OnNavyStrong, icon = R.drawable.ic_flag) { showCreateTrip = true; screen = Screen.TRIPS; error = ""; results = emptyList() }
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    CarButton("추천 여행", Modifier.weight(1f), Btn.OnNavy) { openSearch(true) }
                                                    CarButton("목적지 찾기", Modifier.weight(1f), Btn.OnNavy) { openSearch() }
                                                }
                                            } else T(LOCKED, 19, FontWeight.Bold, Color.White)
                                        } else {
                                            val pending = remember(t) { t.ideas.filter { it.status == "PROPOSED" } }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Tag(statusLabel(t.status), if (t.status == "ONGOING") Color.White else Color(0x33FFFFFF), if (t.status == "ONGOING") CarColors.Navy else Color.White)
                                                if (t.readyForCar && t.status == "PLANNING") Tag(t.readyByName?.takeIf { it.isNotBlank() && it != "차량" && it != "떠난 가족" }?.let { "${it.take(8)}님이 준비 완료" } ?: "출발 준비 완료", Color(0xFFFF8A3D), Color.White)
                                                if (t.status == "ONGOING" && t.stops.isNotEmpty()) Tag("${t.stops.count { it.status == "VISITED" }}/${t.stops.size}곳 다녀옴", Color(0x33FFFFFF), Color.White)
                                                if (pending.isNotEmpty()) Tag("새 제안 ${pending.size}곳", Color(0x33FF8A3D), Color(0xFFFFD08A))
                                            }
                                            T(t.title, 30, FontWeight.Black, Color.White, maxLines = 2)
                                            val route = t.route
                                            T(when {
                                                route != null -> "${t.destination?.name ?: ""} · ${"%.1f".format(route.distance / 1000.0)}km · 약 ${formatMinutes(route.duration / 60)}"
                                                t.destination != null -> "${t.destination.name} · 경로를 아직 찾지 못했어요"
                                                else -> "목적지를 아직 정하지 않았어요"
                                            }, 21, FontWeight.Bold, CarColors.OnNavySub)
                                            if (parked) {
                                                // 들르는 곳마다 누가 왜 가고 싶어 했는지 함께 보여 준다(추천 코스나 차에서 바로 넣은 곳은 이름만).
                                                val stopNotes = t.stops.map { p -> if (p.reason.startsWith("추천 코스") || p.reason.endsWith("추가한 경유지")) "" else "${p.byName}님" + (if (p.reason.isNotBlank()) " · ${p.reason}" else "") }
                                                StopList(t.stops.map { (if (it.status == "VISITED") "✓ " else "") + it.place.name } + listOfNotNull(t.destination?.name), onNavy = true, notes = stopNotes)
                                                val next = t.stops.firstOrNull { it.status != "VISITED" }
                                                val needsDestination = t.status == "PLANNING" && t.destination == null && pending.isEmpty()
                                                // 이 카드의 큰 버튼은 지금 할 일 하나뿐이다: 출발 또는 도착.
                                                if (t.status == "PLANNING" && !needsDestination) {
                                                    val startLabel = when {
                                                        busy -> "출발 준비 중"
                                                        pending.isNotEmpty() -> "가족 제안 ${pending.size}곳 담고 출발"
                                                        else -> "이 코스로 출발"
                                                    }
                                                    CarButton(startLabel, Modifier.fillMaxWidth(), Btn.OnNavyStrong, enabled = !busy, icon = R.drawable.ic_flag) {
                                                        run {
                                                            trip = if (pending.isNotEmpty()) api.quickStart(token, t.id) else api.start(token, t.id)
                                                            trips = api.trips(token)
                                                            notice = "출발했어요. 들르는 곳에 정차하면 ‘도착’을 눌러 주세요."
                                                        }
                                                    }
                                                }
                                                if (t.status == "ONGOING") {
                                                    if (next != null) CarButton("${next.place.name} 도착", Modifier.fillMaxWidth(), Btn.OnNavyStrong, enabled = !busy) { run { val a = api.arrive(token, next.id); trip = a.trip; arrival = a } }
                                                    else CarButton("목적지 도착 · 여행 마치기", Modifier.fillMaxWidth(), Btn.OnNavyStrong, enabled = !busy, icon = R.drawable.ic_flag) { confirmFinish = true }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    CarButton(if (needsDestination) "목적지 정하기" else "코스 편집", Modifier.weight(1f), if (needsDestination) Btn.OnNavyStrong else Btn.OnNavy, enabled = !busy) {
                                                        tripsTab = if (pending.isNotEmpty()) 0 else if (t.destination == null) 2 else 1
                                                        showCreateTrip = false; screen = Screen.TRIPS; error = ""; results = emptyList(); query = ""
                                                    }
                                                    // 취소: 출발 뒤에는 마치기·출발 취소를, 출발 전에는 코스 비우기·여행방 지우기를 고른다.
                                                    CarButton(if (t.status == "ONGOING") "여행 종료" else "여행 취소", Modifier.weight(1f), Btn.OnNavy, enabled = !busy) { if (t.status == "ONGOING") confirmFinish = true else confirmCancelTrip = true }
                                                }
                                            } else {
                                                val nextTarget = t.stops.firstOrNull { it.status != "VISITED" }?.place?.name ?: t.destination?.name ?: "목적지 미정"
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Tag("다음 안내", Color(0xFFFF8A3D), Color.White)
                                                    T(nextTarget, 22, FontWeight.Bold, Color.White, Modifier.weight(1f), maxLines = 1)
                                                }
                                                T(LOCKED, 19, FontWeight.Bold, Color.White)
                                            }
                                        }
                                        if (offline) T("연결이 끊겼어요 · 자동으로 다시 연결해요", 18, FontWeight.Bold, Color(0xFFFFD08A))
                                    }
                                }
                                incoming?.let { p ->
                                    if (token != null) Column(Modifier.align(Alignment.BottomEnd).padding(18.dp).width(470.dp).background(Color.White, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (parked) {
                                            Tag("${p.byName.take(12)}님의 새 제안")
                                            T(p.place.name, 26, FontWeight.Black, maxLines = 1)
                                            if (p.reason.isNotBlank()) T("“${p.reason}”", 18, color = CarColors.Sub, maxLines = 2)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                CarButton(if (p.kind == "DESTINATION") "목적지로 정하기" else "경로에 담기", Modifier.weight(1.4f), enabled = !busy) { run { trip = api.decide(token, p.id, true); incoming = null; updateSolo(null); notice = "${p.place.name} · 경로에 담았어요." } }
                                                CarButton("나중에 보기", Modifier.weight(1f), Btn.Soft) { incoming = null }
                                            }
                                        } else T("가족 제안이 도착했어요 · 정차 후 확인", 24, FontWeight.Bold, maxLines = 1)
                                    }
                                }
                            }
                            Screen.SEARCH -> SidePanel(if (showCourses) "추천 여행" else if (addingStopToSolo && solo != null) "경유지 추가" else "목적지 찾기", error, { addingStopToSolo = false; screen = Screen.MAP }, Modifier.fillMaxWidth(.64f)) {
                                Segmented(listOf("장소 검색", "추천 여행"), if (showCourses) 1 else 0) { i -> openSearch(i == 1, addStopForSolo = if (i == 0) addingStopToSolo else false) }
                                if (showCourses) {
                                    val c = openCourse
                                    if (c == null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { COURSE_VIEWS.forEachIndexed { i, v -> Choice(v.first, courseView == i) { courseView = i; error = "" } } }
                                        val list = courses
                                        when {
                                            list == null -> T("추천 여행을 불러오는 중이에요.", 19, color = CarColors.Sub)
                                            list.isEmpty() -> T(if (COURSE_VIEWS[courseView].second.second == "COMMUNITY") "아직 다른 가족이 올린 코스가 없어요. 여행을 다녀와서 휴대폰 추억 앨범에서 코스로 올릴 수 있어요." else "보여 줄 추천 여행이 없어요.", 19, color = CarColors.Sub)
                                        }
                                        list?.forEach { course ->
                                            Card(Modifier.clickable {
                                                run {
                                                    val full = api.course(course.id)
                                                    openCourse = full
                                                    courseDraftStops = full.waypoints
                                                    courseAddingStop = false
                                                    results = emptyList(); query = ""
                                                }
                                            }) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Tag(if (course.kind == "REFERENCE") "참고 코스" else "가족이 올린 코스")
                                                    if (course.trending) Tag("요즘 인기", CarColors.OkBg, CarColors.Ok)
                                                }
                                                T(course.title, 24, FontWeight.Bold, maxLines = 1)
                                                T((course.waypoints.map { it.name } + course.destination.name).joinToString(" → "), 18, color = CarColors.Ink, maxLines = 1)
                                                T("좋아요 ${course.likes}" + (if (course.reviewCount > 0) " · 후기 ${course.reviewCount} · ★ ${course.rating}" else "") + (course.sourceName?.let { " · 참고: $it" } ?: ""), 16, color = CarColors.Sub, maxLines = 1)
                                            }
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Tag(if (c.kind == "REFERENCE") "참고 코스" else "가족이 올린 코스")
                                            if (c.trending) Tag("요즘 인기", CarColors.OkBg, CarColors.Ok)
                                            Spacer(Modifier.weight(1f))
                                            CarButton("목록으로", kind = Btn.Soft) { openCourse = null; courseAddingStop = false }
                                        }
                                        T(c.title, 28, FontWeight.Black, maxLines = 2)
                                        if (c.description.isNotBlank()) T(c.description, 18, color = CarColors.Sub, maxLines = 2)
                                        T("들르는 순서 ${courseDraftStops.size}/5", 19, FontWeight.Bold, CarColors.Navy)
                                        if (courseDraftStops.isEmpty()) {
                                            T("경유지 없이 도착지(${c.destination.name})로 바로 안내해요.", 18, color = CarColors.Sub)
                                        }
                                        courseDraftStops.forEachIndexed { i, w ->
                                            Card {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    T("${i + 1}", 22, FontWeight.Black, CarColors.Teal)
                                                    Column(Modifier.weight(1f)) {
                                                        T(w.name, 21, FontWeight.Bold, maxLines = 1)
                                                        if (w.address.isNotBlank()) T(w.address, 16, color = CarColors.Sub, maxLines = 1)
                                                    }
                                                    if (i > 0) CarButton("↑", kind = Btn.Soft) {
                                                        courseDraftStops = courseDraftStops.toMutableList().apply { add(i - 1, removeAt(i)) }
                                                    }
                                                    if (i < courseDraftStops.lastIndex) CarButton("↓", kind = Btn.Soft) {
                                                        courseDraftStops = courseDraftStops.toMutableList().apply { add(i + 1, removeAt(i)) }
                                                    }
                                                    CarButton("빼기", kind = Btn.Danger) {
                                                        courseDraftStops = courseDraftStops.filterIndexed { idx, _ -> idx != i }
                                                    }
                                                }
                                            }
                                        }
                                        Card {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Tag("도착지")
                                                Column(Modifier.weight(1f)) {
                                                    T(c.destination.name, 21, FontWeight.Bold, maxLines = 1)
                                                    if (c.destination.address.isNotBlank()) T(c.destination.address, 16, color = CarColors.Sub, maxLines = 1)
                                                }
                                            }
                                        }
                                        if (courseDraftStops != c.waypoints) {
                                            CarButton("원래 코스로 되돌리기", Modifier.fillMaxWidth(), Btn.Soft) {
                                                courseDraftStops = c.waypoints
                                            }
                                        }
                                        if (courseDraftStops.size < 5) {
                                            if (!courseAddingStop) {
                                                CarButton("경유지 추가", Modifier.fillMaxWidth(), Btn.Line, icon = R.drawable.ic_nav_search) {
                                                    courseAddingStop = true; query = ""; results = emptyList(); error = ""
                                                }
                                            } else {
                                                Card {
                                                    T("코스에 추가할 경유지 검색", 20, FontWeight.Bold)
                                                    val findForCourse = { run { results = api.search(token, query.trim()); if (results.isEmpty()) error = "검색 결과가 없어요." } }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        TextInput(query, { v -> query = v }, "예: 해운대, 카페, 시장", "추가할 경유지 검색어", Modifier.weight(1f)) { if (query.isNotBlank()) findForCourse() }
                                                        CarButton(if (busy) "찾는 중" else "검색", enabled = !busy && query.isNotBlank(), icon = R.drawable.ic_nav_search) { findForCourse() }
                                                        CarButton("닫기", kind = Btn.Soft) { courseAddingStop = false; results = emptyList(); query = "" }
                                                    }
                                                    results.forEach { place ->
                                                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                            Column(Modifier.weight(1f)) {
                                                                T(place.name, 20, FontWeight.Bold, maxLines = 1)
                                                                if (place.address.isNotBlank()) T(place.address, 16, color = CarColors.Sub, maxLines = 1)
                                                            }
                                                            CarButton("경유지 추가", kind = Btn.Primary, enabled = courseDraftStops.size < 5) {
                                                                courseDraftStops = courseDraftStops + place
                                                                courseAddingStop = false; results = emptyList(); query = ""
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        T("좋아요 ${c.likes}" + (if (c.reviewCount > 0) " · 후기 ${c.reviewCount} · ★ ${c.rating}" else "") + (c.sourceName?.let { " · 참고: $it" } ?: ""), 17, color = CarColors.Sub, maxLines = 1)
                                        CarButton(if (busy) "길 찾는 중" else "이 코스로 안내 시작", Modifier.fillMaxWidth(), enabled = !busy, icon = R.drawable.ic_flag) {
                                            run {
                                                updateSolo(SoloDrive(c.destination, api.soloRoute(c.destination, courseDraftStops), courseDraftStops, c.title))
                                                openCourse = null; courseAddingStop = false; screen = Screen.MAP
                                                notice = "‘${c.title}’ 코스로 안내를 시작해요."
                                            }
                                        }
                                        val activeTrip = trip?.takeIf { it.status != "DONE" }
                                        if (token != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            if (activeTrip != null) {
                                                CarButton(if (busy) "적용 중" else "지금 가족 여행에 적용", Modifier.weight(1f), Btn.Soft, enabled = !busy) {
                                                    run {
                                                        trip = api.applyCourse(token, activeTrip.id, c.id, courseDraftStops, c.destination)
                                                        updateSolo(null); openCourse = null; courseAddingStop = false; screen = Screen.MAP
                                                        notice = "‘${c.title}’ 코스를 가족 여행에 적용했어요."
                                                    }
                                                }
                                            }
                                            CarButton(if (busy) "만드는 중" else "새 가족 여행방으로", Modifier.weight(1f), Btn.Line, enabled = !busy) {
                                                run {
                                                    val created = api.createTrip(token, c.title, c.destination, c.id, courseDraftStops)
                                                    trips = api.trips(token); trip = created; updateTripId(created.id); updateSolo(null)
                                                    openCourse = null; courseAddingStop = false; screen = Screen.MAP
                                                    notice = "‘${c.title}’ 코스로 새 가족 여행방을 만들었어요."
                                                }
                                            }
                                        }
                                    }
                                    return@SidePanel
                                }
                                val currentSolo = solo
                                if (addingStopToSolo && currentSolo != null) {
                                    Card {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Column(Modifier.weight(1f)) {
                                                Tag("현재 경로에 경유지 추가 (${currentSolo.stops.size}/5)")
                                                T("${currentSolo.courseTitle ?: currentSolo.place.name} 가는 길에 들를 곳을 검색해 추가해요.", 18, color = CarColors.Ink)
                                            }
                                            CarButton("새 목적지 찾기로 전환", kind = Btn.Soft) { addingStopToSolo = false }
                                        }
                                    }
                                }
                                val find = { run { results = api.search(token, query.trim()); if (results.isEmpty()) error = "검색 결과가 없어요." } }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextInput(query, { v -> query = v }, if (addingStopToSolo) "예: 해운대, 카페, 시장" else "예: 부산역, 강릉, 경주", "장소 검색어", Modifier.weight(1f)) { if (query.isNotBlank()) find() }
                                    CarButton(if (busy) "찾는 중" else "검색", enabled = !busy && query.isNotBlank(), icon = R.drawable.ic_nav_search) { find() }
                                }
                                if (results.isEmpty()) T(if (addingStopToSolo) "중간에 들르고 싶은 장소 이름을 검색해 주세요." else "장소 이름이나 지역을 적어 주세요. 바로 길안내를 시작해요.", 19, color = CarColors.Sub)
                                results.forEach { place ->
                                    Card {
                                        T(place.name, 22, FontWeight.Bold, maxLines = 1)
                                        if (place.address.isNotBlank()) T(place.address, 17, color = CarColors.Sub, maxLines = 1)
                                        if (addingStopToSolo && currentSolo != null && currentSolo.stops.size < 5) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                CarButton("현재 경로에 경유지로 추가", Modifier.weight(1.4f), enabled = !busy, icon = R.drawable.ic_flag) {
                                                    val nextStops = currentSolo.stops + place
                                                    run {
                                                        updateSolo(currentSolo.copy(route = api.soloRoute(currentSolo.place, nextStops), stops = nextStops))
                                                        addingStopToSolo = false; results = emptyList(); query = ""; screen = Screen.MAP
                                                        notice = "‘${place.name}’ 경유지를 추가했어요."
                                                    }
                                                }
                                                CarButton("여기로 목적지 변경", Modifier.weight(1f), Btn.Soft, enabled = !busy) {
                                                    run {
                                                        updateSolo(SoloDrive(place, api.soloRoute(place, currentSolo.stops), currentSolo.stops, currentSolo.courseTitle))
                                                        addingStopToSolo = false; results = emptyList(); query = ""; screen = Screen.MAP
                                                        notice = "목적지를 ${place.name}(으)로 바꿨어요."
                                                    }
                                                }
                                            }
                                        } else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                CarButton("여기로 바로 안내", Modifier.weight(1.3f), enabled = !busy, icon = R.drawable.ic_flag) {
                                                    run { updateSolo(SoloDrive(place, api.soloRoute(place))); results = emptyList(); query = ""; screen = Screen.MAP; notice = "${place.name}(으)로 안내를 시작해요." }
                                                }
                                                if (currentSolo != null && currentSolo.stops.size < 5) {
                                                    CarButton("현재 경로에 경유지 추가", Modifier.weight(1f), Btn.Soft, enabled = !busy) {
                                                        val nextStops = currentSolo.stops + place
                                                        run {
                                                            updateSolo(currentSolo.copy(route = api.soloRoute(currentSolo.place, nextStops), stops = nextStops))
                                                            results = emptyList(); query = ""; screen = Screen.MAP
                                                            notice = "‘${place.name}’ 경유지를 추가했어요."
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (token != null && trip?.status == "PLANNING") T("가족 여행의 경유지·목적지를 바꾸려면 ‘가족 여행’ 메뉴를 써 주세요.", 17, color = CarColors.Sub)
                            }
                            Screen.TRIPS -> if (token != null) SidePanel(trip?.title ?: "가족 여행", error, { screen = Screen.MAP }, Modifier.fillMaxWidth(.66f)) {
                                val active = trips.filter { it.status != "DONE" }
                                val t = trip
                                val pending = t?.ideas?.filter { it.status == "PROPOSED" }.orEmpty()
                                // 고른 여행이 없거나 새로 만드는 중이면 '여행 목록'만 보여 준다.
                                val tab = if (t == null || showCreateTrip) 3 else tripsTab
                                Segmented(listOf(if (pending.isEmpty()) "가족 제안" else "가족 제안 ${pending.size}", "들르는 순서", "장소 추가", "여행 목록"), tab) { i -> tripsTab = i; showCreateTrip = false; error = ""; results = emptyList(); query = "" }
                                when {
                                    tab == 0 && t != null -> {
                                        if (pending.isEmpty()) T("기다리는 제안이 없어요. 가족이 휴대폰에서 장소를 제안하면 여기에 나타나요.", 19, color = CarColors.Sub)
                                        else CarButton(if (busy) "담는 중" else "제안 ${pending.size}곳 모두 코스에 담기", Modifier.fillMaxWidth(), enabled = !busy) {
                                            run {
                                                trip = api.setReady(token, t.id, ready = true, autoConfirmPending = true)
                                                trips = api.trips(token)
                                                updateSolo(null)
                                                notice = "가족 제안을 모두 코스에 담았어요."
                                            }
                                        }
                                        pending.forEach { p ->
                                            Card {
                                                Tag("${p.byName.take(12)}님 · ${if (p.kind == "DESTINATION") "목적지 제안" else "들르는 곳"}${if (p.yes > 0) " · 좋아요 ${p.yes}" else ""}")
                                                T(p.place.name, 24, FontWeight.Bold, maxLines = 1)
                                                if (p.reason.isNotBlank()) T("“${p.reason}”", 18, color = CarColors.Sub, maxLines = 2)
                                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    CarButton(if (p.kind == "DESTINATION") "목적지로 정하기" else "경로에 담기", Modifier.weight(1.4f), enabled = !busy) { run { trip = api.decide(token, p.id, true); updateSolo(null); notice = "${p.place.name} · 경로에 담았어요." } }
                                                    CarButton("이번엔 안 가요", Modifier.weight(1f), Btn.Soft, enabled = !busy) { run { trip = api.decide(token, p.id, false) } }
                                                }
                                            }
                                        }
                                    }
                                    tab == 1 && t != null -> {
                                        if (t.stops.isEmpty()) T("아직 들르는 곳이 없어요. ‘장소 추가’에서 넣을 수 있어요.", 19, color = CarColors.Sub)
                                        t.stops.forEachIndexed { i, p ->
                                            Card {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    T(if (p.status == "VISITED") "✓" else "${i + 1}", 22, FontWeight.Black, CarColors.Teal)
                                                    Column(Modifier.weight(1f)) {
                                                        T(p.place.name, 22, FontWeight.Bold, maxLines = 1)
                                                        T("${p.byName}님 제안" + (if (p.status == "VISITED") " · 다녀옴" else ""), 16, color = CarColors.Sub, maxLines = 1)
                                                    }
                                                    CarButton("위로", kind = Btn.Soft, enabled = !busy && i > 0) { val ids = t.stops.map { it.id }.toMutableList(); ids.add(i - 1, ids.removeAt(i)); run { trip = api.reorder(token, t.id, ids, t.route?.version) } }
                                                    CarButton("아래로", kind = Btn.Soft, enabled = !busy && i < t.stops.lastIndex) { val ids = t.stops.map { it.id }.toMutableList(); ids.add(i + 1, ids.removeAt(i)); run { trip = api.reorder(token, t.id, ids, t.route?.version) } }
                                                    if (p.status != "VISITED") {
                                                        CarButton("빼기", kind = Btn.Danger, enabled = !busy) {
                                                            run { trip = api.removeProposal(token, p.id); notice = "‘${p.place.name}’ 경유지를 뺐어요." }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        t.destination?.let { d ->
                                            Card {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Tag("도착지")
                                                    T(d.name, 22, FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                    tab == 2 && t != null -> {
                                        if (t.destination != null) {
                                            Segmented(listOf("경유지 추가 (${t.stops.size}/5)", "목적지 바꾸기"), if (tripSearchKind == "DESTINATION") 1 else 0) { idx ->
                                                tripSearchKind = if (idx == 1) "DESTINATION" else "STOP"
                                            }
                                        }
                                        val find = { run { results = api.search(token, query.trim()); if (results.isEmpty()) error = "검색 결과가 없어요." } }
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            TextInput(query, { v -> query = v }, if (t.destination != null && tripSearchKind == "STOP") "예: 해운대, 카페, 시장" else "예: 부산역", "여행 장소 검색어", Modifier.weight(1f)) { if (query.isNotBlank()) find() }
                                            CarButton(if (busy) "찾는 중" else "검색", enabled = !busy && query.isNotBlank(), icon = R.drawable.ic_nav_search) { find() }
                                        }
                                        if (results.isEmpty()) T(if (t.destination == null) "이 여행의 목적지를 검색해 정해 주세요." else "장소 이름을 검색해 주세요.", 19, color = CarColors.Sub)
                                        results.forEach { place ->
                                            Card {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Column(Modifier.weight(1f)) {
                                                        T(place.name, 22, FontWeight.Bold, maxLines = 1)
                                                        if (place.address.isNotBlank()) T(place.address, 17, color = CarColors.Sub, maxLines = 1)
                                                    }
                                                    if (t.destination != null && tripSearchKind == "STOP") {
                                                        CarButton("경유지로 추가", enabled = !busy && t.stops.size < 5) {
                                                            run { trip = api.addStop(token, t.id, place); updateSolo(null); results = emptyList(); query = ""; tripsTab = 1; notice = "‘${place.name}’ 경유지를 추가했어요." }
                                                        }
                                                    } else {
                                                        CarButton("목적지로 정하기", enabled = !busy) {
                                                            if (t.destination != null) pendingDestination = place
                                                            else run { trip = api.setDestination(token, t.id, place); updateSolo(null); results = emptyList(); query = ""; notice = "목적지를 ${place.name}(으)로 정했어요." }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    showCreateTrip || active.isEmpty() -> {
                                        Card {
                                            T("새 가족 여행방", 24, FontWeight.Black)
                                            T("만든 뒤 가족이 QR로 들어와 가고 싶은 곳을 제안할 수 있어요.", 18, color = CarColors.Sub)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                TextInput(newTripTitle, { v -> newTripTitle = v.take(40) }, "예: 주말 강릉 나들이", "새 가족 여행방 이름", Modifier.weight(1f))
                                                CarButton(if (busy) "만드는 중" else "만들기", enabled = !busy && newTripTitle.isNotBlank()) {
                                                    run {
                                                        val created = api.createTrip(token, newTripTitle.trim())
                                                        trips = api.trips(token); trip = created; updateTripId(created.id); updateSolo(null); showCreateTrip = false; tripsTab = 2
                                                        notice = "‘${created.title}’ 여행방을 만들었어요. 목적지를 정해 보세요."
                                                    }
                                                }
                                            }
                                        }
                                        if (active.isNotEmpty()) CarButton("여행 목록으로", Modifier.fillMaxWidth(), Btn.Soft) { showCreateTrip = false }
                                    }
                                    else -> {
                                        active.forEach { s ->
                                            Card {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                            Tag(statusLabel(s.status))
                                                            if (s.readyForCar && s.status == "PLANNING") Tag("출발 준비 완료", CarColors.Warm, CarColors.WarmInk)
                                                            if (s.id == tripId) Tag("지금 고른 여행", CarColors.OkBg, CarColors.Ok)
                                                        }
                                                        T(s.title, 24, FontWeight.Bold, maxLines = 1)
                                                        T("${s.date ?: "날짜 미정"} · ${s.destination?.name ?: "목적지 미정"} · 들르는 곳 ${s.stopCount}", 17, color = CarColors.Sub, maxLines = 1)
                                                    }
                                                    if (s.id != tripId || solo != null) CarButton("이 여행 안내", kind = Btn.Soft) { select(s.id) }
                                                }
                                            }
                                        }
                                        CarButton("새 여행방 만들기", Modifier.fillMaxWidth(), Btn.Line) { showCreateTrip = true }
                                    }
                                }
                            }
                            Screen.FAMILY -> if (token == null) SidePanel("가족 연결", error, { screen = Screen.MAP }, Modifier.fillMaxWidth(.64f)) {
                                Segmented(listOf("새 가족방 만들기", "코드로 연결"), joinTab) { i -> joinTab = i; error = "" }
                                if (joinTab == 0) Card {
                                    // ── 방법 1: 차량에서 바로 가족방 만들기 + QR 초대 ──
                                    T("이 차에서 가족방 만들기", 24, FontWeight.Black)
                                    T("만들면 초대 QR이 바로 나와요. 가족이 휴대폰 카메라로 찍으면 들어와요.", 18, color = CarColors.Ink)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextInput(newFamilyName, { v -> newFamilyName = v.take(30) }, "예: 우리 가족", "가족방 이름 입력", Modifier.weight(1.2f), 22)
                                        TextInput(carName, { v -> carName = v.take(20) }, "예: 아빠 차", "차량 이름 입력", Modifier.weight(1f), 22)
                                    }
                                    CarButton(if (busy) "만드는 중" else "가족방 만들기", Modifier.fillMaxWidth(), enabled = !busy && newFamilyName.isNotBlank()) {
                                        run {
                                            val (tk, info) = api.createFamily(newFamilyName.ifBlank { "우리 가족" }, carName.ifBlank { "우리 차" })
                                            val saved = SavedLink(tk, info.familyName, info.vehicleName)
                                            linkStore.save(saved); family = info; code = ""; link = saved; familyTab = 0
                                            phoneOrigin = api.phoneAppOrigin()
                                            notice = "‘${info.familyName}’ 가족방을 만들었어요. QR로 가족을 초대해 보세요."
                                        }
                                    }
                                } else Card {
                                    // ── 방법 2: 휴대폰에서 만든 가족방에 숫자 6자리로 연결 ──
                                    T("휴대폰에서 만든 가족방과 연결", 24, FontWeight.Black)
                                    T("휴대폰의 ‘우리 가족 → 차량 연결 코드 받기’에 나온 숫자 6자리를 적어 주세요.", 18, color = CarColors.Ink)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextInput(code, { v -> code = v.filter(Char::isDigit).take(6) }, "숫자 6자리", "차량 연결 코드 숫자 6자리 입력", Modifier.weight(1f), 26, numeric = true)
                                        TextInput(carName, { v -> carName = v.take(20) }, "차 이름", "차 이름 입력", Modifier.weight(1f), 22)
                                    }
                                    CarButton(if (busy) "연결하는 중" else "연결하기", Modifier.fillMaxWidth(), enabled = !busy && code.length == 6) {
                                        run {
                                            val (tk, info) = api.pair(code, carName.ifBlank { "우리 차" })
                                            val saved = SavedLink(tk, info.familyName, info.vehicleName)
                                            linkStore.save(saved); family = info; code = ""; link = saved; screen = Screen.MAP
                                            notice = "‘${info.familyName}’ 가족과 연결했어요."
                                        }
                                    }
                                }
                            } else SidePanel(family?.familyName ?: "가족", error, { screen = Screen.MAP }, Modifier.fillMaxWidth(.64f)) {
                                val members = family?.members.orEmpty()
                                Segmented(listOf("초대 QR", "구성원 ${members.size}명", "이 차"), familyTab) { i -> familyTab = i; confirmUnpair = false; error = "" }
                                when (familyTab) {
                                    0 -> {
                                        val inviteCode = family?.inviteCode.orEmpty()
                                        val qrOrigin = phoneOrigin ?: BuildConfig.FAMILY_INVITE_URL.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8789"
                                        val qrUrl = if (inviteCode.isNotBlank()) "$qrOrigin/?invite=$inviteCode" else "$qrOrigin/"
                                        Card {
                                            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                                                InviteQr(qrUrl, 230)
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    T("휴대폰 카메라로 찍으면 바로 들어와요", 24, FontWeight.Black)
                                                    if (inviteCode.isNotBlank()) T("초대 코드 · ${inviteCode.take(4)} ${inviteCode.drop(4)}", 22, FontWeight.Bold, CarColors.Teal)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        CarButton("크게 보기", Modifier.weight(1f), Btn.Primary) { showQrDialog = true }
                                                        CarButton(if (busy) "발급 중" else "새로 발급", Modifier.weight(1f), Btn.Soft, enabled = !busy) {
                                                            run {
                                                                val nextCode = api.refreshInvite(token)
                                                                family = family?.copy(inviteCode = nextCode) ?: api.home(token)
                                                                notice = "새 초대 QR 코드를 발급했어요."
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    1 -> Card {
                                        if (members.isEmpty()) T("아직 휴대폰으로 들어온 가족이 없어요. 처음 들어온 사람이 가족방 관리자가 돼요.", 19, color = CarColors.Sub)
                                        members.forEach { m -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { T(m.name, 22, FontWeight.Medium); if (m.role == "ADMIN") Tag("관리자") } }
                                    }
                                    else -> {
                                        Card {
                                            T(family?.vehicleName ?: link?.vehicleName ?: "", 24, FontWeight.Black)
                                            T(originLabel, 17, color = CarColors.Sub)
                                        }
                                        if (!confirmUnpair) CarButton("가족 연결 끊기", Modifier.fillMaxWidth(), Btn.Line) { confirmUnpair = true }
                                        else Card {
                                            T("연결을 끊으면 이 차에서 가족 여행을 볼 수 없어요. 혼자 운전 길안내는 그대로 쓸 수 있어요.", 19, color = CarColors.Ink)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                CarButton("취소", Modifier.weight(1f), Btn.Soft) { confirmUnpair = false }
                                                CarButton("연결 끊기", Modifier.weight(1f), Btn.Danger, enabled = !busy) { run { try { api.unpair(token) } catch (e: ApiError) { if (e.status != 401) throw e }; forget("가족 연결을 끊었어요.") } }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val message = notice.ifBlank { if (screen == Screen.MAP) error else "" }
                        val corner = if (incoming != null && screen == Screen.MAP) Alignment.BottomStart else Alignment.BottomCenter
                        if (message.isNotBlank()) T(message, 21, FontWeight.Bold, Color.White, Modifier.align(corner).padding(start = 22.dp, bottom = 22.dp).widthIn(max = 720.dp).clip(RoundedCornerShape(16.dp)).background(if (notice.isBlank()) CarColors.Bad else Color(0xFF10273F)).clickable { notice = ""; error = "" }.padding(horizontal = 22.dp, vertical = 16.dp))
                    }
                }
                // ── 들르는 곳 도착: 그곳을 제안한 가족의 이야기 ──
                val a = arrival
                if (a != null && parked) CenterDialog {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Tag("경유지 도착", CarColors.OkBg, CarColors.Ok)
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(CarColors.Tint).clickable { tts.speak(arrivalSpeechText(a)) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            T("음성으로 듣기", 17, FontWeight.Bold, CarColors.Navy)
                        }
                    }
                    T("${a.place.name}에 도착했어요", 30, FontWeight.Black)
                    if (a.stories.isEmpty()) T("남겨진 이야기가 없어요.", 20, color = CarColors.Sub)
                    a.stories.forEach { s ->
                        Card {
                            T("${s.by}님의 이야기", 21, FontWeight.Bold)
                            if (s.reason.isNotBlank()) T("가고 싶었던 이유 · ${s.reason}", 20, color = CarColors.Ink)
                            if (s.message.isNotBlank()) T("가족에게 · ${s.message}", 20, color = CarColors.Ink)
                            s.comments.take(2).forEach { c -> T(c, 17, color = CarColors.Sub, maxLines = 1) }
                        }
                    }
                    T("사진은 가족이 휴대폰에서 이 장소에 올릴 수 있어요.", 17, color = CarColors.Sub)
                    CarButton("확인", Modifier.fillMaxWidth()) { tts.stop(); arrival = null }
                }
                val tk = link?.token
                val current = trip
                if (showQrDialog && parked && tk != null) CenterDialog {
                    val inviteCode = family?.inviteCode.orEmpty()
                    val qrOrigin = phoneOrigin ?: BuildConfig.FAMILY_INVITE_URL.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8789"
                    val qrUrl = if (inviteCode.isNotBlank()) "$qrOrigin/?invite=$inviteCode" else "$qrOrigin/"
                    Tag("휴대폰 카메라로 스캔", CarColors.OkBg, CarColors.Ok)
                    T("‘${family?.familyName ?: link?.familyName ?: "우리 가족"}’ 방 QR 초대", 28, FontWeight.Black)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        InviteQr(qrUrl, 220)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (inviteCode.isNotBlank()) {
                                T("초대 코드", 18, FontWeight.Bold, CarColors.Sub)
                                T("${inviteCode.take(4)} ${inviteCode.drop(4)}", 30, FontWeight.Black, CarColors.Navy)
                            }
                            T("다른 참가자가 핸드폰으로 이 QR을 찍으면 ‘${family?.familyName ?: "우리 가족"}’ 방에 바로 참여해 장소를 제안하고 사진을 함께 볼 수 있어요.", 19, color = CarColors.Ink)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CarButton(if (busy) "발급 중" else "새 초대 코드 발급", Modifier.weight(1f), Btn.Soft, enabled = !busy) {
                            run {
                                val nextCode = api.refreshInvite(tk)
                                family = family?.copy(inviteCode = nextCode) ?: api.home(tk)
                                notice = "새 초대 QR 코드를 발급했어요."
                            }
                        }
                        CarButton("닫기", Modifier.weight(1f), Btn.Primary) { showQrDialog = false }
                    }
                }
                if (confirmFinish && parked && tk != null && current != null) CenterDialog {
                    T("여행을 끝낼까요?", 30, FontWeight.Black)
                    T("마치면 가족의 추억 앨범에 담겨요. 잘못 출발했다면 출발만 취소해 준비 중으로 되돌릴 수 있어요.", 20, color = CarColors.Sub)
                    CarButton("계속 여행하기", Modifier.fillMaxWidth()) { confirmFinish = false }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarButton("여행 마치기", Modifier.weight(1f), Btn.Soft, enabled = !busy) { run { api.finish(tk, current.id); confirmFinish = false; trip = null; updateTripId(null); notice = "여행을 마쳤어요. 가족의 추억 앨범에 담겼어요." } }
                        CarButton("출발 취소", Modifier.weight(1f), Btn.Danger, enabled = !busy) { run { trip = api.cancelStart(tk, current.id); trips = api.trips(tk); confirmFinish = false; notice = "출발을 취소했어요. 준비 중으로 돌아왔어요." } }
                    }
                }
                if (confirmCancelTrip && parked && tk != null && current != null) CenterDialog {
                    T("‘${current.title}’ 여행을 취소할까요?", 30, FontWeight.Black, maxLines = 2)
                    T("코스만 비우면 가족이 남긴 한마디는 제안으로 남아요. 여행방을 지우면 제안과 사진까지 모두 사라져요.", 20, color = CarColors.Ink)
                    CarButton("그대로 두기", Modifier.fillMaxWidth()) { confirmCancelTrip = false }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarButton("코스만 비우기", Modifier.weight(1f), Btn.Soft, enabled = !busy && (current.destination != null || current.stops.isNotEmpty())) { run { trip = api.clearCourse(tk, current.id); trips = api.trips(tk); confirmCancelTrip = false; notice = "코스를 비웠어요. 목적지부터 다시 정해 보세요." } }
                        CarButton("여행방 지우기", Modifier.weight(1f), Btn.Danger, enabled = !busy) { run { api.deleteTrip(tk, current.id); confirmCancelTrip = false; trip = null; updateTripId(null); trips = api.trips(tk); notice = "여행방을 지웠어요." } }
                    }
                }
                val place = pendingDestination
                if (place != null && parked && tk != null && current != null) CenterDialog {
                    T("여행 목적지를 바꿀까요?", 30, FontWeight.Black)
                    T("가족 여행의 목적지가 ${place.name}(으)로 바뀌고 경로를 다시 찾아요. 들르는 곳은 그대로예요.", 20, color = CarColors.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarButton("그대로 두기", Modifier.weight(1.3f)) { pendingDestination = null }
                        CarButton("바꾸기", Modifier.weight(1f), Btn.Danger) { pendingDestination = null; run { trip = api.setDestination(tk, current.id, place); updateSolo(null); results = emptyList(); query = ""; notice = "목적지를 ${place.name}(으)로 바꿨어요." } }
                    }
                }
            }
            }
        }
    }
}

private fun statusLabel(s: String) = when (s) { "ONGOING" -> "여행 중"; "DONE" -> "다녀옴"; else -> "준비 중" }
private fun formatMinutes(minutes: Int) = if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"

@Composable private fun FamilyMap(pointsJson: String?, stopsJson: String, parked: Boolean, modifier: Modifier) {
    var loaded by remember { mutableStateOf(false) }
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(message: ConsoleMessage): Boolean { Log.w("FamilyMap", "map script: ${message.message()}"); return true } }
            webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String) { loaded = true; view.postDelayed({ val h = (view.height / view.resources.displayMetrics.density).toInt().coerceAtLeast(1); view.evaluateJavascript("window.resizeMap($h)", null) }, 300) } }
            // 키보드가 올라왔다 내려가는 등 크기가 바뀌면 지도를 다시 맞춘다.
            addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom -> if (loaded && bottom - top != oldBottom - oldTop) { val h = ((bottom - top) / v.resources.displayMetrics.density).toInt(); if (h > 0) (v as WebView).evaluateJavascript("window.resizeMap($h)", null) } }
            loadUrl("file:///android_asset/map/index.html")
        }
    }, update = { web ->
        if (loaded) {
            val h = (web.height / web.resources.displayMetrics.density).toInt(); if (h > 0) web.evaluateJavascript("window.resizeMap($h)", null)
            // 주행 중에는 지도를 끌거나 확대할 수 없게 잠근다.
            web.evaluateJavascript("window.setLocked(${!parked})", null)
            if (pointsJson != null) web.evaluateJavascript("window.showRoute(JSON.parse(${JSONObject.quote(pointsJson)}),JSON.parse(${JSONObject.quote(stopsJson)}))", null)
            else web.evaluateJavascript("window.clearRoute()", null)
        }
    })
}
