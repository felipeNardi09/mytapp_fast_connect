# mytapp_fast_connect-android consumer ProGuard/R8 rules.
#
# The public API is plain Kotlin with no reflection, so no keep rules are strictly required.
# These keep the public surface readable in stack traces from release builds.
-keep public class com.mytapp.fastconnect.android.** { public *; }
-keep public class com.mytapp.fastconnect.core.** { public *; }
