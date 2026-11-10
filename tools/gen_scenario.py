"""
scenario.json 생성기 (표준 라이브러리만 사용, 시드 고정).
주요 지점(가상 좌표)을 Catmull-Rom 곡선으로 잇고, 지점 사이에 약간의 굴곡을 넣은 뒤
약 200m 간격으로 다시 나눈다. 장소마다 경로 이탈·합류 지점과 우회 폴리라인을 만든다.
실제 길찾기 결과가 아니다.

사용: python3 -I tools/gen_scenario.py app/src/main/assets/scenario.json
"""
import json, math, random, sys

SEED = 20261105
STEP_M = 200.0
R = 6371000.0

# (위도, 경도, 이 지점부터의 도로명, 제한속도 km/h)
ANCHORS = [
    (37.5133, 127.1002, "올림픽대로", 80),
    (37.5245, 127.1530, "올림픽대로", 80),
    (37.5440, 127.2050, "미사대로", 60),
    (37.5530, 127.2480, "경강로", 60),
    (37.5420, 127.2930, "경강로", 60),
    (37.5440, 127.3150, "경강로", 60),
    (37.5800, 127.3720, "경강로", 60),
    (37.6020, 127.3780, "서울양양고속도로", 100),
    (37.6390, 127.4800, "서울양양고속도로", 100),
    (37.6700, 127.5600, "서울양양고속도로", 100),
    (37.6950, 127.7200, "서울양양고속도로", 100),
    (37.6800, 127.8700, "중앙고속도로", 100),
    (37.5800, 127.9400, "중앙고속도로", 100),
    (37.4950, 127.9850, "영동고속도로", 100),
    (37.4700, 128.0600, "영동고속도로", 100),
    (37.5100, 128.2400, "영동고속도로", 100),
    (37.5550, 128.4200, "영동고속도로", 100),
    (37.6400, 128.6000, "영동고속도로", 100),
    (37.6900, 128.7300, "대관령로", 60),
    (37.6750, 128.8000, "경강로", 60),
    (37.7750, 128.8800, "경포로", 60),
    (37.7780, 128.9150, "경포로", 60),
]

# 분명하게 꺾이는 분기점(앵커 인덱스, 안내 문구). 이 앵커에서 곡선을 끊어
# 들어오는 방향과 나가는 방향(앞뒤 앵커 배치로 정해짐)이 그대로 꺾이게 한다.
CORNERS = [
    (3, 0, "팔당 방면"),
    (7, 0, "서울양양고속도로 방면 우측"),
    (11, 0, "중앙고속도로 원주 방면"),
    (13, 0, "만종JC 강릉 방면"),
    (18, 0, "대관령IC 강릉 방면"),
    (20, 0, "경포해변 방면"),
]
TURN_TEXTS = [(ANCHORS[i][0], ANCHORS[i][1], text) for i, _, text in CORNERS]

# id, 이름, 짧은 이름, 분류, 목적, 종류, 위도, 경도(None이면 경로 옆 휴게소), 우회(분), 체류(분), 경로 옆 거리 표시(km)
PLACES = [
    ("cafe_dumulmeori", "두물머리 카페", "카페", "카페 · 영업 중", "커피", "SPOT", 37.5345, 127.3159, 8, 20, 1.1),
    ("rest_hongcheon", "홍천 휴게소", "휴게소", "휴게소", "휴식", "REST_AREA", 37.6950, 127.7200, 6, 10, 0.0),
    ("hanu_hoengseong", "횡성 한우 식당", "식당", "식당 · 영업 중", "점심", "SPOT", 37.5050, 127.9600, 12, 50, 2.3),
    ("rest_hoengseong", "횡성 휴게소", "휴게소", "휴게소", "휴식", "REST_AREA", 37.4800, 128.0350, 4, 10, 0.0),
    ("rest_pyeongchang", "평창 휴게소", "휴게소", "휴게소", "휴식", "REST_AREA", 37.5550, 128.4200, 5, 10, 0.0),
    ("sheep_daegwallyeong", "대관령 양떼목장", "목장", "관광 · 체류 60분 예상", "관광", "SPOT", 37.7120, 128.7180, 35, 60, 6.5),
    ("market_gangneung", "강릉 중앙시장", "시장", "시장 · 목적지에서 3km", "시장", "SPOT", 37.7700, 128.8850, 0, 40, 1.0),
]

LAT0 = 37.6
def to_xy(lat, lon):
    return ((lon - 127.0) * math.cos(math.radians(LAT0)) * 111320.0, (lat - LAT0) * 110574.0)
def to_ll(x, y):
    return (LAT0 + y / 110574.0, 127.0 + x / (math.cos(math.radians(LAT0)) * 111320.0))

def catmull(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return tuple(0.5 * ((2 * p1[i]) + (-p0[i] + p2[i]) * t + (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t2
                        + (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t3) for i in range(2))

def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])

def resample(pts, attrs, step):
    out, oattr = [pts[0]], [attrs[0]]
    carry = 0.0
    for i in range(1, len(pts)):
        a, b = pts[i - 1], pts[i]
        d = dist(a, b)
        pos = step - carry
        while pos <= d:
            t = pos / d
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)); oattr.append(attrs[i - 1])
            pos += step
        carry = d - (pos - step)
    if dist(out[-1], pts[-1]) > 1:
        out.append(pts[-1]); oattr.append(attrs[-1])
    return out, oattr

def main(path):
    rnd = random.Random(SEED)
    A = [to_xy(a[0], a[1]) for a in ANCHORS]
    corner = {i: ang for i, ang, _ in CORNERS}
    # 꺾이는 앵커에서 끊어 여러 구간으로 나눈다. 각 구간의 점 목록과 원래 앵커 인덱스(도로 속성용)
    runs, cur = [], [(A[0], 0)]
    for i in range(1, len(A)):
        cur.append((A[i], i))
        if i in corner and i < len(A) - 1:
            runs.append(cur)
            cur = [(A[i], i)]
    runs.append(cur)

    fine, fattr = [], []
    for run in runs:
        P = [p for p, _ in run]
        ext = [P[0]] + P + [P[-1]]
        for k in range(len(P) - 1):
            p0, p1, p2, p3 = ext[k], ext[k + 1], ext[k + 2], ext[k + 3]
            ai = run[k][1]
            seg = dist(p1, p2)
            n = max(8, int(seg / 50))
            amp = (70.0 if ANCHORS[ai][3] >= 100 else 35.0) if seg > 1500 else 0.0
            ph1, ph2 = rnd.uniform(0, 6.28), rnd.uniform(0, 6.28)
            waves = max(1, int(seg / 3500))
            for j in range(n):
                tt = j / n
                x, y = catmull(p0, p1, p2, p3, tt)
                x2, y2 = catmull(p0, p1, p2, p3, min(1, tt + 0.01))
                ddx, ddy = x2 - x, y2 - y
                LL = math.hypot(ddx, ddy) or 1
                nx, ny = -ddy / LL, ddx / LL
                taper = math.sin(math.pi * tt)
                off = amp * taper * (math.sin(tt * waves * math.pi * 2 + ph1) * 0.7 + math.sin(tt * waves * 5.3 + ph2) * 0.3)
                fine.append((x + nx * off, y + ny * off)); fattr.append(ai)
    fine.append(A[-1]); fattr.append(len(A) - 2)
    pts, attr = resample(fine, fattr, STEP_M)
    cum = [0.0]
    for i in range(1, len(pts)):
        cum.append(cum[-1] + dist(pts[i - 1], pts[i]))

    segments = []
    for i, a in enumerate(attr):
        road, lim = ANCHORS[a][2], ANCHORS[a][3]
        if not segments or segments[-1]["road"] != road or segments[-1]["limitKph"] != lim:
            segments.append({"fromIndex": i, "road": road, "limitKph": lim})

    def nearest(p):
        return min(range(len(pts)), key=lambda i: dist(pts[i], p))

    def index_at(d):
        return min(range(len(cum)), key=lambda i: abs(cum[i] - d))

    places = []
    for (pid, name, short, cat, purpose, kind, lat, lon, detour, dwell, off) in PLACES:
        target = to_xy(lat, lon)
        ni = nearest(target)
        if kind == "REST_AREA":
            a, b = pts[max(0, ni - 1)], pts[min(len(pts) - 1, ni + 1)]
            dx, dy = b[0] - a[0], b[1] - a[1]
            L = math.hypot(dx, dy) or 1
            target = (pts[ni][0] + dy / L * 120, pts[ni][1] - dx / L * 120)
            span = 400.0
        else:
            span = 700.0 + dist(pts[ni], target) * 0.3
        ex, mg = index_at(cum[ni] - span), index_at(cum[ni] + span)
        e, m = pts[ex], pts[mg]
        ctrl = [pts[max(0, ex - 2)], e, target, m, pts[min(len(pts) - 1, mg + 2)]]
        via = []
        for s in range(1, 4):
            q0, q1, q2, q3 = ctrl[s - 1], ctrl[s], ctrl[s + 1], ctrl[min(4, s + 2)]
            if s == 3:
                break
            n = max(4, int(dist(q1, q2) / 80))
            for k in range(1, n + 1):
                via.append(catmull(q0, q1, q2, q3, k / n))
        stop_index = len(via) // 2
        best = min(range(len(via)), key=lambda i: dist(via[i], target))
        via[best] = target
        stop_index = best
        dlat, dlon = to_ll(*target)
        dest = to_xy(ANCHORS[-1][0], ANCHORS[-1][1])
        places.append({
            "id": pid, "name": name, "shortName": short, "category": cat, "purpose": purpose, "kind": kind,
            "lat": round(dlat, 6), "lon": round(dlon, 6),
            "detourMin": detour, "dwellMin": dwell, "offRouteKm": off,
            "routeKm": round(cum[ni] / 1000, 2),
            "distanceToDestinationKm": round(dist(target, dest) / 1000, 1),
            "detour": {
                "exitIndex": ex, "mergeIndex": mg, "stopIndex": stop_index,
                "via": [[round(v, 6) for v in to_ll(*p)] for p in via],
            },
        })

    km = {p["id"]: p["routeKm"] for p in places}
    h = km["rest_hongcheon"]
    script = [
        {"atKm": round(h - 36, 1), "event": {"kind": "urgent", "memberId": "minjun", "type": "TOILET"}},
        {"atKm": round(h - 29, 1), "event": {"kind": "proposal", "memberId": "seoyeon", "placeId": "hanu_hoengseong", "message": "배고파! 점심 여기서 먹자"}},
        {"atKm": round(h - 28.6, 1), "event": {"kind": "vote", "memberId": "mom", "proposalId": "@last", "choice": "YES"}},
        {"atKm": round(h - 20, 1), "event": {"kind": "proposal", "memberId": "mom", "placeId": "sheep_daegwallyeong", "message": "양 보러 가고 싶어"}},
        {"atKm": round(h - 19.6, 1), "event": {"kind": "vote", "memberId": "seoyeon", "proposalId": "@last", "choice": "YES"}},
        {"atKm": round(h - 11, 1), "event": {"kind": "proposal", "memberId": "minjun", "placeId": "market_gangneung", "message": "도착하면 닭강정!"}},
        {"atKm": round(h - 10.6, 1), "event": {"kind": "vote", "memberId": "mom", "proposalId": "@last", "choice": "YES"}},
    ]

    out = {
        "seed": SEED,
        "startTime": "10:30",
        "origin": {"name": "서울 집", "shortName": "서울"},
        "destination": {"name": "강릉 경포해변", "shortName": "강릉"},
        "route": [[round(v, 6) for v in to_ll(*p)] for p in pts],
        "segments": segments,
        "turnTexts": [{"lat": t[0], "lon": t[1], "text": t[2]} for t in TURN_TEXTS],
        "places": places,
        "initialAccepted": [{"memberId": "mom", "placeId": "cafe_dumulmeori"}],
        "script": script,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print("points", len(pts), "km", round(cum[-1] / 1000, 1))
    for p in places:
        print(p["id"], p["routeKm"], "exit", p["detour"]["exitIndex"], "merge", p["detour"]["mergeIndex"], "via", len(p["detour"]["via"]))

if __name__ == "__main__":
    main(sys.argv[1])
