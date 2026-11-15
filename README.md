# What's the Sauce?

**드라이브스루처럼 '맛(소스)'으로 사연을 주문해 듣고, 햄스터 진행자 CREW에게 언제든 말로 물어보는 차량용 인터랙티브 오디오 앱** (Pleos Connect / Android Automotive OS)

- 팀: **opensauce**
- APK: [`apk/opensauce_WhatsTheSauce_release.apk`](apk/opensauce_WhatsTheSauce_release.apk)

```
whats-the-sauce/
├─ apk/       제출용 release APK
├─ android/   Android Studio 프로젝트 (Kotlin, Jetpack Compose)
└─ backend/   CREW 백엔드 (Python, FastAPI, 선택) — LLM 대화, 음성 주문 해석
```

## 1. 개발·테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** (Android 14, API 34, `android-automotive`, arm64-v8a) |
| 화면 규격 | **2560 × 1440 px, 160 dpi** (메인 디스플레이) |
| 에뮬레이터 설정 | RAM 4 GB, data partition 6 GB, Cold boot |
| 개발 PC | macOS (Apple Silicon), Android Studio, JDK 21 (Gradle JVM) |
| 빌드 | Kotlin 2.0.21, AGP 8.8.2, Gradle 8.10.2, compileSdk 35, minSdk 33 |
| Pleos SDK | SpeechToText 3.3.1 · TextToSpeech 3.3.1 · NaviHelper 3.0.5 |

## 2. 실행 방법

### APK로 바로 실행 (심사용)
1. Pleos Connect v2.1.0 에뮬레이터를 켠다 (아래 3장 설정 참고).
2. APK 설치
   ```bash
   adb install -r apk/opensauce_WhatsTheSauce_release.apk
   ```
   또는 APK 파일을 에뮬레이터 화면으로 끌어다 놓는다.
3. 하단 앱 목록(⊞)에서 **What's the Sauce?** 실행. 처음 실행 시 마이크·알림 권한을 허용한다.

### 소스에서 빌드
1. Android Studio 에서 `android/` 폴더를 연다 → Gradle Sync.
2. 실행 기기로 Pleos Connect v2.1.0 을 고르고 ▷ Run.
3. release APK 다시 만들기: 저장소 루트에서 `./build_release.sh` (→ `apk/opensauce_WhatsTheSauce_release.apk`).
   release 는 별도 키 없이 설치되도록 디버그 키로 서명된다.

## 3. 필요한 설정

### Pleos Connect 에뮬레이터
[개발 환경 설정 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) 그대로 진행한다.

1. Android Studio → **Tools > SDK Manager > SDK Update Sites** 에 `Pleos Connect System Image` 추가
   - Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. **SDK Platforms** 에서 API 34 의 Pleos Connect system image 설치 → Android Studio 재시작
3. **Device Manager > Create device > Automotive > Pleos Connect v2.1.0**, API 34, RAM 4 GB
   - 디스크 공간 부족으로 에뮬레이터가 꺼지면 AVD `config.ini` 의 `disk.dataPartition.size` 를 `6G` 로 낮춘다.

### 앱 사용 팁
- **남은 거리 연동**: 에뮬레이터 내비에서 목적지를 정해 안내를 시작하면, 앱이 내비의 남은 거리로 세트를 다시 짠다. 안내가 없으면 테스트 거리(25km)로 동작한다.
- **CREW에게 묻기**: 사연 재생 중 화면의 재생 버튼(또는 핸들 재생/일시정지 키)을 **길게(0.5초)** 누른다.
- **음성**: 기본은 데모 음성 모드(마이크 불필요). 실제 마이크로 말하려면 상단 "음성 · 데모" 태그를 눌러 "음성 · 마이크"로 바꾸고, 에뮬레이터 **Extended controls > Microphone > Virtual microphone uses host audio input** 을 켠다.
- Pleos Client ID/Secret 은 없어도 된다 (STT/TTS 를 ON_DEVICE 로 사용). 있으면 `android/local.properties` 에 넣는다 (`local.properties.example` 참고).

### 백엔드 (선택 — 없어도 앱은 동작)
```bash
cd backend
pip install -r requirements.txt
export ANTHROPIC_API_KEY=...        # 없으면 규칙 기반 CREW 로 동작
uvicorn main:app --host 0.0.0.0 --port 8000
```
- 에뮬레이터에서 PC 는 `http://10.0.2.2:8000` 으로 보인다 (기본값). 백엔드가 없으면 앱 내장 규칙(`LocalCrew`)으로 답한다.

## 4. 테스트 시나리오
1. 앱 실행 → Home: "남은 거리 25km → Large Set" (테스트 거리)
2. 내비에서 목적지(예: 현대자동차 본사) 안내 시작 → 앱으로 돌아오면 내비와 같은 남은 거리, 세트 편수가 바뀜 (예: 9.0km → Combo Set 2편)
3. Mix 에서 맛 슬라이더를 바꾸면 접시 위 소스가 바뀜 → "이 맛으로 사연 추천받기"
4. Home **이 세트 듣기** → Now Serving 에서 CREW 인사 후 사연 낭독
5. 재생 버튼을 길게 → CREW 듣는 중 → 답변 → 끊긴 문장부터 이어서 낭독
6. Sauce Factory → **말로 제보하기** → 받아쓴 썰을 **제보 보내기**

## 5. 구현된 기능

| 기획서 | 구현 |
|---|---|
| 소스 6종 + 맛 태그 | 단맛·매운맛·고소함·새콤함·씁쓸함 (0~100). 소스 설명도 실제 맛 표현 (예: Sweet Chili "달콤하다가 끝맛이 매콤한 맛") |
| 소스 믹싱 추천 | `reco/Recommender.kt` — 맛 벡터 코사인 유사도, 최근 들은 사연 제외 |
| 음성 주문 | 재생 중인 사연이 없을 때 핸들 재생 키를 길게 → "달콤하고 새콤한 썰 줘" / "조금 더 맵게" |
| CREW 실시간 대화 | **핸들 재생/일시정지 키를 길게(0.5~0.6초)** 누르면: 현재 문장 위치 저장 → 일시정지 → STT → 답변 → 그 문장부터 재개. 짧게 누르면 평소처럼 재생/일시정지. 화면 재생 버튼도 같은 규칙. 시스템 미디어 화면에는 "CREW에게 묻기" 버튼(커스텀 액션) |
| 주행거리 기반 세트 | **Pleos 내비(NaviHelper) 남은 거리**로 편성. 8km 마다 1편 (최대 8편). 1편 Single, 2~3편 Combo, 4편 이상 Large. 남은 시간 안에서만 편성. Home 상단 카드에 "남은 거리 → 세트"를 크게 표시 |
| 재편성 | 사연이 끝날 때마다 남은 거리로 다음 메뉴를 다시 짬. 마지막엔 "하나 더?" 제안 |
| 내비 안내가 없을 때 | 테스트 주행 거리 (기본 25km, 상단 거리 칩/Home 거리 카드를 누르면 45→4→12→25km) |
| 데모 음성 모드 | 상단 "음성 · 데모" 태그(기본 켜짐): 마이크 없이 미리 정한 질문/주문/제보 문장이 받아쓰기처럼 들어감. 누르면 "음성 · 마이크"(실제 STT)로 전환 |
| Sauce Factory (썰 제보) | 말로 제보(받아쓰기), 정차 중엔 직접 수정/입력, 소스 선택(또는 CREW에게 맡기기), 닉네임. 서버(`/factory/submit`)에 못 보내면 기기에 보관 후 재전송 |
| 주행 중 UX 제한 | `CarUxRestrictionsManager` — 주행 중엔 Mix 슬라이더·제보 글자 입력 잠금, 목록 짧게 |
| 화면 | 메뉴는 아래 가운데, 지금 화면은 케첩 자국으로 표시. Home·Now Serving 에 CREW 자막 없음 (소리로만) |
| 차량 미디어 연동 | `MediaBrowserServiceCompat` + `MediaSessionCompat` — 스티어링 휠 키, 미디어 ID·검색 재생, 브라우즈 트리, 오디오 포커스, 자동 재생 없음 |

### 핸들 버튼 메모
- 대부분의 AAOS 차량에서 **음성(PTT) 버튼은 시스템 음성 비서(Gleo AI)가 먼저 가져가서** 일반 앱에 전달되지 않는다. 그래서 앱이 확실히 받을 수 있는 **재생/일시정지 키 길게 누르기**를 CREW 호출로 썼다. PTT 키가 앱까지 오는 차량이면 그 키도 바로 CREW 호출로 동작하게 해 두었다.
- 정식으로 핸들 음성 버튼 → Gleo AI → 앱 기능 호출을 하려면 Pleos App Schema(App Capability)에 기능을 등록해야 한다 (향후 과제).
- 에뮬레이터에서 테스트: `adb shell input keyevent --longpress KEYCODE_MEDIA_PLAY_PAUSE` 또는 화면 재생 버튼을 길게 누르기.

### 동작 방식 메모
- **사연 낭독**은 사전 녹음 파일 대신 TTS 로 문장 단위 낭독한다. 그래서 대화 후 "이전 재생 위치"가 문장 단위로 정확히 복귀된다. 사전 생성 음성 파일로 바꾸려면 `StoryPlayer.narrate()` 만 교체하면 된다.
- Pleos STT/TTS 초기화에 실패하면(일반 AAOS 에뮬레이터 등) 안드로이드 기본 TTS / SpeechRecognizer 로 자동 전환한다.
- 백엔드에 닿지 않으면 CREW 는 앱 내장 규칙(`crew/LocalCrew`)으로 답한다. 사연 중 질문은 "지금까지 들은 문장" 중 가장 관련 있는 문장을 근거로 답한다.
- 샘플 사연은 1~2분 분량이다. 실제 서비스 분량(5~8분)으로 늘려도 편성 로직은 그대로 동작한다.
- `backend/data/stories.json` 과 `android/app/src/main/assets/stories.json` 은 같은 파일이다. 수정 시 둘 다 갱신.
