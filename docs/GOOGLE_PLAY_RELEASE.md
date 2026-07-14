# 아맞다주차! Google Play 출시 준비

이 문서는 현재 소스 기준의 출시 절차와 체크리스트다. Play Console에서 첫 앱을 만들기 전에
아래의 **결정 필요** 항목을 먼저 확정한다.

## 현재 준비 상태

- `targetSdk 35`, `minSdk 26`
- Google Play 신규 앱용 AAB 빌드 경로: `build-release.ps1`
- `USE_EXACT_ALARM`을 사용하지 않음. 출차/습관 알림은 Android 절전 정책에 따라 다소 늦을 수 있음
- 주차 기록·차량 이름·메모·습관 기록은 앱 전용 로컬 저장소에만 보관
- Android 자동 백업은 꺼져 있음 (`allowBackup=false`)
- 블루투스는 선택 기능이며, 블루투스 없는 기기에서도 수동 기록 설치 가능

## 결정 필요 — 첫 AAB 업로드 전

- [ ] **패키지 ID**: 현재 `com.ohdduck.parknote`. Google Play에 올린 뒤에는 바꾸거나 재사용할 수 없다.
- [ ] **개발자 표시 이름 / 지원 이메일**: Play Console과 개인정보 처리방침에 똑같이 사용한다.
- [ ] **가격 / 배포 국가**: 첫 출시의 무료/유료 여부는 나중에 유료에서 무료로는 바꿀 수 있어도 반대는 제한된다.
- [ ] **개인 개발자 계정 생성일**: 2023-11-13 이후 개인 계정이면 비공개 테스트 12명·연속 14일이 필요하다.
- [ ] `docs/PRIVACY_POLICY_TEMPLATE.md`의 대괄호 값을 실제 정보로 바꾼 뒤, 공개 HTTPS URL에 게시한다.

## 업로드 키와 AAB 만들기

1. 저장소 밖의 안전한 폴더에 업로드 키를 만든다. 비밀번호는 명령줄이나 Git에 기록하지 않는다.

   ```powershell
   & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
     -keystore "C:\secure\amatdajuchaj-upload.jks" `
     -alias amatdajuchaj -keyalg RSA -keysize 4096 -validity 10000
   ```

2. `keystore.properties.example`을 `keystore.properties`로 복사하고 실제 값만 로컬에 입력한다.
   이 파일과 `.jks` 파일은 `.gitignore`에 포함되어 있다.

3. AAB를 만든다.

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build-release.ps1
   ```

4. 결과물 `dist\AMatdaParking-release.aab`의 SHA-256을 보관한다. 이 파일만 Play Console에 올린다.
   `build.ps1`이 만드는 APK는 직접 설치/개발 확인용이며 Play 출시용이 아니다.

5. 첫 업로드에서 **Play App Signing**을 설정한다. Google이 앱 서명 키를 관리하고, 로컬 업로드 키는
   업로드 인증에만 쓰는 구성이 권장된다.

## Play Console 입력값 초안

자세한 복사·붙여넣기 문구는 `docs/STORE_LISTING_KO.md`에 있다.

| 항목 | 권장 값 |
|---|---|
| 앱 | 앱 |
| 기본 언어 | 한국어(대한민국) |
| 앱 이름 | 아맞다주차! |
| 카테고리 | 자동차 및 차량 (또는 생산성) |
| 광고 | 없음 |
| 앱 액세스 | 로그인·제한 없음 |
| Data safety | 수집·공유 없음 — 최종 AAB에 네트워크/분석/광고 SDK가 없다는 전제 |
| 개인정보 처리방침 | 공개 HTTPS URL (템플릿의 실제 개발자명·지원 이메일 필수) |
| 콘텐츠 등급 | IARC 설문을 실제 기능에 맞게 작성 |

## 테스트와 출시 순서

1. **내부 테스트**: 본인과 가까운 테스터 기기에서 설치·권한·위젯·블루투스·알림을 빠르게 검증한다.
2. **비공개 테스트**: `docs/PLAY_RELEASE_TEST_PLAN.md`를 테스터에게 전달하고 피드백을 기록한다.
3. 새 개인 계정이면 12명 이상이 14일 연속 참여한 뒤 프로덕션 액세스를 신청한다.
4. 프로덕션 액세스 후에는 소규모 단계적 출시로 시작하고, Android Vitals·크래시·리뷰를 확인한다.

## Play Console에서 확인할 정책 항목

- Data safety, 개인정보 처리방침, 광고, 앱 액세스, 타깃 연령층, 콘텐츠 등급
- `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` 권한의 실제 사용 목적
- 개인정보 처리방침 URL과 스토어 설명/스크린샷이 앱의 실제 기능과 일치하는지

## 공식 문서

- [Google Play 신규 앱의 AAB 요구사항](https://developer.android.com/studio/publish/)
- [Play App Signing](https://developer.android.com/studio/publish/app-signing)
- [현재 Target API 요구사항](https://developer.android.com/google/play/requirements/target-sdk)
- [Data safety 작성법](https://support.google.com/googleplay/android-developer/answer/10787469)
- [개인 계정 비공개 테스트 요건](https://support.google.com/googleplay/android-developer/answer/14151465)
- [스토어 그래픽 규격](https://support.google.com/googleplay/android-developer/answer/9866151)
