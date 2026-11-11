package ai.familyroute.demo

import android.os.Bundle
import android.util.Log
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

/** 혼자 운전하는 길: 가족 여행과 상관없이 이 차에서만 쓰고, 서버에 남지 않는다. */
private data class SoloDrive(val place: Place, val route: RouteLine)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val api = remember { CarApi() }
            val linkStore = remember { VehicleLink(applicationContext) }
            val parking = remember { ParkingGuard(applicationContext) }
            val parked by parking.allowed.collectAsStateWithLifecycle()
            DisposableEffect(parking) { onDispose { parking.close() } }
            val scope = rememberCoroutineScope()

            var link by remember { mutableStateOf(linkStore.load()) }
            var family by remember { mutableStateOf<FamilyInfo?>(null) }
            var trips by remember { mutableStateOf<List<TripSummary>>(emptyList()) }
            var tripId by remember { mutableStateOf<String?>(null) }
            var trip by remember { mutableStateOf<TripDetail?>(null) }
            var solo by remember { mutableStateOf<SoloDrive?>(null) }
            var screen by remember { mutableStateOf(Screen.MAP) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            var notice by remember { mutableStateOf("") }
            var offline by remember { mutableStateOf(false) }
            var incoming by remember { mutableStateOf<Idea?>(null) }
            var arrival by remember { mutableStateOf<Arrival?>(null) }
            var confirmFinish by remember { mutableStateOf(false) }
            var confirmUnpair by remember { mutableStateOf(false) }
            var pendingDestination by remember { mutableStateOf<Place?>(null) }
            var code by remember { mutableStateOf("") }
            var carName by remember { mutableStateOf("우리 차") }
            var query by remember { mutableStateOf("") }
            var results by remember { mutableStateOf<List<Place>>(emptyList()) }
            var phoneOrigin by remember { mutableStateOf<String?>(null) }

            fun forget(message: String) {
                linkStore.clear(); link = null; family = null; trips = emptyList(); trip = null; tripId = null; incoming = null; arrival = null
                confirmFinish = false; confirmUnpair = false; pendingDestination = null; if (screen == Screen.TRIPS) screen = Screen.MAP; notice = message
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
            fun select(id: String) { tripId = id; trip = null; incoming = null; solo = null; screen = Screen.MAP }
            fun openSearch() { screen = Screen.SEARCH; error = ""; results = emptyList() }

            // 가족과 연결된 동안 3초마다 가족 정보, 여행 목록, 고른 여행을 새로 받는다.
            LaunchedEffect(link?.token) {
                val token = link?.token ?: return@LaunchedEffect
                val seen = mutableSetOf<String>(); var first = true
                while (true) {
                    try {
                        family = api.home(token)
                        trips = api.trips(token)
                        // 고른 여행이 없거나 끝났으면 진행 중인 여행, 없으면 준비 중인 여행을 고른다.
                        if (tripId == null || trips.none { it.id == tripId && it.status != "DONE" })
                            tripId = trips.firstOrNull { it.status == "ONGOING" }?.id ?: trips.firstOrNull { it.status == "PLANNING" }?.id
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
            LaunchedEffect(screen) { if (screen == Screen.FAMILY && link != null) phoneOrigin = api.phoneAppOrigin() }
            LaunchedEffect(parked) {
                if (!parked) {
                    if (screen != Screen.MAP || arrival != null || confirmFinish || pendingDestination != null) notice = "출발해서 화면을 닫았어요."
                    screen = Screen.MAP; arrival = null; confirmFinish = false; confirmUnpair = false; pendingDestination = null
                }
            }
            LaunchedEffect(incoming?.id, parked) { if (incoming != null && !parked) { delay(4000); incoming = null } }
            LaunchedEffect(notice) { if (notice.isNotBlank()) { delay(5000); notice = "" } }

            // 지도에 그릴 길: 혼자 운전 중이면 그 길, 아니면 고른 가족 여행의 길.
            val shownSolo = solo
            val mapPoints = shownSolo?.route?.pointsJson ?: trip?.route?.pointsJson
            val mapStops = if (shownSolo != null) "[]" else trip?.stopsJson() ?: "[]"

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
                                Column(Modifier.align(Alignment.TopStart).padding(18.dp).width(440.dp).heightIn(max = 660.dp).background(CarColors.Navy, RoundedCornerShape(24.dp)).padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (shownSolo != null) {
                                        // ── 혼자 운전 ──
                                        Tag("길안내", Color.White, CarColors.Navy)
                                        T(shownSolo.place.name, 30, FontWeight.Black, Color.White, maxLines = 2)
                                        T("${"%.1f".format(shownSolo.route.distance / 1000.0)}km · 약 ${formatMinutes(shownSolo.route.duration / 60)}", 21, FontWeight.Bold, CarColors.OnNavySub)
                                        if (shownSolo.place.address.isNotBlank()) T(shownSolo.place.address, 18, color = CarColors.OnNavySub, maxLines = 2)
                                        if (parked) {
                                            CarButton("다른 목적지 찾기", Modifier.fillMaxWidth(), Btn.OnNavyStrong) { openSearch() }
                                            CarButton("안내 끝내기", Modifier.fillMaxWidth(), Btn.OnNavy) { solo = null; notice = "길안내를 끝냈어요." }
                                            T("출발 위치는 서울 (시연)", 16, color = CarColors.OnNavySub)
                                        } else T("주행 중에는 지도만 보여요.\n정차하면 다시 쓸 수 있어요.", 22, FontWeight.Bold, Color.White)
                                    } else if (token == null) {
                                        // ── 가족 연결 전: 혼자 운전부터 바로 쓸 수 있게 ──
                                        T("어디로 갈까요?", 30, FontWeight.Black, Color.White)
                                        T("목적지를 찾으면 바로 길안내를 시작해요.", 19, color = CarColors.OnNavySub)
                                        if (parked) {
                                            CarButton("목적지 찾기", Modifier.fillMaxWidth(), Btn.OnNavyStrong, icon = R.drawable.ic_nav_search) { openSearch() }
                                            CarButton("가족과 연결하기", Modifier.fillMaxWidth(), Btn.OnNavy) { screen = Screen.FAMILY; error = "" }
                                            T("가족과 연결하면 가족이 휴대폰에서 만든 여행과 제안을 이 차에서 볼 수 있어요. 연결하지 않아도 길안내는 쓸 수 있어요.", 17, color = CarColors.OnNavySub)
                                        } else T("주행 중에는 지도만 보여요.\n정차하면 다시 쓸 수 있어요.", 22, FontWeight.Bold, Color.White)
                                    } else {
                                        T(family?.familyName ?: link?.familyName ?: "", 18, FontWeight.Bold, CarColors.OnNavySub)
                                        if (t == null) {
                                            T(if (trips.none { it.status != "DONE" }) "준비 중인 가족 여행이 없어요" else "여행을 골라 주세요", 30, FontWeight.Black, Color.White)
                                            T("가족 여행은 휴대폰에서 만들어요. 혼자 가는 길은 ‘목적지’에서 바로 찾을 수 있어요.", 19, color = CarColors.OnNavySub)
                                            if (parked) {
                                                CarButton("목적지 찾기", Modifier.fillMaxWidth(), Btn.OnNavyStrong, icon = R.drawable.ic_nav_search) { openSearch() }
                                                if (trips.any { it.status != "DONE" }) CarButton("가족 여행 고르기", Modifier.fillMaxWidth(), Btn.OnNavy) { screen = Screen.TRIPS }
                                            }
                                        } else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tag(statusLabel(t.status), if (t.status == "ONGOING") Color.White else Color(0x33FFFFFF), if (t.status == "ONGOING") CarColors.Navy else Color.White) }
                                            T(t.title, 30, FontWeight.Black, Color.White, maxLines = 2)
                                            val route = t.route
                                            T(when {
                                                route != null -> "${t.destination?.name ?: ""} · ${"%.1f".format(route.distance / 1000.0)}km · 약 ${formatMinutes(route.duration / 60)}"
                                                t.destination != null -> "${t.destination.name} · 경로를 아직 찾지 못했어요"
                                                else -> "목적지를 아직 정하지 않았어요"
                                            }, 21, FontWeight.Bold, CarColors.OnNavySub)
                                            StopList(t.stops.map { (if (it.status == "VISITED") "✓ " else "") + it.place.name } + listOfNotNull(t.destination?.name), onNavy = true)
                                            if (parked) {
                                                val next = t.stops.firstOrNull { it.status != "VISITED" }
                                                when (t.status) {
                                                    "PLANNING" -> CarButton(if (busy) "출발 준비 중" else "출발하기", Modifier.fillMaxWidth(), Btn.OnNavyStrong, enabled = !busy && t.destination != null) {
                                                        run { trip = api.start(token, t.id); notice = "출발했어요. 들르는 곳에 정차하면 ‘도착’을 눌러 가족의 이야기를 확인해요." }
                                                    }
                                                    "ONGOING" -> {
                                                        if (next != null) CarButton("${next.place.name} 도착", Modifier.fillMaxWidth(), Btn.OnNavyStrong, enabled = !busy) { run { val a = api.arrive(token, next.id); trip = a.trip; arrival = a } }
                                                        CarButton("여행 마치기", Modifier.fillMaxWidth(), Btn.OnNavy, enabled = !busy) { confirmFinish = true }
                                                    }
                                                }
                                                if (t.destination == null) T("목적지는 ‘가족 여행’ 메뉴에서 정할 수 있어요.", 17, color = CarColors.OnNavySub)
                                                T("출발 위치는 서울 (시연)", 16, color = CarColors.OnNavySub)
                                            } else T("주행 중에는 지도만 보여요.\n정차하면 다시 쓸 수 있어요.", 22, FontWeight.Bold, Color.White)
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
                                                CarButton(if (p.kind == "DESTINATION") "목적지로 정하기" else "경로에 담기", Modifier.weight(1.4f), enabled = !busy) { run { trip = api.decide(token, p.id, true); incoming = null; solo = null; notice = "${p.place.name} · 경로에 담았어요." } }
                                                CarButton("나중에 보기", Modifier.weight(1f), Btn.Soft) { incoming = null }
                                            }
                                        } else T("가족 제안이 도착했어요 · 정차 후 확인", 24, FontWeight.Bold, maxLines = 1)
                                    }
                                }
                            }
                            Screen.SEARCH -> SidePanel("목적지 찾기", error, { screen = Screen.MAP }, Modifier.fillMaxWidth(.6f)) {
                                val find = { run { results = api.search(token, query.trim()); if (results.isEmpty()) error = "검색 결과가 없어요." } }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextInput(query, { v -> query = v }, "예: 부산역", "목적지 검색어", Modifier.weight(1f)) { if (query.isNotBlank()) find() }
                                    CarButton(if (busy) "찾는 중" else "검색", enabled = !busy && query.isNotBlank(), icon = R.drawable.ic_nav_search) { find() }
                                }
                                if (results.isEmpty()) T("장소 이름이나 지역을 적어 주세요. 바로 길안내를 시작해요.", 19, color = CarColors.Sub)
                                results.forEach { place ->
                                    Card {
                                        T(place.name, 22, FontWeight.Bold, maxLines = 1)
                                        if (place.address.isNotBlank()) T(place.address, 17, color = CarColors.Sub, maxLines = 1)
                                        CarButton("여기로 안내", Modifier.fillMaxWidth(), enabled = !busy, icon = R.drawable.ic_flag) {
                                            run { solo = SoloDrive(place, api.soloRoute(place)); results = emptyList(); query = ""; screen = Screen.MAP; notice = "${place.name}(으)로 안내를 시작해요." }
                                        }
                                    }
                                }
                                if (token != null && trip?.status == "PLANNING") T("가족 여행의 목적지를 바꾸려면 ‘가족 여행’ 메뉴를 써 주세요. 여기서 고른 곳은 이 차에서만 안내해요.", 17, color = CarColors.Sub)
                            }
                            Screen.TRIPS -> if (token != null) SidePanel("가족 여행", error, { screen = Screen.MAP }, Modifier.fillMaxWidth(.66f)) {
                                val active = trips.filter { it.status != "DONE" }
                                if (active.isEmpty()) T("준비 중인 여행이 없어요. 가족이 휴대폰에서 여행을 만들면 여기에 나타나요.", 20, color = CarColors.Sub)
                                active.forEach { s ->
                                    Card {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tag(statusLabel(s.status)); if (s.id == tripId) Tag("지금 고른 여행", CarColors.OkBg, CarColors.Ok) }
                                                T(s.title, 24, FontWeight.Bold, maxLines = 1)
                                                T("${s.date ?: "날짜 미정"} · ${s.destination?.name ?: "목적지 미정"} · 들르는 곳 ${s.stopCount} · 새 제안 ${s.pendingCount}", 17, color = CarColors.Sub, maxLines = 1)
                                            }
                                            if (s.id != tripId || solo != null) CarButton("이 여행 안내", kind = Btn.Soft) { select(s.id) }
                                        }
                                    }
                                }
                                val t = trip
                                if (t != null) {
                                    T("‘${t.title}’ 가족 제안", 23, FontWeight.Bold)
                                    val pending = t.ideas.filter { it.status == "PROPOSED" }
                                    if (pending.isEmpty()) T("기다리는 제안이 없어요.", 19, color = CarColors.Sub)
                                    pending.forEach { p ->
                                        Card {
                                            Tag("${p.byName.take(12)}님 · ${if (p.kind == "DESTINATION") "목적지 제안" else "들르는 곳"}${if (p.yes > 0) " · 좋아요 ${p.yes}" else ""}")
                                            T(p.place.name, 24, FontWeight.Bold, maxLines = 1)
                                            if (p.reason.isNotBlank()) T("“${p.reason}”", 18, color = CarColors.Sub, maxLines = 2)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                CarButton(if (p.kind == "DESTINATION") "목적지로 정하기" else "경로에 담기", Modifier.weight(1.4f), enabled = !busy) { run { trip = api.decide(token, p.id, true); notice = "${p.place.name} · 경로에 담았어요." } }
                                                CarButton("이번엔 안 가요", Modifier.weight(1f), Btn.Soft, enabled = !busy) { run { trip = api.decide(token, p.id, false) } }
                                            }
                                        }
                                    }
                                    if (t.stops.isNotEmpty() && t.status == "PLANNING") {
                                        T("들르는 순서", 23, FontWeight.Bold)
                                        t.stops.forEachIndexed { i, p ->
                                            Card {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    T("${i + 1}", 22, FontWeight.Black, CarColors.Teal)
                                                    T(p.place.name, 22, FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                                                    CarButton("위로", kind = Btn.Soft, enabled = !busy && i > 0) { val ids = t.stops.map { it.id }.toMutableList(); ids.add(i - 1, ids.removeAt(i)); run { trip = api.reorder(token, t.id, ids, t.route?.version) } }
                                                    CarButton("아래로", kind = Btn.Soft, enabled = !busy && i < t.stops.lastIndex) { val ids = t.stops.map { it.id }.toMutableList(); ids.add(i + 1, ids.removeAt(i)); run { trip = api.reorder(token, t.id, ids, t.route?.version) } }
                                                }
                                            }
                                        }
                                    }
                                    if (t.status == "PLANNING") {
                                        T(if (t.destination == null) "여행 목적지 정하기" else "여행 목적지 바꾸기", 23, FontWeight.Bold)
                                        val find = { run { results = api.search(token, query.trim()); if (results.isEmpty()) error = "검색 결과가 없어요." } }
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            TextInput(query, { v -> query = v }, "예: 부산역", "여행 목적지 검색어", Modifier.weight(1f)) { if (query.isNotBlank()) find() }
                                            CarButton(if (busy) "찾는 중" else "검색", enabled = !busy && query.isNotBlank(), icon = R.drawable.ic_nav_search) { find() }
                                        }
                                        results.forEach { place ->
                                            Card {
                                                T(place.name, 22, FontWeight.Bold, maxLines = 1)
                                                if (place.address.isNotBlank()) T(place.address, 17, color = CarColors.Sub, maxLines = 1)
                                                CarButton("여행 목적지로 정하기", Modifier.fillMaxWidth(), enabled = !busy, icon = R.drawable.ic_flag) {
                                                    if (t.destination != null) pendingDestination = place
                                                    else run { trip = api.setDestination(token, t.id, place); solo = null; results = emptyList(); query = ""; notice = "목적지를 ${place.name}(으)로 정했어요." }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Screen.FAMILY -> if (token == null) SidePanel("가족과 연결하기", error, { screen = Screen.MAP }, Modifier.fillMaxWidth(.6f)) {
                                // ── 가족 연결(선택) ──
                                T("가족과 연결하면 가족이 휴대폰에서 만든 여행·제안을 이 차에서 보고, 경로에 담고, 도착하면 가족의 이야기를 볼 수 있어요.", 19, color = CarColors.Ink)
                                Card {
                                    T("① 가족 휴대폰에서 아빠어디가 → 우리 가족 → ‘차량 연결 코드 받기’를 눌러요.\n② 화면에 나온 숫자 6자리를 아래에 적어요.", 19, color = CarColors.Ink)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextInput(code, { v -> code = v.filter(Char::isDigit).take(6) }, "숫자 6자리", "차량 연결 코드 숫자 6자리 입력", Modifier.weight(1f), 28, numeric = true)
                                        TextInput(carName, { v -> carName = v.take(20) }, "차 이름", "차 이름 입력", Modifier.weight(1f), 24)
                                    }
                                    CarButton(if (busy) "연결하는 중" else "가족과 연결하기", Modifier.fillMaxWidth(), enabled = !busy && code.length == 6) {
                                        run {
                                            val (tk, info) = api.pair(code, carName.ifBlank { "우리 차" })
                                            val saved = SavedLink(tk, info.familyName, info.vehicleName)
                                            linkStore.save(saved); family = info; code = ""; link = saved; screen = Screen.MAP
                                            notice = "‘${info.familyName}’ 가족과 연결했어요."
                                        }
                                    }
                                }
                                T("연결하지 않아도 ‘목적지’에서 길안내는 그대로 쓸 수 있어요.", 17, color = CarColors.Sub)
                            } else SidePanel(family?.familyName ?: "가족", error, { screen = Screen.MAP }, Modifier.fillMaxWidth(.6f)) {
                                Card {
                                    T("이 차량", 20, FontWeight.Bold, CarColors.Sub)
                                    T(family?.vehicleName ?: link?.vehicleName ?: "", 26, FontWeight.Black)
                                    T("이 가족의 여행을 보고, 경로 확정과 출발·도착 기록을 할 수 있어요. 가족 초대·탈퇴 같은 관리는 휴대폰에서 해요.", 18, color = CarColors.Sub)
                                }
                                Card {
                                    T("가족 구성원 ${family?.members?.size ?: 0}명", 22, FontWeight.Bold)
                                    family?.members?.forEach { m -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { T(m.name, 21, FontWeight.Medium); if (m.role == "ADMIN") Tag("관리자") } }
                                }
                                phoneOrigin?.let { origin ->
                                    Card {
                                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                            InviteQr("$origin/", 170)
                                            T("휴대폰으로 찍으면 아빠어디가가 열려요. 각자 계정으로 로그인한 뒤 장소를 제안하고 사진을 올려요.", 19, color = CarColors.Ink, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                                if (!confirmUnpair) CarButton("이 차의 가족 연결 끊기", Modifier.fillMaxWidth(), Btn.Line) { confirmUnpair = true }
                                else Card {
                                    T("연결을 끊으면 이 차에서 가족 여행을 볼 수 없어요. 혼자 운전 길안내는 그대로 쓸 수 있어요.", 19, color = CarColors.Ink)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CarButton("취소", Modifier.weight(1f), Btn.Soft) { confirmUnpair = false }
                                        CarButton("연결 끊기", Modifier.weight(1f), Btn.Danger, enabled = !busy) { run { try { api.unpair(token) } catch (e: ApiError) { if (e.status != 401) throw e }; forget("가족 연결을 끊었어요.") } }
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
                    T("${a.place.name}에 도착했어요", 30, FontWeight.Black)
                    if (a.stories.isEmpty()) T("남겨진 이야기가 없어요.", 20, color = CarColors.Sub)
                    a.stories.forEach { s ->
                        Card {
                            T("${s.by}님의 이야기", 21, FontWeight.Bold)
                            if (s.reason.isNotBlank()) T("가고 싶었던 이유 · ${s.reason}", 20, color = CarColors.Ink)
                            if (s.message.isNotBlank()) T("가족에게 · ${s.message}", 20, color = CarColors.Ink)
                        }
                    }
                    T("사진은 가족이 휴대폰에서 이 장소에 올릴 수 있어요.", 17, color = CarColors.Sub)
                    CarButton("확인", Modifier.fillMaxWidth()) { arrival = null }
                }
                val tk = link?.token
                val current = trip
                if (confirmFinish && parked && tk != null && current != null) CenterDialog {
                    T("여행을 마칠까요?", 30, FontWeight.Black)
                    T("마치면 이 여행은 가족의 추억 앨범으로 옮겨져요. 들르지 못한 곳도 기록에 남아요.", 20, color = CarColors.Sub)
                    CarButton("계속 여행하기", Modifier.fillMaxWidth()) { confirmFinish = false }
                    CarButton("여행 마치기", Modifier.fillMaxWidth(), Btn.Soft, enabled = !busy) { run { api.finish(tk, current.id); confirmFinish = false; trip = null; tripId = null; notice = "여행을 마쳤어요. 가족의 추억 앨범에 담겼어요." } }
                }
                val place = pendingDestination
                if (place != null && parked && tk != null && current != null) CenterDialog {
                    T("여행 목적지를 바꿀까요?", 30, FontWeight.Black)
                    T("가족 여행의 목적지가 ${place.name}(으)로 바뀌고 경로를 다시 찾아요. 들르는 곳은 그대로예요.", 20, color = CarColors.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarButton("그대로 두기", Modifier.weight(1.3f)) { pendingDestination = null }
                        CarButton("바꾸기", Modifier.weight(1f), Btn.Danger) { pendingDestination = null; run { trip = api.setDestination(tk, current.id, place); solo = null; results = emptyList(); query = ""; notice = "목적지를 ${place.name}(으)로 바꿨어요." } }
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
