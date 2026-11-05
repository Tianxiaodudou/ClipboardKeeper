# LSPosed 按 META-INF/xposed/java_init.list 里的全限定名反射实例化 HookEntry，
# 类名与方法名都不能被 R8 改写
-keep class com.tianxiaodudou.clipboardkeeper.hook.** { *; }
-keepclassmembers class com.tianxiaodudou.clipboardkeeper.hook.** { *; }

# libxposed API 只在编译期存在（compileOnly），运行时由 LSPosed 提供
-dontwarn io.github.libxposed.**

# libsu 内部通过反射加载 Shell 实现类
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# 数据库/记录模型无需保留签名，但保留字段名便于调试
-keepclassmembers class com.tianxiaodudou.clipboardkeeper.ui.ClipRecord { <fields>; }
