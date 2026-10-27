# 품 드라이브 (Poom Drive)

> 동승자와 운전자를 함께 챙기는 Pleos Connect 차량용 앱

## 프로젝트 소개
품 드라이브는 차량에 누가 탑승했는지 인식하고, 탑승자에 맞춰 부드러운 운전·실내 공기·휴식을 음성으로 안내하는 차량용 앱입니다. 운전자가 혼자 탑승한 경우에는 운전자의 자세와 휴식을 관리합니다.

본 프로젝트는 현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작입니다.

## 주요 기능
| 핵심 기능 | 내용 |
|---|---|
| 탑승자 인식과 출발 전 점검 | 뒷좌석 탑승 감지, 음성으로 동승자 유형 확인(아이·부모님·반려견), 아이 동승 시 카시트 점검 안내와 동화 제안 |
| 흔들림 안내와 흔들림 점수 | 급제동·급가속·급회전 감지 시 동승자 기준 음성 안내, 도착 후 흔들림 점수와 멀미 여부 기록 |
| 공기·온도 케어 | 내기순환 1시간 지속 시 외기 전환 권유, 실내 온도가 설정보다 높을 때 풍량 안내 |
| 음성 한마디 휴식 | 2시간 연속 주행 시 휴식 제안, "응" 응답 시 가장 가까운 졸음쉼터를 경유지로 추가 |

| 탑승 모드 | 안내 |
|---|---|
| 아이 동승 | 카시트 점검, 동화 낭독, 흔들림·더위 안내 |
| 부모님 동승 | 흔들림 안내, 멀미 확인 |
| 반려견 동승 | 실내 온도·환기, 2시간 휴식 |
| 운전자 단독 | 환기, 신호 대기 중 자세 안내, 휴식 제안 |

- 주행 중에는 화면 없이 음성으로만 안내하며, 운전자는 "응"·"아니"로 답합니다.
- 정차 중(P단)에만 화면을 사용합니다.

## 활용 기술
| 구분 | 내용 |
|---|---|
| Pleos Vehicle SDK | 좌석 탑승, 공조(내기순환·풍량·실내·설정 온도), 조향각, 문, 브레이크 |
| AAOS Car API | 차속, 기어, 외기 온도, 주행 제한 상태 |
| Pleos NaviHelper | 현재 위치, 경로 요청, 경유지 추가 |
| Pleos TTS·STT | 온디바이스(ON_DEVICE) 음성 안내와 짧은 대답 인식 |
| Android 표준 | 부팅 완료 시 자동 시작, 휴대폰 블루투스 연결 시 시작 알림, 포그라운드 서비스 |
| 공공데이터 | 전국졸음쉼터표준데이터 253곳 (한국도로공사) |
| 콘텐츠 | 저작권 보호 기간이 끝난 동화, 방정환 「만년 셔츠」(한국어 위키문헌, PD-old-70) |

## 검증
- Pleos Connect Emulator v2.1.0에서 핵심 기능 18개 항목을 시험했습니다. 상세 결과와 화면은 [docs/verification.md](docs/verification.md)에 있습니다.

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
에뮬레이터에서 재현하기 어려운 상황은 adb 전용 명령으로 재현합니다.

```
adb shell am broadcast --user 10 -a com.seattlegymsquad.poomdrive.DEMO -n com.seattlegymsquad.poomdrive/.DemoReceiver --es cmd <명령>
```

| 명령 | 내용 |
|---|---|
| `timescale:60` | 시간 압축 (1초를 1분으로 계산) |
| `cabin:31`, `cabin:off` | 시연용 실내 온도 |
| `answer:yes` 등 | 다음 질문에 미리 녹음한 대답 입력 (`yes`, `no`, `ne`, `geurae`, `child`, `parent`, `pet`) |
| `mode:auto` 등 | 탑승 모드 (`auto`, `child`, `parent`, `pet`, `solo`) |
| `trigger:rest` 등 | 기능 즉시 실행 (`rest`, `ventilation`, `heat`, `boarding`, `story`, `posture`, `brake`, `end`) |
| `drive:<초>` | 연속 주행 시간 지정 |
| `away:<초>` | 지정한 시간 동안 Suspend 후 복귀한 상황 재현 (대기 안내 폐기, 정차 시간 반영) |
| `service:stop` | 케어 서비스 종료 (Suspend 중 앱이 종료된 상황 재현) |
| `bt:connected` | 휴대폰 블루투스 연결 신호 대체 (에뮬레이터 블루투스 미지원) |
| `status` | 현재 상태 로그 출력 |

## 폴더 구조
| 경로 | 내용 |
|---|---|
| `app/src/main/java/.../vehicle` | 차량 신호 수신 |
| `app/src/main/java/.../voice` | 음성 안내·질문·대답 판정 |
| `app/src/main/java/.../navi` | 내비게이션 연동 |
| `app/src/main/java/.../care` | 케어 규칙, 주행 기록 저장, 시연 설정 |
| `app/src/main/java/.../data` | 졸음쉼터 데이터, 동화 |
| `app/src/main/assets` | 졸음쉼터 데이터, 동화 본문, 시연용 대답 음성 |
| `apk/` | 제출용 release APK |
| `docs/` | 검증 결과 |
