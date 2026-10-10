# Injected - APK 弹窗注入工具

将自定义弹窗注入到任意 APK 的启动流程中，宿主启动时自动弹出弹窗。全部处理在本机完成：解析 Manifest → 定位启动类 → smali 插桩 → 重打包 → 重签名。

## 功能特性

### v2 增强
- **多弹窗包注入**：一次选择多个弹窗包同时注入（SAF 多选，选择时自动缓存本地副本）
- **触发策略可选**：
  - 随机触发：注入随机调度器，宿主每次启动等概率弹出一个弹窗
  - 依次触发：全部弹窗按顺序插入启动流程，宿主启动时全部弹出
- **内置 8 种弹窗**：基础弹窗、多按钮弹窗、进度条弹窗、更新提示弹窗、倒计时公告、二次元公告（渐变背景）、列表弹窗、输入框弹窗，一键生成弹窗包无需外部文件
- **即时预览**：选择弹窗包后立即在注入器内试弹，预览效果所见即所得
- **双色主题**：浅色樱花粉紫 / 深色霓虹夜紫，跟随系统深色模式
- **assets 支持**：弹窗包可携带图片等资源，注入时自动合并进宿主 APK

### 核心能力
- 自动解析 APK 的 AndroidManifest（二进制 AXML），列出启动器 Activity 供选择
- baksmali 反编译目标 dex → 插桩 → smali 汇编，全流程使用 dexlib2
- 追加 dex 而非覆盖：多 dex 命名自动避开宿主现有编号
- 弹窗代码独立 try-catch，单个弹窗异常不影响宿主启动
- 重签名支持：内置 debug 签名，或导入自定义 keystore（JKS/BKS）

## 使用方法

1. **选择宿主 APK**：注入目标（如 `com.heci.toolbox`）
2. **选择弹窗包**：从文件选择（支持多选）或从内置弹窗库勾选，可即时预览
3. **选择触发策略**：随机触发 / 依次触发
4. **出击**：等待注入完成，产物输出到应用私有目录，安装即可

弹窗包为 zip 格式，包含：

```
classes.dex      # 弹窗代码（编译后的 dex）
xymods.txt       # smali 调用声明，如：
                 # invoke-static {p0}, Lcom/xypopup/Popup;->show(Landroid/content/Context;)V
assets/          # 可选，图片等资源
```

## 构建

需要 JDK 17 与 Android SDK（compileSdk 34）：

```
./gradlew assembleDebug
```

国内网络可将 `gradle/wrapper/gradle-wrapper.properties` 中的 distributionUrl 替换为腾讯镜像：

```
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip
```

Maven 依赖已配置阿里云镜像（settings.gradle）。

## 免责声明

本项目仅供学习研究 Android 逆向与 smali 插桩技术。注入他人应用前请确认拥有相应权限，使用者需自行承担使用本工具产生的一切后果，请勿用于任何违法违规用途。
