# Blancall Pro — 工程规则

本工程是 **Blancall** 的含AI 完整版。

共享规则见 reference **`blancall-shared`**（`D:\Blancall\_shared\AGENTS-shared.md`），
涵盖技术栈版本、构建命令、ABI 约束、签名机制、资源约定。
**先读那份**，本文件只写本工程的差异和不在共享文件里的知识。

## 本工程身份

| 项 | 值 |
|---|---|
| applicationId | `com.ilyskyo.blancall` |
| versionName | `7.4.0` |
| versionCode | 48 |
| local.properties 的 sdk.dir | Android SDK（`AppData\Local\Android\Sdk`） |

与 `Blancall-Std` 的唯一 gradle 差异就是上面三项的applicationId / versionName。
代码本身有差异，但 `build.gradle.kts`、`libs.versions.toml`、native 层结构一致。

## 本工程独有

- 含 `.qoder/` 和 `.trae/` 两个工具的目录（Pro 工程独有，Std 没有）
  - 提交前确认里面的内容是否该入库 —— 规则类文件通常该入库，缓存类不该
- `apk-output/` 存放历史发布包（2.8 → 7.x），已被 `.gitignore` 忽略
- `philosophical_video.mp4`、`_gaokao.txt` 等根目录杂项文件，注意不要误提交
- Gradle JVM 堆配置两版相同：`org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8`
  （Std 的 `gradle.properties:13` 也有这一行，此前误记为「Std 没有」，已据实修正）

## 打发布包的完整流程

```sh
# 1. 确认签名信息在位（local.properties 四项齐全）
#    Windows: type local.properties

# 2. 构建
gradlew.bat assembleRelease

# 3. 验证签名 —— 这一步不能省
keytool -printcert -jarfile app\build\outputs\apk\release\app-release.apk

# 4. 产物
#    Pro → app\build\outputs\apk\release\
```

第3 步要确认证书指纹是正式签名的那张。如果看到 `CN=Android Debug`，
说明 `local.properties` 被重新生成、release 配置丢失了，
构建脚本会静默回退到 debug 签名 —— 这时**不要发布**，先修local.properties。

## 相关位置

```
正式签名密钥   D:\Blancall\keystore\blancall-release.jks
签名备份       D:\Blancall-keystore-backup\blancall-release.jks
共享规则       D:\Blancall\_shared\AGENTS-shared.md
标准版工程     D:\Blancall\Blancall-Std\
发布产物归档   D:\Blancall\_archive\
工具与脚本     D:\Blancall\_tools\