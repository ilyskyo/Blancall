# ════════════════════════════════════════════════════════
# Cloze ProGuard / R8 规则
# ════════════════════════════════════════════════════════

# ── 1. WorkManager Worker 需要无参构造（反射实例化）──
-keep class com.ilyskyo.blancall.notification.ReminderWorker { <init>(...); }

# ── 2. 数据模型类（字段经 org.json 手工读写；保留以防 R8 误裁字段/构造，见报告说明）──
# Article / PracticeRecord / MistakeDetail / PracticeState / PracticeStatus（均为 data.model 顶层类，已核对存在）
-keep class com.ilyskyo.blancall.data.model.Article { *; }
-keep class com.ilyskyo.blancall.data.model.PracticeRecord { *; }
-keep class com.ilyskyo.blancall.data.model.MistakeDetail { *; }
-keep class com.ilyskyo.blancall.data.model.PracticeState { *; }
-keep class com.ilyskyo.blancall.data.model.PracticeStatus { *; }
-keep class com.ilyskyo.blancall.data.model.PracticeStatus$* { *; }

# ── 3. FileProvider（系统组件反射调用）──
-keep class androidx.core.content.FileProvider { *; }

# ── 4. 算法 object（保险保留，避免优化破坏单例状态；ClozeGenerator 已重命名为 BlancallGenerator）──
-keep class com.ilyskyo.blancall.algorithm.BlancallGenerator { *; }
-keep class com.ilyskyo.blancall.algorithm.BlancallGenerator$* { *; }
-keep class com.ilyskyo.blancall.algorithm.SentenceSplitter { *; }

# ── 5. 保留 Kotlin metadata（反射 / 协程内省依赖）──
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
-keep class kotlin.Metadata { *; }

# ── 6. 保留 R8 默认行为之外的枚举（valueOf 依赖）──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── 7. pdfbox 可选 JPX(JP2) 解码依赖缺类，避免 R8 报错 ──
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter

# ── 8. Room / WorkManager：保留 Room 生成数据库类（避免 R8 裁剪致启动崩溃）──
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.** { *; }
