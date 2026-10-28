# 품 드라이브 (Poom Drive)

> 동승자와 운전자를 함께 챙기는 Pleos Connect 차량용 앱

## 프로젝트 소개
품 드라이브는 차량에 누가 탑승했는지 인식하고, 탑승자에 맞춰 부드러운 운전·실내 공기·휴식을 음성으로 안내하는 차량용 앱입니다. 운전자가 혼자 탑승한 경우에는 운전자의 자세와 휴식을 관리합니다.

본 프로젝트는 현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작입니다.

## 주요 기능
| 핵심 기능 | 내용 |
|---|---|
| 탑승자 인식과 출발 전 점검 | 좌석 탑승 감지, 좌석이 빈 채 뒷문이 열렸다 닫히면 질문(좌석 센서에 잡히지 않는 반려견), 음성으로 동승자 확인(여러 명 동시 인식, 예: "아이랑 강아지요"), 하루 첫 출발에만 카시트·반려견 안전장비 점검, 아이 동승 시 동화 제안 |
| 동승자를 모른 채 출발 | 가장 조심스러운 기준으로 안내, 첫 정차(또는 주행 3분 후)에 음성으로 1회 재질문, 알림 센터에서 동승자 선택(주행 중 사용 가능, 동승자 선택만 제공) |
| 흔들림 안내와 포근 지수 | 급제동·급가속·급회전 감지 시 동승자 기준 음성 안내, 20분 이상 주행 후 포근 지수(30분당 흔들림 기준)와 멀미 여부 기록, 멀미한 동승자는 다음 주행부터 작은 흔들림도 안내 |
| 공기·온도 케어 | 내기순환 1시간 지속 시 외기 전환 권유, 실내 온도가 설정보다 높을 때 풍량 안내 |
| 음성 한마디 휴식 | 2시간 연속 주행 시 휴식 제안, "네" 응답 시 가장 가까운 졸음쉼터를 경유지로 추가 |

| 탑승 모드 | 안내 |
|---|---|
| 아이 동승 | 카시트 점검, 동화 낭독, 흔들림·더위 안내 |
| 부모님 동승 | 흔들림 안내, 멀미 확인 |
| 반려견 동승 | 안전장비 점검, 실내 온도(설정보다 2℃ 높으면 안내)·환기, 2시간 휴식 |
| 여러 동승자 | 탑승한 동승자의 기능을 모두 적용하고 가장 보수적인 기준 사용, 안내 주어 결합(예: "아이와 반려견이") |
| 운전자 단독 | 환기, 신호 대기 중 자세 안내, 휴식 제안 |

- 주행 중에는 화면 없이 음성으로만 안내하며, 운전자는 "네"·"아니요"(또는 "응"·"아니")로 답합니다.
- 정차 중(P단)에만 화면을 사용합니다. Pleos 차량 제어 앱과 같은 타일 구성, 낮·밤 테마, Asta Sans 글꼴을 적용했습니다.

## 활용 기술
| 구분 | 내용 |
|---|---|
| Pleos Vehicle SDK | 좌석 탑승, 공조(내기순환·풍량·실내·설정 온도), 조향각, 문, 브레이크 |
| AAOS Car API | 차속, 기어, 외기 온도, 주행 제한 상태 |
| Pleos NaviHelper | 현재 위치, 경로 요청, 경유지 추가 |
| Pleos TTS·STT | 온디바이스(ON_DEVICE) 음성 안내와 짧은 대답 인식 |
| Android 표준 | 부팅 완료 시 자동 시작, 휴대폰 블루투스 연결 시 서비스 재시작(시스템이 허용하지 않으면 시작 알림과 음성 안내), 알림 센터 동승자 선택, 포그라운드 서비스 |
| 공공데이터 | 전국졸음쉼터표준데이터 253곳 (한국도로공사) |
| 콘텐츠 | 전래동화 「해님 달님」, 「흥부와 놀부」 (옛이야기를 팀이 새로 쓴 글, 화면 없이 소리로 듣는 이야기, 문장마다 쉬어 가며 낭독하고 안내로 끊기면 그 문장부터 이어 읽기) |
| 글꼴 | Asta Sans (42dot, SIL Open Font License 1.1, `app/src/main/assets/licenses/AstaSans-OFL.txt`) |
| 앱 아이콘 | Google Gemini로 생성한 이미지를 팀이 편집 |

## 검증
- Pleos Connect Emulator v2.1.0에서 핵심 기능 29개 항목을 시험했습니다. 상세 결과와 화면은 [docs/verification.md](docs/verification.md)에 있습니다.

## 시연 영상
- 영상 파일: [docs/poom-drive-demo.mp4](docs/poom-drive-demo.mp4)
- Pleos Connect Emulator v2.1.0에서 실제로 동작하는 앱을 녹화한 영상입니다.
- 화면 전환, 안내 판단과 순서, 알림, 동화 낭독과 이어 읽기는 앱이 차량 신호를 받아 실시간으로 처리한 결과입니다.
- 차량 신호(차속, 기어, 좌석 탑승, 실내 온도 등)는 에뮬레이터의 Virtual Console로 입력했습니다.
- 영상 제작 과정에서 보완한 항목은 다음과 같습니다.

| 항목 | 내용 |
|---|---|
| 지도 | 에뮬레이터 내비게이션 지도가 비어 있고 위치가 고정되어 있어, 실제 도로 경로(© OpenStreetMap, OSRM)를 Pleos 지도 스타일로 그려 합성했습니다. 화면에 '합성'으로 표기했습니다. |
| 앱 음성 | 녹화 중 에뮬레이터 CPU 부하로 음성이 끊겨, 같은 앱이 같은 문장을 다시 말하게 하여 별도로 녹음했습니다. 목소리와 문장은 앱 출력 그대로이며, 영상에서 앱이 말한 시점에 배치했습니다. |
| 운전자 대답 | 시연 명령(`answer:`)으로 미리 녹음한 대답을 음성 인식기에 직접 입력해 녹화에 소리가 남지 않으므로, 같은 말을 합성 음성으로 덧붙였습니다. |
| 시간 경과 | '20분 뒤' 등의 구간은 시연 명령으로 앱의 시간을 빠르게 진행하고, 영상에서는 잘라 냈습니다. |
| 설명 화면 | 자막, 장면 제목, 차량 신호 패널, 속도·핸들 그래프는 이해를 돕기 위해 덧붙인 화면입니다. |

## 개발 환경
- 언어: Kotlin
- 플랫폼: Pleos Connect (Android Automotive OS, Android 14)
- 빌드: Gradle 8.10.2, Android Gradle Plugin 8.8.2, Kotlin 2.0.0
- SDK: Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech·SpeechToText 3.3.1

## 빌드와 실행
1. `local.properties`에 Android SDK 경로(`sdk.dir`)를 지정합니다.
2. 빌드: `./gradlew assembleDebug`
3. 설치: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. 차속 권한 허용: `adb shell pm grant --user 10 com.seattlegymsquad.poomdrive android.car.permission.CAR_SPEED`
5. 실행: `adb shell am start --user 10 -n com.seattlegymsquad.poomdrive/.MainActivity`

## 시연 명령
에뮬레이터에서 재현하기 어려운 상황은 adb 전용 명령으로 재현합니다. 정차 화면의 제목을 3초 누르면 자주 쓰는 명령을 버튼으로 실행할 수 있습니다.

```
adb shell am broadcast --user 10 -a com.seattlegymsquad.poomdrive.DEMO -n com.seattlegymsquad.poomdrive/.DemoReceiver --es cmd <명령>
```

| 명령 | 내용 |
|---|---|
| `timescale:60` | 시간 압축 (1초를 1분으로 계산) |
| `cabin:31`, `cabin:off` | 시연용 실내 온도 |
| `answer:yes` 등 | 다음 질문에 미리 녹음한 대답 입력 (`yes`, `no`, `ne`, `aniyo`, `geurae`, `child`, `parent`, `pet`, `child_pet`) |
| `mode:auto` 등 | 동승자 선택 (`auto`, `solo`, 또는 `child`, `child+pet`처럼 동승자 조합) |
| `pick:child` 등 | 알림 센터의 동승자 버튼을 누른 것과 같은 동작 (`child`, `parent`, `pet`) |
| `trigger:rest` 등 | 기능 즉시 실행 (`rest`, `ventilation`, `heat`, `boarding`, `story`, `posture`, `brake`, `end`, `pickpassenger`) |
| `drive:<초>` | 연속 주행 시간 지정 |
| `away:<초>` | 지정한 시간 동안 Suspend 후 복귀한 상황 재현 (대기 안내 폐기, 정차 시간 반영) |
| `service:stop` | 케어 서비스 종료 (Suspend 중 앱이 종료된 상황 재현) |
| `bt:connected` | 휴대폰 블루투스 연결 신호 대체 (에뮬레이터 블루투스 미지원) |
| `sensitive:clear` | 멀미 기록(작은 흔들림도 안내할 동승자) 초기화 |
| `safety:clear` | 오늘 한 카시트·안전장비 점검 기록 초기화 |
| `story:0` 등 | 다음에 읽을 동화 지정 (`0` 해님 달님, `1` 흥부와 놀부) |
| `say:<문장>` | 앱 음성으로 문장 읽기 (시연 영상 음성 녹음용) |
| `status` | 현재 상태 로그 출력 |

## 폴더 구조
| 경로 | 내용 |
|---|---|
| `app/src/main/java/.../vehicle` | 차량 신호 수신 |
| `app/src/main/java/.../voice` | 음성 안내·질문·대답 판정 |
| `app/src/main/java/.../navi` | 내비게이션 연동 |
| `app/src/main/java/.../care` | 케어 규칙, 주행 기록 저장, 시연 설정 |
| `app/src/main/java/.../data` | 졸음쉼터 데이터, 동화 |
| `app/src/main/assets` | 졸음쉼터 데이터, 동화 본문, 시연용 대답 음성, 글꼴과 라이선스 |
| `apk/` | 제출용 release APK |
| `docs/` | 검증 결과 |
