# 비품 관리 (Supply Manager)

집과 파티룸의 비품을 **대분류 → 중분류 → 소분류 → 구매 내역** 4단 구조로 관리하는
Android 앱입니다. 모든 데이터는 기기 안(Room/SQLite)에만 저장되며 서버가 필요 없습니다.

## 구조

| 단계 | 화면 | 설명 |
| --- | --- | --- |
| 대분류 | 하단 탭 | `집` · `파티룸` · `설정` (고정) |
| 중분류 | 폴더 목록 | 대분류별 폴더 (집-화장실, 집-거실 …) 추가/이름변경/삭제 |
| 소분류 | 품목 목록 | 폴더별 비품(휴지, 수건 …) 추가/수정/삭제 |
| 구매 내역 | 품목 상세 | **구매 일자 · 갯수 · 비용** 추가/수정/삭제 |

각 단계는 하위 데이터를 자동 집계합니다.

- 품목 상세: 총 갯수, 총 비용, 구매 횟수, 평균 단가
- 폴더 목록: 품목 수, 총 갯수, 폴더 총 비용
- 설정 탭: 대분류별 / 전체 합계

폴더나 품목을 삭제하면 하위 데이터도 외래키 `ON DELETE CASCADE` 로 함께 지워집니다.

## 기술 스택

- Kotlin 2.1.21 / Jetpack Compose (Material 3)
- Room 2.7 (KSP) — 로컬 영속화
- Navigation Compose — 탭 + 계층 라우팅
- DataStore Preferences — 테마 설정
- 수동 DI (`AppContainer`) — 모듈 규모가 작아 DI 프레임워크 미사용

`minSdk 26` / `targetSdk 35` / `compileSdk 35`, JDK 17.

## 패키지 구성

```
com.kibilotus.supplymanager
├── SupplyApplication.kt      앱 진입점, AppContainer 생성
├── AppContainer.kt           DB · Repository · SettingsStore 조립
├── MainActivity.kt           Compose 호스트 + 테마 적용
├── domain/MainCategory.kt    대분류(탭) 정의
├── data/
│   ├── SupplyRepository.kt   입력 검증 + 단일 데이터 창구
│   ├── PurchaseDraft.kt      구매 입력값 검증 (순수 JVM, 테스트 대상)
│   ├── SaveResult.kt         저장 결과(성공/중복/형식 오류)
│   ├── local/                Room Entity · DAO · 집계 Projection
│   └── settings/             DataStore 테마 설정
└── ui/
    ├── navigation/           라우트, 하단 탭, NavHost
    ├── folder/               중분류 목록
    ├── item/                 소분류 목록
    ├── detail/               구매 내역 추가·수정·삭제
    ├── settings/             설정 탭
    ├── common/               공용 컴포저블·포맷터
    └── theme/                Material 3 테마
```

## 데이터 모델

```
folders(id, categoryKey, name, createdAt)            -- UNIQUE(categoryKey, name)
items(id, folderId→folders.id, name, memo, createdAt) -- UNIQUE(folderId, name)
purchases(id, itemId→items.id, purchasedOn, quantity, cost, memo, createdAt)
```

- `purchasedOn` 은 `LocalDate.toEpochDay()` 값입니다. 시각·시간대를 저장하지 않으므로
  기기 시간대가 바뀌어도 구매 일자가 흔들리지 않습니다.
- `cost` 는 해당 구매 건의 **총 비용(원)** 입니다. 단가는 `cost / quantity` 로 보여줍니다.

## 빌드 / 실행

Android Studio(Ladybug 이상 권장)에서 `supply-manager` 폴더를 열거나:

```bash
cd supply-manager
./gradlew :app:assembleDebug      # APK 빌드
./gradlew :app:installDebug       # 연결된 기기에 설치
./gradlew :app:testDebugUnitTest  # 단위 테스트
```

Android SDK 경로는 `local.properties` 의 `sdk.dir` 또는 `ANDROID_HOME` 으로 지정합니다.

## 대분류를 추가하려면

`MainCategory` 에 항목을 추가하고 `strings.xml` 에 라벨을, `BottomTab.ALL` 에 아이콘을
등록하면 됩니다. 폴더·품목·구매 내역 화면은 그대로 재사용됩니다.

```kotlin
enum class MainCategory(val key: String, @StringRes val labelRes: Int) {
    HOME("home", R.string.category_home),
    PARTY_ROOM("party_room", R.string.category_party_room),
    OFFICE("office", R.string.category_office), // 추가
}
```
