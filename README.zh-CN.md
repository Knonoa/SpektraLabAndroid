# SpektraLab 安卓版

**简体中文** · [English](README.md)

基于 Andrea Volpato 的 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 胶片过程模型，将 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 移植到 Android 的非官方项目。导入照片或直接拍摄，选择胶片、相纸进行冲印模拟，再把结果保存到手机。图像在本机处理，无需账号或上传云端。目前应用界面以中文为主，项目文档提供中英文版本。

**[下载最新 APK](https://github.com/Knonoa/SpektraLabAndroid/releases/latest)** · [1.2 发布说明](SpektraDroid/docs/releases/1.2.md) · [RAW 实测记录](SpektraDroid/docs/RAW_VALIDATION.md)

以下新增功能在 [`dev` 分支](https://github.com/Knonoa/SpektraLabAndroid/tree/dev)维护，**尚未包含在已发布的 1.2 APK**。见 [索尼 USB 连接说明](SpektraDroid/docs/SONY_USB.md)、[新增实测记录](SpektraDroid/docs/USB_FULL_RAW_VALIDATION.md) 和 [页面评估与待修问题](SpektraDroid/docs/UI_AUDIT.md)。

## 功能

- **三种工作模式（开发中）**：工作台导入冲印、手机相机拍摄、USB监视器。应用内素材库支持搜索、来源与类型筛选、照片 / RAW回看及视频播放；命名配方可在三种模式调用。工作台保留自身调色，相机和监视器共享拍摄调色。见 [工作站验证](SpektraDroid/docs/WORKSTATION_VALIDATION.md)。
- **胶片与相纸模拟**：25 款胶片、9 款相纸，曝光、放大机、颗粒、光晕、散射、显影成色剂和扫描锐化。Kotlin / Jetpack Compose 编辑器，C++20 本机处理引擎。
- **胶片边框 Film Edge**：135、半格、XPan、120 等画幅，片门、齿孔、胶片型号、帧号、片边雾化和漏光。边框作为底片曝光的一部分参与显影。
- **日期后背 Date Back**：液晶或点阵日期，以及 EXIF 中可用的快门、光圈、曝光补偿等拍摄数据。胶片型号印在片边。
- **拍摄页面**：CameraX 取景、实时胶片预览、闪光灯和变焦；查看系统公开的逻辑/物理摄像头及其参数，并在设备允许时指定物理镜头。
- **复用手机成像能力**：优先使用厂商公开的 CameraX 扩展；部分设备可走 Camera2 HAL HDR 场景模式。「系统相机」按钮调用手机自带相机，再导入回传照片。可用模式取决于设备及系统相机的回传能力。
- **RAW 与 TIFF 导入**：DNG、索尼 ARW、尼康 NEF、佳能 CR2/CR3、经典 TIFF/TIF，以及普通图像。原生线性浮点图像送入胶片引擎，RAW 计算输入不经过 8 位显示位图。
- **索尼 USB 联机（开发中）**：USB/OTG 的 PC 遥控连接、自动接收照片原文件、480p/720p 上限实时监看、胶片色调预览、可选相机参数叠加的无声 H.264 MP4 录制。参考 libgphoto2 的索尼协议；实体相机兼容性待验证。
- **编辑与导出**：640/1600 像素渐进预览、缩放和分屏对比、设置自动保存、可取消的 JPEG 导出。保存到 `Pictures/SpektraLab`，普通输出源图长边最高 4096 像素；新增完整尺寸开关，在检查可用内存后保留 RAW 解码尺寸，本次禁用胶片片边以保留构图。全尺寸处理需要 64 位设备和较多空闲内存，JPEG 仍为 8 位。普通模式加入片边后的最终画布可能更大。

## 安装

需要 **Android 8.0 / API 26 及以上**。通用 APK 包含 **arm64-v8a、armeabi-v7a、x86_64**。从 [Releases](https://github.com/Knonoa/SpektraLabAndroid/releases) 下载 APK，在系统提示时允许浏览器或文件管理器安装应用。

**从 1.1 升级**：旧公开版本使用调试证书，本工作区没有其私钥。1.2 改用独立发布密钥，安装前请备份需要保留的设置，再卸载 1.1；卸载会清除应用设置和缓存。后续发布必须沿用 1.2 密钥，才能覆盖升级。已有 `.dev` / `.usb` 开发版是独立应用。

1.2 正式包名为 `com.spektralab.android`，版本号 `1.2`、版本代码 `3`，启用代码压缩和资源精简。进入拍摄页时请求相机权限；Android 8–9 保存导出照片还需要存储权限。

## 使用

1. 选择照片，或从相册分享/打开到本应用。部分文件提供器把 RAW 归类为通用二进制文件，选择器也允许这种类型。
2. 选择胶片、相纸，调整曝光、放大机和效果。长按查看原图，也可使用分屏对比。
3. 在「片边」页开启胶片边框和日期打印。启用边框后，照片会居中裁成所选片门比例。
4. 进入「拍摄」页拍照，可开启胶片预览；需要手机自带相机的模式时使用「系统相机」。开发版拍摄后留在相机素材栏，可直接回看或进入工作台。
5. 联机索尼时打开「连接 USB 相机」，将相机设为 USB PC 遥控，允许手机访问。见 [连接说明](SpektraDroid/docs/SONY_USB.md)。
6. 导出 JPEG 到系统相册。开启「允许全尺寸处理」后选择「完整尺寸」，不缩图、不用片边裁切；无法恢复相机拍摄时就已丢弃的像素。

## RAW 实测与限制

已从公开样片库下载真实 RAW，逐文件核对 SHA-256，在 **arm64 Android 15 / API 35 模拟器**中完成 **640 和 1024 像素两级解码、浮点胶片处理及 JPEG 输出**：

| 相机 | 实测样片模式 |
|---|---|
| 索尼 A7R V | 降尺寸无损压缩 RAW |
| 索尼 A7R VI | APS-C HQ ARW6、全画幅无损 RAW |
| 索尼 A1 | 全画幅压缩 RAW |
| 索尼 A1 II | 全画幅无损 Small RAW |
| 索尼 A7 V | APS-C HQ ARW6 |
| 索尼 A7 IV | APS-C 未压缩 RAW |
| 尼康 Z8 | HE 系列高质量压缩 |
| 尼康 Z9 / Z6 III | HE 系列压缩 |

这 9 款相机的 10 份样片全部通过，另有 5 份 ARW/NEF/CR2/CR3/DNG 回归样片通过。调试版测试共 **10 项，零失败、零跳过**。模式、尺寸、耗时与校验值见 [RAW_VALIDATION.md](SpektraDroid/docs/RAW_VALIDATION.md) 和 [raw-fixtures.json](SpektraDroid/docs/raw-fixtures.json)；发布包验证见 [1.2 发布说明](SpektraDroid/docs/releases/1.2.md)。

- 这些样片不代表所有 RAW 压缩、裁切、连拍和方向组合都已覆盖。另外九款索尼 24–61MP 完整尺寸样片已通过高内存模拟器的完整解码、默认胶片处理和 JPEG 尺寸验证，见 [新增结果](SpektraDroid/docs/USB_FULL_RAW_VALIDATION.md)。其他模式和手机性能仍需验证。
- 为支持新索尼格式和尼康 HE/HE*，固定使用 LibRaw 官方开发快照 `0.23.0-Snapshot202611`，并非 LibRaw 稳定发布版。
- RAW 先用 LibRaw 输出 **16 位线性 compatible 图像**，之后缩图、颜色转换、胶片处理采用 float32；去马赛克仍有整数计算，该策略本身也有高光裁切。显示与 JPEG 导出为 8 位 sRGB，尚无 16 位 TIFF 导出。
- 大 RAW 仍需较多内存。本轮 A7R VI 全画幅样片曾触发内存不足，已通过提早释放解码缓冲、再生成所需尺寸浮点图修复；低内存设备仍需单独验证。
- 摄像头和拍摄模式取决于厂商向第三方开放的能力。厂商扩展会话中胶片预览不可用，HAL HDR 路径仍可使用；HDR、夜景及预览与实拍一致性因手机而异。应用没有嵌入谷歌相机，也没有手动多图 HDR 合成功能。
- 半格双联、交互裁切尚未实现。安卓端使用原生 CPU 引擎，不能套用桌面 GPU 的性能数据。

## 构建

在 Android Studio 中打开 **`SpektraDroid/`**，或使用 Gradle wrapper。依赖：JDK 17、Android SDK 35、Build Tools 34.0.0、NDK `27.2.12479018`、CMake `3.22.1`、Git。工程固定 Gradle 8.7 和 AGP 8.5.2。设置 `ANDROID_HOME`，或在本地 `local.properties` 写入 `sdk.dir=…`。

```bash
git clone https://github.com/Knonoa/SpektraLabAndroid.git
cd SpektraLabAndroid/SpektraDroid
bash tools/setup_libraw.sh
./gradlew assembleDebug
```

依赖脚本获取并校验 LibRaw 固定提交 `0b87f5c907d7ee0e7641dd1b6a6c9140b9451922`。也可以用 `SPEKTRALAB_LIBRAW_SOURCE` 指向同一干净提交。引擎数据和字体已随源码提供，普通编译及手机运行均不需要 Python。

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.spektralab.android.dev`。macOS 可运行 `tools/build_install_mac.command` 编译并通过 adb 安装。正式签名配置见 [RELEASING.md](SpektraDroid/docs/RELEASING.md)。

```bash
./gradlew connectedDebugAndroidTest
```

仓库包含合成 TIFF/DNG 测试。没有外部真实 RAW 时，相机样片测试会跳过；要强制验证样片数量，请按[验证指南](SpektraDroid/docs/RAW_VALIDATION.md)传入 `rawFixtureDir` 和 `rawFixtureCount`，不能把跳过结果当作相机兼容性验证。

## 目录

| 路径 | 内容 |
|---|---|
| `SpektraDroid/app/src/main/java/` | 安卓编辑器、相机、状态、图像导入导出 |
| `SpektraDroid/app/src/main/cpp/` | 安卓引擎、JNI、片边及线性图像适配 |
| `SpektraDroid/app/src/main/assets/` | 引擎数据、字体和许可文本 |
| `SpektraDroid/app/src/androidTest/` | JNI、精度、RAW/TIFF、相机枚举测试 |
| `SpektraDroid/tools/` | 依赖准备、数据生成、参考对比工具 |
| `engine/` | 上游共享引擎资源与原生 RAW/TIFF 读取器 |
| `modern_UI/`、`windows_UI/`、`desktop/` | 保留的上游平台代码，不是安卓应用入口 |

本仓库安卓开发使用 `main` 分支。贡献请拆成便于审查的提交，保留署名和许可证，并写明实际运行的检查。片边生成方式见 [HANDOFF.md](SpektraDroid/docs/HANDOFF.md)：修改拼装源文件，再生成 `overscan.cpp`。

## 致谢、许可与名称

- [spektrafilm](https://github.com/andreavolpato/spektrafilm)，Andrea Volpato：胶片过程模型、实测彩色胶片数据和参考实现；新增黑白胶片数据：Hanze Qiu。
- [SpektraLab](https://github.com/JamesQiu2005/SpektraLab)：原生参考引擎、Film Edge 与 Date Back；[Knonoa/SpektraLabAndroid](https://github.com/Knonoa/SpektraLabAndroid)：安卓移植。
- 应用与引擎：**GPL-3.0-or-later**，见 [LICENSE.txt](LICENSE.txt)；胶片、相纸和派生 LUT 数据：**CC BY-SA 4.0**，另见[上游数据声明](license/SPEKTRAFILM_LICENSE.txt)。
- [libgphoto2](https://github.com/gphoto/libgphoto2)：索尼协议参考，源码文件头许可为 LGPL-2.0-or-later；见 [参考与署名](SpektraDroid/docs/SONY_USB.md)。
- [LibRaw](https://github.com/LibRaw/LibRaw)：LGPL-2.1 或 CDDL，本发行选择 LGPL-2.1；TeX Gyre Heros：GUST Font License；DejaVu：附带字体许可；stb：公有领域 / MIT；AndroidX：Apache-2.0。相关声明和许可文本保存在 APK assets 与源码中。

本项目为**非官方安卓移植**，不代表上游作者背书。SpektraLab 名称与图标另受上游商标条款约束，源码和数据许可不授予商标权；再次发行衍生应用前，请查阅[上游商标说明](https://github.com/JamesQiu2005/SpektraLab#trademark)。
