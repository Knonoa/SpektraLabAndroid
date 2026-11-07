# Android 新机型 RAW 实测

测试日期：2026-11-07（设备日志）。最终应用包名 `com.spektralab.android.dev`，在 Apple silicon Mac 上构建，arm64 Android 15 / API 35 模拟器运行，模拟器实际 RAM 约 2.4 GiB。当前未连接 USB 真机。

## 结果与范围

**用户指定的 9 款相机、10 份新机型 RAW 全部通过。** 同一最终版本另复测 5 份旧机型/格式 RAW，总计 15 份；`NativeImportTest` + `AndroidEngineTest` 共 **10 项通过，0 失败、0 跳过，134.28 秒**。原始日志见 [raw-validation-android.txt](raw-validation-android.txt)。主机 `raw_decoder_errors` 的 10 个错误路径检查也通过；构建覆盖三个 ABI，最终 APK 含 LibRaw 许可证，`git diff --check` 通过。

每个文件都经过：完整 RAW 解包与去马赛克 → 640 px 预览级 / 1024 px 输出级线性解码 → 检查有限像素和场景变化 → native float 胶片引擎 → JPEG 写出。没有使用文件内嵌 JPEG 充当计算输入。已逐张检查新机型输出，没有明显花屏、错位或解码异常色块；这不等同于与厂商显影软件的色彩定量对照。

下表的两个时间分别为独立 640/1024 解码（不含胶片渲染）。它们是模拟器单轮观测，不能作为真机性能或优化倍数依据。

| 相机 | 真实样片模式 | 预览 / 输出尺寸 | 解码秒数 640 / 1024 | 结果 |
|---|---|---|---|---|
| Sony A7R V | 无损压缩降尺寸 RAW（`sony_ycbcr_load_raw`） | 640x426 / 1024x682 | 1.047 / 1.007 | 解码、胶片输出通过 |
| Sony A7R VI | 14 位 APS-C HQ 有损 ARW6 | 640x427 / 1024x683 | 7.672 / 7.665 | 解码、胶片输出通过 |
| Sony A7R VI | 14 位全画幅无损压缩 RAW | 640x427 / 1024x683 | 12.122 / 12.131 | 解码、胶片输出通过 |
| Sony A1 | 14 位全画幅有损压缩 RAW | 640x427 / 1024x684 | 8.319 / 8.291 | 解码、胶片输出通过 |
| Sony A1 II | 全画幅无损压缩 Small RAW | 640x427 / 1024x684 | 0.829 / 0.792 | 解码、胶片输出通过 |
| Sony A7 V | 14 位 APS-C HQ 有损 ARW6 | 640x427 / 1024x683 | 3.783 / 3.779 | 解码、胶片输出通过 |
| Sony A7 IV | 14 位 APS-C 未压缩 RAW | 640x427 / 1024x684 | 2.417 / 2.402 | 解码、胶片输出通过 |
| Nikon Z8 | HE 系列高质量压缩（NEFCompression=14） | 640x427 / 1024x683 | 9.867 / 9.833 | 解码、胶片输出通过 |
| Nikon Z9 | HE 系列压缩（NEFCompression=13） | 640x427 / 1024x683 | 9.554 / 9.498 | 解码、胶片输出通过 |
| Nikon Z6 III | HE 系列压缩，4000×2672（NEFCompression=13） | 640x428 / 1024x684 | 2.251 / 2.222 | 解码、胶片输出通过 |
| Canon EOS 7D | CR2 回归 | 640x426 / 1024x682 | 3.370 / 3.380 | 解码、胶片输出通过 |
| Canon EOS R6 | CR3 回归 | 640x427 / 1024x683 | 1.393 / 1.395 | 解码、胶片输出通过 |
| Nikon D1 | NEF 回归 | 640x421 / 1024x674 | 0.445 / 0.446 | 解码、胶片输出通过 |
| Sony DSLR-A300 | ARW 回归 | 640x430 / 1024x688 | 1.849 / 1.849 | 解码、胶片输出通过 |
| Canon PowerShot SD450 / CHDK | 真实相机 DNG 回归 | 640x480 / 1024x767 | 0.876 / 0.875 | 解码、胶片输出通过 |

这些结果只覆盖表内模式。部分 Sony 样片是 APS-C 或 Small RAW；不能据此宣称所有像素尺寸、连拍模式、竖拍和压缩组合均已测试。**4096 px 或原始全分辨率胶片导出尚未验证，armeabi-v7a/x86_64 仅编译验证，真机性能和峰值内存仍需测量。** raw.pixls.us 将某些 Nikon 条目标为“8bit”，该值不用于推断传感器实际位深；本轮按 LibRaw 的实际解码器与 Nikon 压缩标签确认 HE 系列。

## 解码库与精度

旧 LibRaw 0.22.2 无法打开此 A7 V 样片，也不能解包三台 Nikon 的 HE/HE*。正式 0.22.3 仍不含所需支持。本轮选用 [LibRaw 官方 snapshot202611](https://github.com/LibRaw/LibRaw/commit/0b87f5c907d7ee0e7641dd1b6a6c9140b9451922)，固定为：

- 版本：`0.23.0-Snapshot202611`（开发快照，不是稳定版发布）。
- Commit：`0b87f5c907d7ee0e7641dd1b6a6c9140b9451922`。
- Source tree：`fb93233a46ec9a96fb17f2dbab160f5fa27a0ab6`。
- `tools/setup_libraw.sh` 与 CMake 都校验 commit、tree、工作目录 clean；不跟随 `master` 自动升级。
- APK 的三个 ABI 都确认含此版本字符串；`assets/licenses/LibRaw/` 含 COPYRIGHT 与 LGPL 文本。

处理保留 compatible16 策略：相机白平衡、AHD、线性 ProPhoto 16 位输出、无自动增亮。此阶段仍有 LibRaw 的整数去马赛克和 16 位输出量化/高光裁切。随后面积缩图、ProPhoto → 线性 sRGB 和胶片处理是 float32；Bitmap 只用于显示，JPEG 最终写 8 位。这不是全浮点 RAW 解码。

## 大像素内存修复

A7R VI 的 100,851,712 字节全画幅无损样片初次测试时，旧路径同时持有 LibRaw 缓冲、完整 16 位 RGB 和完整 float RGB，模拟器将应用以 `LOW_MEMORY` 终止；该次不能算通过。

最终路径保持完整尺寸去马赛克，在获得独立的、已旋转的 16 位线性 RGB 后立即释放 LibRaw 工作缓冲，再直接面积平均成所需尺寸的 float RGB。没有降低至 8 位或替换成内嵌预览。新增 `boundedRawDecodeMatchesFullPrecisionAreaScaling` 比较完整精度缩图和有界解码，逐像素容差 2e-6；最终 15 份样片全部重跑通过。该修复显著减少同时存活的缓冲，但没有消除完整 RAW 解包和去马赛克本身的内存开销。

官方 Changelog 还指出：Nikon HE/HE* 解包的中间缓冲额外约 8 bytes/像素，45MP 输入加压缩数据约 400MB。低内存设备、大幅导出仍需进一步实际验证。

## 样片来源与校验

样片下载后逐一计算完整文件 SHA-256，并与公开索引匹配。全部为来源标注 CC0 的公开验证样片，完整下载地址、字节数、SHA 与原始模式描述见 [raw-fixtures.json](raw-fixtures.json)。RAW 文件仅存于仓库外 `../../.android-dev/raw-samples/`，不提交大文件。

- [raw.pixls.us 公开索引](https://raw.pixls.us/json/getrepository.php?set=all)：8 份指定新机型 + 5 份旧格式回归。
- [DNG Lab A7R VI 样片库](https://rawdb.dnglab.org/sets/Sony/ILCE-7RM6)：APS-C HQ / 全画幅无损各一份，[JSON 索引](https://rawdb.dnglab.org/api/sets/Sony/ILCE-7RM6)。通过 [LibRaw issue 828](https://github.com/LibRaw/LibRaw/issues/828) 找到。
- 本机最终 JPEG：`../../.android-dev/raw-final-results/`。

## 构建与复现

在 `SpektraDroid/` 下执行；JDK 17、SDK/NDK/CMake 已准备在仓库外。首次获取依赖需要网络，之后可离线构建：

```bash
export JAVA_HOME='/Users/winter/Data/Product/胶片模拟/.android-dev/jdk/zulu17.70.113-ca-jdk17.0.21.0.3-macosx_aarch64/Contents/Home'
export ANDROID_HOME='/Users/winter/Data/Product/胶片模拟/.android-dev/sdk'
export GRADLE_USER_HOME='/Users/winter/Data/Product/胶片模拟/.android-dev/gradle'
export SPEKTRALAB_LIBRAW_SOURCE="$(bash tools/setup_libraw.sh '/Users/winter/Data/Product/胶片模拟/.android-dev/libraw-deps/LibRaw-0.23.0-snapshot')"
./gradlew --offline assembleDebug assembleDebugAndroidTest
```

应用 APK：`app/build/outputs/apk/debug/app-debug.apk`；测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。本机可用 adb 位于 `../../.android-dev/adb35/platform-tools/adb`。

必须安装上述 `.dev` 包和 `.dev.test` 测试包；旧 `.usb` 是另一构建，不能用其测试结果验证本次版本。样片需写入目标应用可读目录，例如先 `adb push` 到 `/data/local/tmp/`，再用 `run-as com.spektralab.android.dev` 管道复制到其内部 `cache/raw-fixtures/`。避免由 shell 创建外部目录导致应用看不到文件。

```bash
adb shell am instrument -w -r \
  -e rawFixtureCount 15 \
  -e rawFixtureDir /data/user/0/com.spektralab.android.dev/cache/raw-fixtures \
  -e class com.spektralab.android.NativeImportTest,com.spektralab.android.AndroidEngineTest \
  com.spektralab.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

`rawFixtureCount` 强制校验文件数，防止空目录假通过；未指定数目且没有样片时标记 skip。每文件打印 BEGIN/PASS/FAIL；单文件失败会继续收集，最终统一失败。正常 JPEG 位于 `/sdcard/Android/data/com.spektralab.android.dev/files/raw-results/`。

最终交付包为仓库外 `../../SpektraLab-raw-verified.apk`，与本轮模拟器测试应用一致。仓库内旧 `SpektraLab-raw-float-usb.apk` 是早期产物，不代表本轮验证版本。

最终 APK SHA-256：`0ac96b2fb8223d2202789b970fa6a548e241123756c33ef9f8029f05365e8a79`。

## 1.2 发布包补充验证

正式包 `com.spektralab.android`（versionCode 3）在启用 R8、资源精简、独立发布签名且不可调试的条件下，于同一模拟器完成相同 15 份真实 RAW 的 640/1024 解码与胶片 JPEG 输出，全部通过。完整 10 项测试零失败、零跳过，耗时 22.798 秒；不能用此单轮模拟器耗时推算真机性能。运行记录见 [releases/1.2-validation.txt](releases/1.2-validation.txt)，安装与签名变更见 [1.2 发布说明](releases/1.2.md)。
