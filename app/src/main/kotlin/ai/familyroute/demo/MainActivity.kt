package ai.familyroute.demo

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.familyroute.demo.car.*
import ai.familyroute.demo.phone.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 차량 화면에서 지금 열려 있는 곳. 지도 외의 화면은 정차 중에만 열린다. */
private enum class Screen { MAP, FIND, FAMILY, PHOTOS, ORDER }

private const val LOST = "연결이 끊겼어요. 잠시 후 다시 확인해 주세요."

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val client = remember { FamilyRoomClient() }
            val historyStore = remember { RoomHistory(applicationContext) }
            val parking = remember { ParkingGuard(applicationContext) }
            val parked by parking.allowed.collectAsStateWithLifecycle()
            DisposableEffect(parking) { onDispose { parking.close() } }
            val scope = rememberCoroutineScope()

            // 가족방과 경로
            var room by remember { mutableStateOf<LiveRoom?>(null) }
            var roomName by remember { mutableStateOf("") }
            var roomHistory by remember { mutableStateOf(historyStore.load()) }
            var proposals by remember { mutableStateOf<List<LiveProposal>>(emptyList()) }
            var incoming by remember { mutableStateOf<LiveProposal?>(null) }
            var route by remember { mutableStateOf<LiveRoute?>(null) }
            var waypoints by remember { mutableStateOf<List<LiveWaypoint>>(emptyList()) }
            var itineraryVersion by remember { mutableIntStateOf(-1) }
            var familyPhotos by remember { mutableStateOf<List<FamilyPhoto>>(emptyList()) }

            // 화면 상태
            var screen by remember { mutableStateOf(Screen.MAP) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            var notice by remember { mutableStateOf("") }
            // 연결이 끊긴 것은 오류 문구와 따로 다룬다(운전 중에는 여행 카드의 한 줄로만 보인다).
            var offline by remember { mutableStateOf(false) }
            // 목적지를 먼저 검색해서 자동으로 생긴 방. 가족이 아직 고르지 않은 방이라 '쓰던 가족방'에 남기지 않는다.
            var autoRoom by remember { mutableStateOf(false) }
            // 지금 경로나 가족방이 바뀌는 동작은 한 번 물어본 뒤에 한다.
            var pendingReplace by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
            var confirmForget by remember { mutableStateOf<String?>(null) }
            var finishDialog by remember { mutableStateOf(false) }
            var lastTripFromCourse by remember { mutableStateOf(false) }
            var memoryDialog by remember { mutableStateOf(false) }

            // 찾기
            var findCourses by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var results by remember { mutableStateOf<List<SearchPlace>>(emptyList()) }
            var selected by remember { mutableStateOf<SearchPlace?>(null) }
            var courses by remember { mutableStateOf<List<SharedCourse>>(emptyList()) }
            var coursesStatus by remember { mutableStateOf("") }
            var courseView by remember { mutableIntStateOf(0) }
            var regions by remember { mutableStateOf(TrendingRegions(false, emptyList())) }
            var shownReviews by remember { mutableStateOf<Map<String, List<CourseReview>>>(emptyMap()) }
            var nearby by remember { mutableStateOf<List<SharedCourse>>(emptyList()) }
            var showPreferences by remember { mutableStateOf(false) }
            var companions by remember { mutableStateOf<Set<String>>(emptySet()) }
            var travelStyles by remember { mutableStateOf<Set<String>>(emptySet()) }
            var travelHours by remember { mutableStateOf<Int?>(null) }

            // 가족방 연결, 순서 바꾸기, 사진
            var pairingCode by remember { mutableStateOf("") }
            // 지금 경로가 추천받은 코스에서 시작됐으면 그 코스 이름. 이 여행은 다시 공유하지 않고 후기를 남긴다.
            var fromCourseTitle by remember { mutableStateOf("") }
            var switchingRoom by remember { mutableStateOf(false) }
            var editWaypoints by remember { mutableStateOf<List<LiveWaypoint>>(emptyList()) }
            var editVersion by remember { mutableIntStateOf(-1) }
            var photoImages by remember { mutableStateOf<List<Pair<FamilyPhoto, ImageBitmap>>>(emptyList()) }
            var photoRoom by remember { mutableStateOf<LiveRoom?>(null) }
            var albumPhotos by remember { mutableStateOf<List<FamilyPhoto>>(emptyList()) }
            var deletePhotoTarget by remember { mutableStateOf<FamilyPhoto?>(null) }
            var photoDeleting by remember { mutableStateOf(false) }
            var photoStatus by remember { mutableStateOf("") }

            fun applyState(s: LiveRoomState) {
                proposals = s.proposals; route = s.route; waypoints = s.waypoints; itineraryVersion = s.itineraryVersion; familyPhotos = s.photos; fromCourseTitle = s.fromCourseTitle
                if (s.roomName.isNotBlank()) roomName = s.roomName
            }
            fun open(next: Screen) { screen = next; error = ""; switchingRoom = false; photoRoom = null; photoImages = emptyList(); albumPhotos = emptyList(); deletePhotoTarget = null; photoStatus = "" }
            /** 한 번에 하나씩만 실행하고, 실패하면 이유를 화면에 보여 준다. */
            fun run(failure: String, block: suspend () -> Unit) {
                if (busy) return
                busy = true; error = ""
                // 연결 문제와 그 밖의 실패를 구분해서 알린다.
                scope.launch { try { block() } catch (e: Exception) { error = if (e is java.io.IOException) "연결이 느리거나 끊겼어요. 잠시 후 다시 눌러 주세요." else failure } finally { busy = false } }
            }
            suspend fun ensureRoom(): LiveRoom = room ?: client.create().also { autoRoom = true; room = it }
            /** 지금 경로가 있으면 지워진다는 것을 먼저 알리고, 없으면 바로 한다. */
            fun replacing(what: String, action: () -> Unit) { if (route != null) pendingReplace = what to action else action() }
            fun loadCourses(q: String) {
                coursesStatus = "추천 여행을 불러오는 중이에요."
                scope.launch {
                    try { courses = client.publicCourses(q, COURSE_VIEWS[courseView].second); coursesStatus = if (courses.isEmpty()) "아직 이 지역에 공유된 여행이 없어요." else "" }
                    catch (_: Exception) { courses = emptyList(); coursesStatus = "추천 여행을 불러오지 못했어요. 다시 눌러 주세요." }
                }
            }
            fun openFind(showCourses: Boolean) {
                open(Screen.FIND); findCourses = showCourses; selected = null; results = emptyList()
                if (showCourses) { loadCourses(""); scope.launch { regions = try { client.trendingRegions() } catch (_: Exception) { TrendingRegions(false, emptyList()) } } }
            }
            /** 후기를 펼치거나 접는다. 차에서는 읽기만 하고, 쓰기는 휴대폰에서 한다. */
            fun toggleReviews(course: SharedCourse) {
                if (course.id in shownReviews) { shownReviews = shownReviews - course.id; return }
                scope.launch { try { shownReviews = shownReviews + (course.id to client.reviews(course)) } catch (_: Exception) { error = "후기를 불러오지 못했어요." } }
            }
            fun search() {
                if (query.isBlank()) return
                if (findCourses) { loadCourses(query.trim()); return }
                run("장소를 검색하지 못했어요. 연결을 확인해 주세요.") {
                    results = client.search(ensureRoom(), query.trim()); selected = null
                    if (results.isEmpty()) error = "검색 결과가 없어요. 다른 이름으로 찾아보세요."
                }
            }
            fun goTo(place: SearchPlace) = run("경로를 찾지 못했어요. 다시 시도해 주세요.") {
                val active = ensureRoom()
                client.setDestination(active, place)
                val s = client.state(active); applyState(s)
                companions = s.preferences.companions; travelStyles = s.preferences.styles; travelHours = s.preferences.hours
                selected = place; results = emptyList()
                nearby = try { client.recommendations(active, place) } catch (_: Exception) { emptyList() }
            }
            fun startCourse(course: SharedCourse) = run("코스 경로를 찾지 못했어요. 다시 시도해 주세요.") {
                val active = ensureRoom()
                client.startCourse(active, course); applyState(client.state(active))
                open(Screen.MAP); notice = "‘${course.title}’ 코스로 길을 찾았어요."
            }
            fun addCourse(course: SharedCourse) = run("코스를 추가하지 못했어요. 들르는 곳은 5곳까지예요.") {
                val active = room ?: return@run
                client.useCourse(active, course); applyState(client.state(active))
                open(Screen.MAP); notice = "‘${course.title}’의 장소를 들르는 곳에 더했어요."
            }
            fun accept(p: LiveProposal) {
                if (!parking.allowed.value) return
                if (route == null && p.type != "DESTINATION") { val text = "먼저 목적지를 정해 주세요. 제안은 그대로 남아 있어요."; if (screen == Screen.MAP) notice = text else error = text; return }
                run("추가하지 못했어요. 연결을 확인하고 다시 눌러 주세요.") {
                    val active = room ?: return@run
                    client.addToRoute(active, p.id); applyState(client.state(active))
                    incoming = null; open(Screen.MAP); notice = "${p.place} · 여행 경로에 담았어요."
                }
            }
            fun decline(p: LiveProposal) = run("결과를 보내지 못했어요. 다시 눌러 주세요.") {
                val active = room ?: return@run
                client.decide(active, p.id, "DECLINED"); applyState(client.state(active)); incoming = null
            }
            fun connectByCode() {
                if (pairingCode.isBlank()) return
                run("연결 코드를 확인하거나 휴대폰에서 새로 받아 주세요.") {
                    val next = client.claim(pairingCode)
                    proposals = emptyList(); route = null; waypoints = emptyList(); familyPhotos = emptyList(); roomName = ""
                    autoRoom = false; room = next; pairingCode = ""; switchingRoom = false; notice = "휴대폰에서 만든 가족방에 연결했어요."
                }
            }
            fun finish(choice: String) = run("안내를 종료하지 못했어요. 연결을 확인해 주세요.") {
                lastTripFromCourse = fromCourseTitle.isNotBlank()
                val active = room ?: return@run
                client.finishTrip(active, choice)
                route = null; waypoints = emptyList(); finishDialog = false; memoryDialog = choice != "SKIP"
                if (choice == "SKIP") notice = "안내를 종료했어요."
            }

            LaunchedEffect(parked) { if (!parked) { if (screen != Screen.MAP || finishDialog || memoryDialog || pendingReplace != null) notice = "출발해서 화면을 닫았어요."; screen = Screen.MAP; finishDialog = false; memoryDialog = false; pendingReplace = null; confirmForget = null; photoRoom = null; photoImages = emptyList(); deletePhotoTarget = null } }
            LaunchedEffect(room) {
                val active = room ?: return@LaunchedEffect
                val seen = mutableSetOf<String>()
                var first = true
                incoming = null
                while (true) {
                    try {
                        val s = client.state(active)
                        if (first && !autoRoom) {
                            val recent = (listOf(active.copy(name = s.roomName)) + roomHistory.filterNot { it.driverToken == active.driverToken }).take(5)
                            if (historyStore.save(recent)) roomHistory = recent
                        }
                        val fresh = if (first) null else s.proposals.lastOrNull { it.id !in seen }
                        seen.addAll(s.proposals.map { it.id }); first = false
                        // 누르려는 순간 카드가 다른 제안으로 바뀌지 않게, 떠 있는 카드가 없을 때만 새 제안을 띄운다.
                        if (fresh != null && incoming == null) incoming = fresh
                        if (incoming != null && s.proposals.none { it.id == incoming?.id }) incoming = null
                        // 수락 같은 작업이 진행 중일 때 그 전에 떠난 응답으로 화면을 되돌리지 않는다.
                        if (!busy) applyState(s)
                        offline = false
                    } catch (e: Exception) {
                        // 401: 서버에서 가족방이 닫혔다. 끊김으로 오래 두지 않고 처음 화면으로 돌아간다.
                        if (client.isRoomGone(e)) {
                            val next = roomHistory.filterNot { it.driverToken == active.driverToken }
                            if (historyStore.save(next)) roomHistory = next
                            room = null; route = null; proposals = emptyList(); waypoints = emptyList(); familyPhotos = emptyList(); roomName = ""; fromCourseTitle = ""
                            notice = "가족방이 닫혔어요. 가족 메뉴에서 새로 연결해 주세요."
                            break
                        }
                        offline = true
                    }
                    delay(2000)
                }
            }
            LaunchedEffect(screen, photoRoom) {
                val active = photoRoom ?: return@LaunchedEffect
                if (screen != Screen.PHOTOS) return@LaunchedEffect
                var loaded: List<FamilyPhoto>? = null
                photoStatus = "사진을 불러오는 중이에요."
                while (true) {
                    try {
                        val current = client.state(active).photos
                        if (current != loaded) {
                            albumPhotos = current
                            photoImages = photoImages.filter { pair -> current.any { it.id == pair.first.id } }
                            val images = current.map { p ->
                                val cached = photoImages.firstOrNull { it.first.id == p.id }
                                cached ?: kotlin.run {
                                    val bytes = client.photo(active, p.id)
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException()
                                    p to bitmap.asImageBitmap()
                                }
                            }
                            photoImages = images; loaded = current
                        }
                        photoStatus = ""
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { loaded = null; photoImages = emptyList(); photoStatus = "사진을 불러오지 못했어요. 가족방 연결을 확인해 주세요." }
                    delay(2000)
                }
            }
            // 주행 중에는 새 제안을 잠깐만 보여 주고, 정차 중에는 운전자가 고를 때까지 둔다.
            LaunchedEffect(incoming?.id, parked) { if (incoming != null && !parked) { delay(4000); incoming = null } }
            var inviteOrigin by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(screen, memoryDialog) {
                if (screen == Screen.FAMILY || screen == Screen.PHOTOS || memoryDialog) {
                    while (true) {
                        inviteOrigin = try { client.inviteOrigin() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        delay(30000)
                    }
                }
            }
            fun inviteUrl(selected: LiveRoom) = inviteOrigin?.let { "$it/#invite=${selected.invite}" }
            LaunchedEffect(notice) { if (notice.isNotBlank()) { delay(5000); notice = "" } }

            val stopNames = remember(route) { route?.let { r -> stopNames(r) } ?: emptyList() }

            // 차량 화면은 넓어서(dp 기준) 글자와 버튼이 작아 보인다. 가로 1280dp에 맞춰 전체를 키운다.
            val base = LocalDensity.current
            val zoom = (LocalConfiguration.current.screenWidthDp / 1280f).coerceIn(1f, 1.6f)
            CompositionLocalProvider(LocalDensity provides Density(base.density * zoom, base.fontScale)) {
            Box(Modifier.fillMaxSize().background(Color(0xFFE4DCD3))) {
                FamilyMap(route, parked, Modifier.fillMaxSize())
                Row(Modifier.fillMaxSize()) {
                    // 왼쪽 세로 메뉴: 어느 화면에서나 같은 자리에 있다.
                    Column(Modifier.fillMaxHeight().width(124.dp).background(CarColors.Navy).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Image(painterResource(R.drawable.ic_app_brand), contentDescription = "아빠어디가", modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.height(6.dp))
                        NavItem(R.drawable.ic_nav_map, "지도", screen == Screen.MAP || screen == Screen.ORDER) { open(Screen.MAP) }
                        NavItem(R.drawable.ic_nav_search, "찾기", screen == Screen.FIND, enabled = parked) { openFind(false) }
                        NavItem(R.drawable.ic_nav_family, "가족", screen == Screen.FAMILY, enabled = parked, badge = proposals.size) { open(Screen.FAMILY) }
                        NavItem(R.drawable.ic_nav_photo, "사진", screen == Screen.PHOTOS, enabled = parked, badge = 0) { open(Screen.PHOTOS) }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (screen) {
                            Screen.MAP -> {
                                // 여행 카드
                                Column(Modifier.align(Alignment.TopStart).padding(18.dp).width(430.dp).heightIn(max = 640.dp).background(CarColors.Navy, RoundedCornerShape(24.dp)).padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val r = route
                                    if (r == null) {
                                        T("어디로 떠날까요?", 30, FontWeight.Black, Color.White)
                                        T(if (room == null) "목적지를 정하거나 가족을 먼저 초대해 보세요." else "목적지를 정하면 가족의 제안을 길에 담을 수 있어요.", 19, color = CarColors.OnNavySub)
                                        // 주행 중에는 누를 수 없는 버튼을 아예 보여 주지 않는다.
                                        if (parked) {
                                            CarButton("목적지 찾기", Modifier.fillMaxWidth(), Btn.OnNavyStrong, icon = R.drawable.ic_nav_search) { openFind(false) }
                                            CarButton("추천 여행 보기", Modifier.fillMaxWidth(), Btn.OnNavy, icon = R.drawable.ic_nav_map) { openFind(true) }
                                            if (room == null || autoRoom) CarButton("가족과 함께하기", Modifier.fillMaxWidth(), Btn.OnNavy, icon = R.drawable.ic_nav_family) { open(Screen.FAMILY) }
                                        }
                                    } else {
                                        T(r.destinationName, 30, FontWeight.Black, Color.White, maxLines = 2)
                                        T("${r.distance / 1000}km · 약 ${formatMinutes(r.duration / 60)}", 22, FontWeight.Bold, CarColors.OnNavySub)
                                        Spacer(Modifier.height(2.dp))
                                        StopList(stopNames, onNavy = true)
                                        Spacer(Modifier.height(2.dp))
                                        if (parked) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            CarButton("순서 바꾸기", Modifier.weight(1f), Btn.OnNavy, enabled = itineraryVersion >= 0 && waypoints.isNotEmpty()) { editWaypoints = waypoints; editVersion = itineraryVersion; open(Screen.ORDER) }
                                            CarButton("안내 종료", Modifier.weight(1f), Btn.OnNavy) { error = ""; finishDialog = true }
                                        }
                                    }
                                    if (parked) T("출발 위치는 서울 (시연)", 16, color = CarColors.OnNavySub)
                                    else T("주행 중에는 지도만 보여요.\n정차하면 다시 쓸 수 있어요.", 22, FontWeight.Bold, Color.White)
                                    if (offline) T("연결이 끊겼어요 · 자동으로 다시 연결해요", 18, FontWeight.Bold, Color(0xFFFFD08A))
                                }
                                // 가족의 새 제안: 정차 중이면 그 자리에서 바로 고른다.
                                incoming?.let { p ->
                                    Column(Modifier.align(Alignment.BottomEnd).padding(18.dp).width(460.dp).background(Color.White, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (parked) {
                                            Tag("${p.member.take(12)}님의 새 제안")
                                            T(p.place, 26, FontWeight.Black, maxLines = 1)
                                            T(proposalDetail(p), 18, color = CarColors.Sub, maxLines = 2)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                CarButton(acceptLabel(p), Modifier.weight(1.4f), enabled = !busy) { accept(p) }
                                                // 여기서는 닫기만 한다. 제안은 가족 메뉴에 그대로 남는다.
                                                CarButton("나중에 보기", Modifier.weight(1f), Btn.Soft) { incoming = null }
                                            }
                                        }
                                        // 주행 중에는 가족이 쓴 글을 보여 주지 않고 한 줄만 잠깐 띄운다.
                                        else T("가족 제안이 도착했어요 · 정차 후 확인", 24, FontWeight.Bold, maxLines = 1)
                                    }
                                }
                            }
                            Screen.FIND -> SidePanel(if (findCourses) "추천 여행" else "목적지 찾기", error, { open(Screen.MAP) }, Modifier.fillMaxWidth(.6f)) {
                                Segmented(listOf("장소 검색", "추천 여행"), if (findCourses) 1 else 0) { i -> openFind(i == 1) }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextInput(query, { query = it }, if (findCourses) "예: 부산" else "예: 부산역", "검색어 입력", Modifier.weight(1f)) { search() }
                                    CarButton(if (busy) "찾는 중" else "검색", enabled = !busy, icon = R.drawable.ic_nav_search) { search() }
                                }
                                if (findCourses) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        COURSE_VIEWS.forEachIndexed { i, (label, _) -> Choice(label, i == courseView) { courseView = i; loadCourses(query.trim()) } }
                                    }
                                    if (regions.names.isNotEmpty()) {
                                        T(if (regions.active) "요즘 많이 찾는 여행지" else "지역별로 둘러보기", 19, FontWeight.Bold, CarColors.Sub)
                                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            regions.names.forEach { name -> Choice(name, query.trim() == name) { query = name; loadCourses(name) } }
                                            Choice("전체", query.isBlank()) { query = ""; loadCourses("") }
                                        }
                                    }
                                    if (coursesStatus.isNotBlank()) T(coursesStatus, 20, color = CarColors.Sub)
                                    courses.forEach { c ->
                                        CourseCard(c, shownReviews[c.id], { toggleReviews(c) }) {
                                            if (route != null) T("지금 경로가 이 코스로 바뀌어요.", 17, color = CarColors.Sub)
                                            CarButton(if (busy) "길 찾는 중" else "이 코스로 길 찾기", Modifier.fillMaxWidth(), enabled = !busy && c.destination != null) { replacing("‘${c.title}’ 코스로 바꾸면") { startCourse(c) } }
                                        }
                                    }
                                } else if (selected == null) {
                                    if (results.isEmpty() && error.isBlank()) T("가고 싶은 곳의 이름이나 지역을 검색해 보세요.", 20, color = CarColors.Sub)
                                    if (route != null) T("새 목적지를 정하면 지금의 들르는 곳은 지워져요.", 19, FontWeight.Bold, CarColors.WarmInk, Modifier.fillMaxWidth().background(CarColors.Warm, RoundedCornerShape(14.dp)).padding(14.dp))
                                    results.forEach { p ->
                                        Card {
                                            T(p.name, 24, FontWeight.Bold, maxLines = 1)
                                            if (p.address.isNotBlank()) T(p.address, 18, color = CarColors.Sub, maxLines = 1)
                                            CarButton(if (busy) "길 찾는 중" else "여기로 가기", Modifier.fillMaxWidth(), enabled = !busy, icon = R.drawable.ic_flag) { replacing("목적지를 ${p.name}(으)로 바꾸면") { goTo(p) } }
                                        }
                                    }
                                } else {
                                    Card {
                                        Tag("목적지를 정했어요", CarColors.OkBg, CarColors.Ok)
                                        T(selected!!.name, 26, FontWeight.Black, maxLines = 1)
                                        CarButton("지도에서 보기", Modifier.fillMaxWidth(), icon = R.drawable.ic_nav_map) { open(Screen.MAP) }
                                    }
                                    T("가는 길에 들르기 좋은 여행", 23, FontWeight.Bold)
                                    CarButton(if (showPreferences) "취향 접기" else "우리 가족 취향으로 고르기", Modifier.fillMaxWidth(), Btn.Line) { showPreferences = !showPreferences }
                                    if (showPreferences) Card {
                                        T("누구와 함께 가나요?", 19, FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("CHILD" to "아이와", "PARENTS" to "부모님과", "PET" to "반려동물과").forEach { (v, l) -> Choice(l, v in companions) { companions = if (v in companions) companions - v else companions + v } } }
                                        T("어떤 하루가 좋나요?", 19, FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("FOOD" to "맛있게", "RELAX" to "느긋하게", "ACTIVITY" to "신나게").forEach { (v, l) -> Choice(l, v in travelStyles) { travelStyles = if (v in travelStyles) travelStyles - v else travelStyles + v } } }
                                        T("여행 시간", 19, FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf<Pair<Int?, String>>(null to "상관없어요", 2 to "2시간", 4 to "4시간", 6 to "6시간", 8 to "8시간").forEach { (v, l) -> Choice(l, v == travelHours) { travelHours = v } } }
                                        CarButton(if (busy) "찾는 중" else "이 취향으로 다시 찾기", Modifier.fillMaxWidth(), enabled = !busy) {
                                            run("추천 여행을 가져오지 못했어요. 다시 시도해 주세요.") { val active = room ?: return@run; client.savePreferences(active, companions, travelStyles, travelHours); nearby = client.recommendations(active, selected!!); showPreferences = false }
                                        }
                                    }
                                    if (nearby.isEmpty()) T("아직 주변에 공유된 여행이 없어요.", 20, color = CarColors.Sub)
                                    nearby.forEach { c ->
                                        CourseCard(c, shownReviews[c.id], { toggleReviews(c) }) {
                                            if (c.matchReason.isNotBlank()) T("우리 가족 취향: ${c.matchReason}", 18, FontWeight.Bold, CarColors.Teal)
                                            CarButton(if (busy) "길 찾는 중" else "들르는 곳으로 더하기", Modifier.fillMaxWidth(), enabled = !busy, icon = R.drawable.ic_plus) { addCourse(c) }
                                        }
                                    }
                                }
                            }
                            Screen.FAMILY -> SidePanel(if (room == null || autoRoom) "가족과 함께하기" else roomName.ifBlank { "우리 가족방" }, error, { open(Screen.MAP) }, Modifier.fillMaxWidth(.66f)) {
                                val active = room
                                // 목적지를 먼저 찾느라 자동으로 생긴 방이면, 가족방을 고르는 첫 화면을 그대로 보여 준다.
                                if (active == null || autoRoom) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Card(Modifier.weight(1f)) {
                                            T("차에서 새로 시작", 23, FontWeight.Bold)
                                            T("가족방을 만들고 QR 또는 초대 링크로 함께해요.", 18, color = CarColors.Sub)
                                            CarButton(if (busy) "만드는 중" else "가족방 만들기", Modifier.fillMaxWidth(), enabled = !busy, icon = R.drawable.ic_plus) {
                                                run("가족방을 만들지 못했어요. 연결을 확인해 주세요.") {
                                                    // 이미 자동으로 생긴 방이 있으면 지금 경로를 그대로 둔 채 그 방을 가족방으로 쓴다.
                                                    val existing = room
                                                    if (existing != null && autoRoom) {
                                                        autoRoom = false
                                                        val recent = (listOf(existing.copy(name = roomName)) + roomHistory.filterNot { it.driverToken == existing.driverToken }).take(5)
                                                        if (historyStore.save(recent)) roomHistory = recent
                                                    } else room = client.create()
                                                }
                                            }
                                        }
                                        Card(Modifier.weight(1f)) {
                                            T("휴대폰에서 만든 방", 23, FontWeight.Bold)
                                            T("휴대폰의 ‘차량 연결’에 나온 숫자 6자리를 적어 주세요.", 18, color = CarColors.Sub)
                                            TextInput(pairingCode, { pairingCode = it.filter(Char::isDigit).take(6) }, "숫자 6자리", "차량 연결 코드 숫자 6자리 입력", Modifier.fillMaxWidth(), 30, numeric = true) { connectByCode() }
                                            CarButton(if (busy) "연결 중" else "이 코드로 연결", Modifier.fillMaxWidth(), Btn.Soft, enabled = !busy && pairingCode.length == 6) { replacing("휴대폰 가족방에 연결하면") { connectByCode() } }
                                        }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                                        Card(Modifier.width(250.dp)) {
                                            val invite = inviteUrl(active)
                                            var useCode by remember(active.driverToken, invite == null) { mutableStateOf(invite == null) }
                                            var attempt by remember(active.driverToken) { mutableIntStateOf(0) }
                                            var joinCode by remember(active.driverToken) { mutableStateOf("") }
                                            var message by remember(active.driverToken) { mutableStateOf("") }
                                            LaunchedEffect(active.driverToken, useCode, attempt) {
                                                if (useCode) {
                                                    joinCode = ""
                                                    message = "초대 코드를 준비하고 있어요."
                                                    try {
                                                        val (code, expiresAt) = client.invitationCode(active)
                                                        joinCode = code
                                                        message = "휴대폰 가족방 홈의 ‘초대 코드로 참여하기’에 입력하세요."
                                                        kotlinx.coroutines.delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
                                                        joinCode = ""
                                                        message = "초대 코드가 만료됐어요."
                                                    } catch (e: CancellationException) { throw e }
                                                    catch (_: Exception) { message = "초대 코드를 받지 못했어요. 다시 시도해 주세요." }
                                                }
                                            }
                                            T("가족 초대", 22, FontWeight.Bold)
                                            if (useCode) {
                                                if (joinCode.isNotEmpty()) { InviteQr(joinCode, 200); T(joinCode.chunked(5).joinToString(" "), 22, FontWeight.Bold) }
                                                T(message, 17, color = CarColors.Sub)
                                                if (joinCode.isEmpty()) CarButton("코드 다시 받기", Modifier.fillMaxWidth()) { attempt++ }
                                                if (invite != null) CarButton("초대 링크 QR 보기", Modifier.fillMaxWidth()) { useCode = false }
                                            } else if (invite != null) {
                                                InviteQr(invite, 200)
                                                T("QR을 찍으면 가족방이 열려요.", 17, color = CarColors.Sub)
                                                T(invite, 17, color = CarColors.Ink)
                                                CarButton("초대 코드로 참여", Modifier.fillMaxWidth()) { useCode = true }
                                            }
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            T(if (proposals.isEmpty()) "가족의 제안을 기다리고 있어요" else "가족의 제안 ${proposals.size}곳", 23, FontWeight.Bold)
                                            if (proposals.isEmpty()) T("가족이 휴대폰에서 장소를 제안하면 여기에 나타나요.", 19, color = CarColors.Sub)
                                            proposals.forEach { p ->
                                                Card {
                                                    Tag("${p.member.take(12)}님 · ${kindLabel(p)}${if (p.votes > 0) " · 가족 ${p.votes}명 응답" else ""}")
                                                    T(p.place, 24, FontWeight.Bold, maxLines = 1)
                                                    T(proposalDetail(p), 18, color = CarColors.Sub, maxLines = 2)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        CarButton(acceptLabel(p), Modifier.weight(1.4f), enabled = !busy) { accept(p) }
                                                        // 누르면 가족에게 '이번에는 가지 않아요'로 전해지고 목록에서 사라진다.
                                                        CarButton("이번엔 안 가요", Modifier.weight(1f), Btn.Soft, enabled = !busy) { decline(p) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (!switchingRoom) CarButton("다른 가족방으로 바꾸기", Modifier.fillMaxWidth(), Btn.Line) { switchingRoom = true; pairingCode = "" }
                                    else Card {
                                        T("다른 가족방으로 바꾸기", 22, FontWeight.Bold)
                                        T("바꾸면 지도와 제안이 새 방의 내용으로 바뀌어요. 휴대폰의 ‘차량 연결’에 나온 숫자 6자리를 적어 주세요.", 18, color = CarColors.Sub)
                                        TextInput(pairingCode, { pairingCode = it.filter(Char::isDigit).take(6) }, "숫자 6자리", "다른 가족방 연결 코드 숫자 6자리 입력", Modifier.fillMaxWidth(), 30, numeric = true) { connectByCode() }
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            CarButton("취소", Modifier.weight(1f), Btn.Soft) { switchingRoom = false; pairingCode = "" }
                                            CarButton(if (busy) "연결 중" else "바꾸고 연결", Modifier.weight(1.4f), enabled = !busy && pairingCode.length == 6) { replacing("다른 가족방으로 바꾸면") { connectByCode() } }
                                        }
                                    }
                                }
                                val others = roomHistory.filterNot { it.driverToken == room?.driverToken }
                                if (others.isNotEmpty()) {
                                    T("이 차에서 쓰던 가족방", 22, FontWeight.Bold)
                                    others.forEachIndexed { i, saved ->
                                        Card {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                // 같은 이름의 방이 여러 개여도 구분되도록 순서 번호를 함께 보여 준다.
                                                T("${i + 1}. " + saved.name.ifBlank { "가족방" }, 22, FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                                                if (confirmForget == saved.driverToken) {
                                                    CarButton("정말 지우기", kind = Btn.Danger, enabled = !busy) { val next = roomHistory.filterNot { it.driverToken == saved.driverToken }; if (historyStore.save(next)) roomHistory = next else error = "기록을 지우지 못했어요."; confirmForget = null }
                                                    CarButton("취소", kind = Btn.Soft) { confirmForget = null }
                                                } else {
                                                    CarButton("열기", kind = Btn.Soft, enabled = !busy) {
                                                        replacing("‘${saved.name.ifBlank { "가족방" }}’ 방을 열면") {
                                                            run("이 방은 닫혔거나 지금 연결할 수 없어요.") { client.state(saved); proposals = emptyList(); route = null; waypoints = emptyList(); familyPhotos = emptyList(); roomName = saved.name; autoRoom = false; room = saved; notice = "가족방에 다시 연결했어요." }
                                                        }
                                                    }
                                                    Spacer(Modifier.width(14.dp))
                                                    CarButton("지우기", kind = Btn.Line, enabled = !busy) { confirmForget = saved.driverToken }
                                                }
                                            }
                                            if (confirmForget == saved.driverToken) T("지우면 이 차에서 이 방을 다시 열 수 없어요. 휴대폰에서 연결 코드를 새로 받아야 해요.", 17, color = CarColors.Sub)
                                        }
                                    }
                                }
                            }
                            Screen.PHOTOS -> SidePanel(if (photoRoom == null) "사진을 볼 가족방 선택" else "${photoRoom?.name?.ifBlank { "가족 여행" }} · 사진", error, { open(Screen.MAP) }, Modifier.fillMaxWidth(.76f)) {
                                val album = photoRoom
                                if (album == null) {
                                    T("어느 가족의 추억을 볼까요? 사진을 열어도 현재 여행 경로는 바뀌지 않아요.", 20, color = CarColors.Sub)
                                    val albums = (listOfNotNull(room?.copy(name = roomName.ifBlank { room?.name ?: "" })) + roomHistory).distinctBy { it.driverToken }
                                    if (albums.isEmpty()) {
                                        T("아직 연결한 가족방이 없어요.", 23, FontWeight.Bold)
                                        CarButton("가족방 연결하기", Modifier.fillMaxWidth()) { open(Screen.FAMILY) }
                                    }
                                    albums.forEachIndexed { index, saved ->
                                        Card {
                                            T(saved.name.ifBlank { "가족 여행 ${index + 1}" }, 24, FontWeight.Bold)
                                            if (saved.driverToken == room?.driverToken) Tag("현재 여행 중인 가족방")
                                            CarButton("이 가족방 사진 보기", Modifier.fillMaxWidth(), enabled = !photoDeleting) {
                                                photoImages = emptyList(); albumPhotos = emptyList(); photoStatus = "사진을 불러오는 중이에요."; error = ""; photoRoom = saved
                                            }
                                        }
                                    }
                                } else {
                                    CarButton("다른 가족방 사진 보기", Modifier.fillMaxWidth(), Btn.Line, enabled = !photoDeleting) { open(Screen.PHOTOS) }
                                    T("이 가족방의 사진만 보여요. 삭제하면 가족 모두의 앨범에서 사라져요.", 19, color = CarColors.Sub)
                                    if (photoStatus.isNotBlank()) T(photoStatus, 20, color = CarColors.Sub)
                                    if (albumPhotos.isEmpty() && photoStatus.isBlank()) Card {
                                        T("아직 올라온 사진이 없어요", 23, FontWeight.Bold)
                                        val invite = inviteUrl(album)
                                        if (invite != null) { InviteQr(invite, 170); T("휴대폰 가족방에서 사진을 올려 주세요.", 19, color = CarColors.Sub) }
                                    }
                                    photoImages.chunked(3).forEach { rowItems ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            rowItems.forEach { (photo, bitmap) ->
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Image(bitmap, contentDescription = "${photo.memberName}님이 올린 가족 사진", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(16.dp)))
                                                    T("${photo.memberName}님", 18, FontWeight.Bold, CarColors.Sub, maxLines = 1)
                                                    CarButton("사진 삭제", Modifier.fillMaxWidth(), Btn.Danger, enabled = !photoDeleting && parked) { deletePhotoTarget = photo }
                                                }
                                            }
                                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                            Screen.ORDER -> SidePanel("가는 순서 바꾸기", error, { open(Screen.MAP) }, Modifier.fillMaxWidth(.6f)) {
                                if (editWaypoints.isEmpty()) T("들르는 곳이 없어요. 목적지로 바로 가요.", 20, color = CarColors.Sub)
                                editWaypoints.forEachIndexed { i, place ->
                                    Card {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            T("${i + 1}", 22, FontWeight.Black, CarColors.Teal)
                                            T(place.name, 23, FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                                            CarButton("위로", kind = Btn.Soft, enabled = !busy && i > 0) { val list = editWaypoints.toMutableList(); list.add(i - 1, list.removeAt(i)); editWaypoints = list }
                                            CarButton("아래로", kind = Btn.Soft, enabled = !busy && i < editWaypoints.lastIndex) { val list = editWaypoints.toMutableList(); list.add(i + 1, list.removeAt(i)); editWaypoints = list }
                                            // 순서를 바꾸다 잘못 누르지 않게 '빼기'는 떨어뜨려 둔다. 실제로 빠지는 것은 아래 '이 순서로 길 찾기'를 누른 뒤다.
                                            Spacer(Modifier.width(18.dp))
                                            CarButton("빼기", kind = Btn.Danger, enabled = !busy) { editWaypoints = editWaypoints.filterNot { it.id == place.id } }
                                        }
                                    }
                                }
                                Card { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Tag("도착지", CarColors.Navy, Color.White); T(route?.destinationName ?: "", 23, FontWeight.Bold, maxLines = 1) } }
                                CarButton(if (busy) "새 길을 찾고 있어요" else "이 순서로 길 찾기", Modifier.fillMaxWidth(), enabled = !busy) {
                                    run("경로를 바꾸지 못했어요. 닫고 지도에서 최신 경로를 확인해 주세요.") {
                                        val active = room ?: return@run
                                        client.updateWaypoints(active, editWaypoints.map { it.id }, editVersion); applyState(client.state(active))
                                        open(Screen.MAP); notice = "바꾼 순서로 길을 찾았어요."
                                    }
                                }
                            }
                        }
                        val message = notice.ifBlank { if (screen == Screen.MAP) error else "" }
                        // 새 제안 카드가 떠 있으면 그 버튼을 가리지 않게 왼쪽 아래로 옮긴다.
                        val corner = if (incoming != null && screen == Screen.MAP) Alignment.BottomStart else Alignment.BottomCenter
                        if (message.isNotBlank()) T(message, 21, FontWeight.Bold, Color.White, Modifier.align(corner).padding(start = 22.dp, bottom = 22.dp).widthIn(max = 720.dp).clip(RoundedCornerShape(16.dp)).background(if (notice.isBlank()) CarColors.Bad else Color(0xFF10273F)).clickable { notice = ""; error = "" }.padding(horizontal = 22.dp, vertical = 16.dp))
                    }
                }
                if (deletePhotoTarget != null && photoRoom != null && screen == Screen.PHOTOS && parked) CenterDialog {
                    val target = deletePhotoTarget!!
                    val album = photoRoom!!
                    T("이 사진을 삭제할까요?", 28, FontWeight.Bold)
                    T("${target.memberName}님이 올린 사진이 이 가족방의 모든 기기에서 삭제돼요. 되돌릴 수 없어요.", 20, color = CarColors.Sub)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarButton("취소", Modifier.weight(1f), Btn.Soft, enabled = !photoDeleting) { deletePhotoTarget = null }
                        CarButton(if (photoDeleting) "삭제 중" else "사진 삭제", Modifier.weight(1f), Btn.Danger, enabled = !photoDeleting) {
                            if (parking.allowed.value) {
                                photoDeleting = true
                                scope.launch {
                                    try {
                                        client.deletePhoto(album, target.id)
                                        if (photoRoom == album) {
                                            photoImages = photoImages.filterNot { it.first.id == target.id }
                                            albumPhotos = albumPhotos.filterNot { it.id == target.id }
                                            deletePhotoTarget = null; notice = "사진을 삭제했어요."
                                        }
                                    } catch (_: Exception) { deletePhotoTarget = null; error = "사진을 삭제하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요." }
                                    finally { photoDeleting = false }
                                }
                            }
                        }
                    }
                }
                pendingReplace?.let { (what, action) ->
                    if (parked) CenterDialog {
                        T("지금 경로가 바뀌어요", 30, FontWeight.Black)
                        T("$what 지금 경로와 들르는 곳 ${(stopNames.size - 1).coerceAtLeast(0)}곳이 지워져요.", 21, color = CarColors.Ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CarButton("그대로 두기", Modifier.weight(1.3f)) { pendingReplace = null }
                            CarButton("바꾸기", Modifier.weight(1f), Btn.Danger) { pendingReplace = null; action() }
                        }
                    }
                }
                if (finishDialog && parked) CenterDialog {
                    T("여행을 마칠까요?", 30, FontWeight.Black)
                    T("아래 버튼을 누르면 길 안내가 바로 끝나요. 남기는 일은 휴대폰 가족방에서 이어서 해요.", 20, color = CarColors.Sub)
                    if (error.isNotBlank()) T(error, 19, FontWeight.Bold, CarColors.Bad)
                    CarButton("계속 안내받기", Modifier.fillMaxWidth(), enabled = !busy) { finishDialog = false; error = "" }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CarButton("끝내고 추억 남기기", Modifier.weight(1f), Btn.Soft, enabled = !busy) { finish("MEMORY") }
                        // 추천받은 코스로 다녀온 여행은 다시 공유하지 않고, 그 코스에 좋아요·후기를 남긴다.
                        if (fromCourseTitle.isNotBlank()) CarButton("끝내고 후기 남기기", Modifier.weight(1f), Btn.Soft, enabled = !busy) { finish("REVIEW") }
                        else CarButton("끝내고 코스 공개하기", Modifier.weight(1f), Btn.Soft, enabled = !busy) { finish("SHARE") }
                        CarButton("그냥 끝내기", Modifier.weight(.8f), Btn.Line, enabled = !busy) { finish("SKIP") }
                    }
                    T(if (fromCourseTitle.isNotBlank()) "추천 코스 「$fromCourseTitle」로 다녀온 여행이라 공유 대신 휴대폰에서 좋아요와 후기를 남길 수 있어요. 여행 기록과 사진은 가족방에 묶여 남아요."
                      else "‘코스 공개하기’는 다른 가족도 볼 수 있게 올리는 거예요. 휴대폰에서 공개할 장소를 고른 뒤에 올라가요. 가족 사진은 공개되지 않아요.", 17, color = CarColors.Sub)
                }
                if (memoryDialog && parked) CenterDialog {
                    T("오늘 여행을 함께 남겨요", 30, FontWeight.Black)
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        val memoryInvite = room?.let { inviteUrl(it) }
                        if (memoryInvite != null) InviteQr(memoryInvite, 220)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            T((if (memoryInvite != null) "① 휴대폰으로 QR을 찍어 가족방을 열어요." else "① 휴대폰에서 가족방을 열어요.") + "\n② ‘우리 가족 여행 기록’에서 이번 여행을 열어요.\n③ 추억 저장·" + (if (lastTripFromCourse) "좋아요·후기" else "코스 공유") + "를 하고 사진도 올려 보세요.", 21, color = CarColors.Ink)
                            if (memoryInvite != null) T("QR을 받은 사람은 가족방에 들어올 수 있어요. 가족에게만 보여 주세요.", 17, color = CarColors.Sub)
                        }
                    }
                    CarButton("확인", Modifier.fillMaxWidth()) { memoryDialog = false }
                }
            }
            }
        }
    }
}

private fun kindLabel(p: LiveProposal) = when (p.type) { "COURSE" -> "여행 코스"; "DESTINATION" -> "목적지"; else -> "들르는 곳" }
private fun acceptLabel(p: LiveProposal) = when (p.type) { "COURSE" -> "코스 더하기"; "DESTINATION" -> "여기로 가기"; else -> "가는 길에 들르기" }
private fun proposalDetail(p: LiveProposal): String {
    if (p.courseStops.isNotBlank()) return p.courseStops
    // 시연 위치 기준 직선거리는 운전자가 판단하는 데 도움이 되지 않아 차량 화면에서는 뺐다.
    return (p.message.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "").ifBlank { p.address }
}
private fun formatMinutes(minutes: Int) = if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
private fun stopNames(route: LiveRoute): List<String> = try {
    val stops = JSONArray(route.stopsJson)
    (0 until stops.length()).map { stops.getJSONObject(it).optString("name") }.filter { it.isNotBlank() } + route.destinationName
} catch (_: Exception) { listOf(route.destinationName) }

/** 추천 여행을 보는 순서. 뒤의 값은 서버에 그대로 넘기는 조건이다. */
private val COURSE_VIEWS = listOf("요즘 인기" to "sort=trending", "좋아요순" to "sort=popular", "새로 올라온" to "sort=new&kind=community", "참고 코스" to "kind=official&sort=trending")

@Composable private fun CourseCard(course: SharedCourse, reviews: List<CourseReview>?, onReviews: () -> Unit, actions: @Composable ColumnScope.() -> Unit) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                course.official -> Tag("${if (course.region.isNotBlank()) course.region + " · " else ""}${if (course.sourceKind == "video") "여행 영상 참고" else "관광 안내 참고"}")
                course.isSample -> Tag("시연 예시", CarColors.Warm, CarColors.WarmInk)
                else -> Tag("가족이 공유한 코스 · 좋아요 ${course.likes}")
            }
            if (course.trending) Tag("요즘 인기", CarColors.OkBg, CarColors.Ok)
        }
        T(course.title, 24, FontWeight.Bold, maxLines = 2)
        if (course.description.isNotBlank()) T(course.description, 18, color = CarColors.Sub, maxLines = 2)
        if (course.official && course.sourceName.isNotBlank()) T("참고: ${course.sourceName}", 17, color = CarColors.Sub, maxLines = 1)
        StopList(course.stops.split(" → ").filter { it.isNotBlank() })
        if (course.reviewCount > 0) {
            CarButton("★ ${course.rating ?: 0.0} · 후기 ${course.reviewCount}개 ${if (reviews == null) "보기" else "접기"}", Modifier.fillMaxWidth(), Btn.Line, onClick = onReviews)
            reviews?.take(3)?.forEach { r ->
                Column(Modifier.fillMaxWidth().background(CarColors.Tint, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T("★ ${r.rating} · ${r.name}", 18, FontWeight.Bold)
                    if (r.text.isNotBlank()) T(r.text, 18, color = CarColors.Ink, maxLines = 3)
                }
            }
        }
        actions()
    }
}

@Composable private fun FamilyMap(route: LiveRoute?, parked: Boolean, modifier: Modifier) {
    var loaded by remember { mutableStateOf(false) }
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(message: ConsoleMessage): Boolean { Log.w("FamilyMap", "map script: ${message.message()}"); return true } }
            webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String) { loaded = true; view.postDelayed({ val cssHeight = (view.height / view.resources.displayMetrics.density).toInt().coerceAtLeast(1); view.evaluateJavascript("window.resizeMap($cssHeight)", null) }, 300) } }
            // 키보드가 올라왔다 내려가는 등 크기가 바뀌면 지도를 다시 맞춘다.
            addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom -> if (loaded && bottom - top != oldBottom - oldTop) { val h = ((bottom - top) / v.resources.displayMetrics.density).toInt(); if (h > 0) (v as WebView).evaluateJavascript("window.resizeMap($h)", null) } }
            loadUrl("file:///android_asset/map/index.html")
        }
    }, update = { web -> if (loaded) { val h = (web.height / web.resources.displayMetrics.density).toInt(); if (h > 0) web.evaluateJavascript("window.resizeMap($h)", null);
        // 주행 중에는 지도를 끌거나 확대할 수 없게 잠근다.
        web.evaluateJavascript("window.setLocked(${!parked})", null); if (route != null) web.evaluateJavascript("window.showRoute(JSON.parse(${JSONObject.quote(route.pointsJson)}),JSON.parse(${JSONObject.quote(route.stopsJson)}))", null) else web.evaluateJavascript("window.clearRoute()", null) } })
}
