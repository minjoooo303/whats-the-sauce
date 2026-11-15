# What's the Sauce?

드라이브스루 콘셉트의 차량용 인터랙티브 오디오 앱 (Pleos Connect / Android Automotive OS).
소스(감정·분위기)를 골라 사연을 주문하고, 햄스터 진행자 **CREW** 에게 듣는 도중 언제든 질문할 수 있다.

```
whats-the-sauce/
├─ android/   Android Studio 프로젝트 (Kotlin, Jetpack Compose)
└─ backend/   CREW 백엔드 (Python, FastAPI) — LLM 대화, 음성 주문 해석
```

## 1. 실행 준비

### Pleos Connect Emulator
[개발 환경 설정 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) 그대로 진행한다.

1. Android Studio (최신) → **Tools > SDK Manager > SDK Update Sites** 에 `Pleos Connect System Image` 추가
   - Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. **SDK Platforms** 에서 API 34 의 Pleos Connect system image 설치 → Android Studio 재시작
3. **Device Manager > Create device > Automotive > Pleos Connect v2.1.0**, Default boot `Cold`, RAM `4 GB`, API 34
4. 에뮬레이터 실행. 음성 기능을 쓰려면 에뮬레이터 설정에서 **마이크 접근 허용** (Virtual Console)

### 백엔드 (선택 — 없어도 앱은 동작)
```bash
cd backend
pip install -r requirements.txt
export ANTHROPIC_API_KEY=...        # 없으면 규칙 기반 CREW 로 동작
uvicorn main:app --host 0.0.0.0 --port 8000
```
- 에뮬레이터에서 PC 는 `http://10.0.2.2:8000` 으로 보인다 (기본값).
- LLM 모델은 `CREW_MODEL` 환경변수로 바꿀 수 있다 (기본 `claude-haiku-5-5`).

### 앱
1. Android Studio 에서 `android/` 폴더를 연다 (JDK 17, Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.21).
2. 필요하면 `android/local.properties` 에 추가 (`local.properties.example` 참고):
   ```properties
   crew.baseUrl=http://10.0.2.2:8000
   pleos.clientId=...        # Pleos Playground > My Project 에서 발급
   pleos.clientSecret=...
   ```
   Client ID/Secret 이 없으면 STT/TTS 를 **ON_DEVICE** 모드로 쓴다 (앱 등록 불필요). 있으면 HYBRID + `registerApp()`.
3. Gradle Sync → Pleos 에뮬레이터에 Run.

> Pleos SDK 는 `https://nexus-playground.pleos.ai/repository/maven-releases/` 에서 받는다 (settings.gradle.kts 에 설정됨).
> 사용 SDK: SpeechToText 3.3.1 · TextToSpeech 3.3.1 · NaviHelper 3.0.5

## 2. 구현된 것

| 기획서 | 구현 |
|---|---|
| 소스 6종 + 맛 태그 | 단맛·매운맛·고소함·새콤함·씁쓸함 (0~100). 소스 설명도 실제 맛 표현 (예: Sweet Chili "달콤하다가 끝맛이 매콤한 맛") |
| 소스 믹싱 추천 | `reco/Recommender.kt` — 맛 벡터 코사인 유사도, 최근 들은 사연 제외 |
| 음성 주문 | 재생 중인 사연이 없을 때 핸들 재생 키를 길게 → "달콤하고 새콤한 썰 줘" / "조금 더 맵게" |
| CREW 실시간 대화 | **핸들 재생/일시정지 키를 길게(0.6초)** 누르면: 현재 문장 위치 저장 → 일시정지 → STT → 답변 → 그 문장부터 재개. 짧게 누르면 평소처럼 재생/일시정지. 화면 재생 버튼도 같은 규칙. 시스템 미디어 화면에는 "CREW에게 묻기" 버튼(커스텀 액션) |
| 주행거리 기반 세트 | 남은 거리 8km 마다 1편 (최대 8편). 1편 Single, 2~3편 Combo, 4편 이상 Large. 남은 시간을 알면 그 안에서만 편성 |
| 재편성 | 사연이 끝날 때마다 남은 거리로 다음 메뉴를 다시 짬. 마지막엔 "하나 더?" 제안 |
| 거리 정보가 없을 때 | 테스트 주행 (상단 거리 칩을 누르면 4→12→25→45km, 평균 40km/h로 줄어듦) |
| Sauce Factory (썰 제보) | 말로 제보(받아쓰기), 정차 중엔 직접 수정/입력, 소스 선택(또는 CREW에게 맡기기), 닉네임. 서버(`/factory/submit`)에 못 보내면 기기에 보관 후 재전송 |
| 주행 중 UX 제한 | `CarUxRestrictionsManager` — 주행 중엔 Mix 슬라이더·제보 글자 입력 잠금, 목록 짧게 |
| 화면 | 메뉴는 아래 가운데, 지금 화면은 케첩 자국으로 표시. Home·Now Serving 에 CREW 자막 없음 (소리로만) |
| 차량 미디어 연동 | `MediaBrowserServiceCompat` + `MediaSessionCompat` — 스티어링 휠 키, 미디어 ID·검색 재생, 브라우즈 트리, 오디오 포커스, 자동 재생 없음 |

### 핸들 버튼 메모
- 대부분의 AAOS 차량에서 **음성(PTT) 버튼은 시스템 음성 비서(Gleo AI)가 먼저 가져가서** 일반 앱에 전달되지 않는다. 그래서 앱이 확실히 받을 수 있는 **재생/일시정지 키 길게 누르기**를 CREW 호출로 썼다. PTT 키가 앱까지 오는 차량이면 그 키도 바로 CREW 호출로 동작하게 해 두었다.
- 정식으로 "Gleo, CREW한테 물어봐" 같은 음성 호출을 넣으려면 Pleos 의 Gleo Interface 연동이 필요하다.
- 에뮬레이터에서 테스트: `adb shell input keyevent --longpress KEYCODE_MEDIA_PLAY_PAUSE` 또는 화면 재생 버튼을 길게 누르기.

### 동작 방식 메모
- **사연 낭독**은 사전 녹음 파일 대신 TTS 로 문장 단위 낭독한다. 그래서 대화 후 "이전 재생 위치"가 문장 단위로 정확히 복귀된다. 사전 생성 음성 파일로 바꾸려면 `StoryPlayer.narrate()` 만 교체하면 된다.
- Pleos STT/TTS 초기화에 실패하면(일반 AAOS 에뮬레이터 등) 안드로이드 기본 TTS / SpeechRecognizer 로 자동 전환한다.
- 백엔드에 닿지 않으면 CREW 는 앱 내장 규칙(`crew/LocalCrew`)으로 답한다. 사연 중 질문은 "지금까지 들은 문장" 중 가장 관련 있는 문장을 근거로 답한다.
- 샘플 사연은 1~2분 분량이다. 실제 서비스 분량(5~8분)으로 늘려도 편성 로직은 그대로 동작한다.
- `backend/data/stories.json` 과 `android/app/src/main/assets/stories.json` 은 같은 파일이다. 수정 시 둘 다 갱신.

## 3. 테스트 시나리오 (기획서 4장)
1. 앱 실행 → CREW: "어서 오세요! 목적지까지 20분 정도 남았네요…"
2. Home 마이크 → "오늘은 달달한 썰이 듣고 싶은데, 반전도 조금 있었으면 좋겠어" → Sweet Chili 중심 세트로 바뀜 → **이 세트로 주문**
3. Now Serving 에서 사연 중 **CREW에게 묻기** → "잠깐, 그 사람이 전 남친 친구인 걸 처음부터 알고 있었던 거야?" → 답변 후 끊긴 문장부터 이어서 재생
4. Home 시간 칩으로 남은 시간을 8분으로 바꾸면 다음 사연 편성이 짧아지고, 마지막에 "하나 더 어떠세요?" 제안
5. Vehicle Emulator 에서 주행 상태로 바꾸면 Mix 슬라이더가 잠기고 "주행 중 · 음성 모드" 로 표시
