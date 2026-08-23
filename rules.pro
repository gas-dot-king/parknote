# R8 축소 규칙
# 매니페스트에서 이름으로 인스턴스화되는 컴포넌트는 반드시 유지한다.
# 하나라도 빠지면 릴리스 빌드에서만 ClassNotFoundException으로 죽는다.
-keep class com.ohdduck.parknote.MainActivity { *; }
-keep class com.ohdduck.parknote.OnboardingActivity { *; }
-keep class com.ohdduck.parknote.ZoneSettingsActivity { *; }
-keep class com.ohdduck.parknote.ParkWidgetProvider { *; }
-keep class com.ohdduck.parknote.BtReceiver { *; }
-keep class com.ohdduck.parknote.ReminderReceiver { *; }
-keep class com.ohdduck.parknote.ParkingTimerReceiver { *; }
-keep class com.ohdduck.parknote.ParkTileService { *; }

# 개인용 앱 — 난독화는 이득이 없고 스택트레이스만 읽기 어려워짐
-dontobfuscate
