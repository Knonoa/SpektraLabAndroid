# SpektraDroid 交接文档：胶片边框、日期后背、拍摄页

日期：2026-11-07 · 分支：`main`（1.2 发布整理，见 §9）· 拍摄测试：vivo V2454DA（Android 17 / API 37，arm64）；本轮 RAW 测试：arm64 Android 15 / API 35 模拟器

本文写给下一个接手 SpektraDroid（SpektraLab 的 Android 移植）的人。界面细节见同目录的 [UI_DESIGN.md](UI_DESIGN.md)。

---

## 1.1 本轮追加：高精度 DNG / TIFF / RAW 导入

导入管线已从“系统 Bitmap 解码”升级为 native float 图像句柄：

- `engine/host/image_io.cpp` 的 LibRaw / TIFF reader 被直接复用；支持 DNG、Sony ARW、Nikon NEF、Canon CR2/CR3，以及经典 TIFF/TIF。
- 安卓 RAW 使用固定提交的 LibRaw `0.23.0-Snapshot202611`，保留 AHD、相机白平衡和 16 位线性输出；16 位/32 位浮点 TIFF 保留为线性 ProPhoto，再转换为线性 sRGB 浮点送入胶片引擎。
- 预览 Bitmap 仅是显示副本；胶片测光、曝光、显影和导出都使用 native float。屏幕显示与 JPEG 导出仍按 8 位 sRGB 写出。
- SAF `OpenDocument` 允许 `image/*` 与 `application/octet-stream`，并把 `content://` 资源缓存为可随机读取的临时文件。文件扩展名和 MIME 都不可靠时，LibRaw 仍会用容器识别。
- 已用公开 `raw.pixls.us` 样片在 Android 模拟器上做过实际解码：Sony DSLR-A300 ARW（640×430 / 1024×688）、Nikon D1 NEF（640×421 / 1024×674）、Canon EOS 7D CR2（640×426 / 1024×682）、Canon EOS R6 CR3（640×427 / 1024×683）和 Canon PowerShot SD450 CHDK DNG（640×480 / 1024×767）均成功。样片只保存在本机 `.android-dev/raw-samples/`，不会进入仓库。
- 样片 SHA-256：ARW `daaa25127006b1178c298bce14de41c80ef0fd95101f0b3513e5126dfb8cced2`；NEF `7886d8b0e1257897faa7404b98fe1086ee2d95606531b6285aed83a0939b768f`；CR2 `b5e47c5fcf7332ac03e0134926f17a338a42e68c1fd7f83e16f45f4b767544e8`；CR3 `74abb0a113d075ad9887a058082f40dd2a938c4813a08474d82356f11a027778`；DNG `32357c5a870bcd26f6e975bd32905397f47121d0e39fc53d719b26e7adee8a22`。
- **2026-11-07 新机型验证**：用户指定的 Sony A7R V、A7R VI、A1、A1 II、A7 V、A7 IV，以及 Nikon Z8、Z9、Z6 III，均已下载真实公开 RAW 并实际解码、送入浮点胶片引擎、写出 JPEG。A7R VI 覆盖 APS-C HQ ARW6 与全画幅无损；Nikon 覆盖 HE/HE*。具体模式、尺寸、来源、完整文件 SHA-256 和最终测试结果见 [RAW_VALIDATION.md](RAW_VALIDATION.md) 与 [raw-fixtures.json](raw-fixtures.json)。不能外推为每台相机的所有模式已验证。
- LibRaw 0.22.2 的初测曾无法打开 A7 V，也无法解包 Nikon HE/HE*；已通过固定官方 snapshot 解决。此前按未完成下载计算的部分 SHA 不准确，本文已移除，只保留完整下载并与公开索引匹配的校验值。
- 大像素 RAW 的内存修复：完整解包、去马赛克与 16 位线性输出之后，先释放 LibRaw 的传感器/工作缓冲，再直接做面积平均缩图到 float。不会把内嵌 JPEG 或 8 位 Bitmap 当作胶片输入，也不先生成完整尺寸 float 图。A7R VI 全画幅样片原先触发模拟器 LOW_MEMORY，修复后 640/1024 两级及胶片输出通过。
- 精度边界：RAW 去马赛克与相机到 ProPhoto 转换使用 LibRaw compatible16，存在该策略本身的 16 位量化和高光裁切；之后缩图、颜色转换和胶片处理采用 float32。这不等同于全浮点 RAW 解码或无限保留高光。显示与 JPEG 写出为 8 位。
- `SpektraNative.LinearImage` 由显式 `close()` 管理，`Photo` 换图和 ViewModel 销毁时会在渲染线程安全释放。

测试：最终安装包的 Android 模拟器测试结果与复现步骤统一记录在 [RAW_VALIDATION.md](RAW_VALIDATION.md)。覆盖 TIFF 精度/常见压缩、浮点高光、合成 DNG、损坏 RAW 恢复、面积缩图精度、胶片边框，以及 15 份公开真实 RAW。

## 1. 需求与完成情况

| # | 需求 | 状态 | 做法 | 验证情况 |
|---|---|---|---|---|
| 1 | 胶片边框（桌面版有，移植时漏了） | ✅ 完成 | **移植桌面引擎的 Film Edge（RFC-032）**：片门形状、齿孔、各胶片实测的片边字样/DX 码/帧号、片边雾化、漏光，作为**底片上的曝光**参与显影 | 主机端 8 种配置渲染检查（§6）；ASan/UBSan 无报错；真机上开启边框可正常渲染（修复闪退后 0 崩溃） |
| 2 | 胶片水印（型号、曝光、光圈、快门、时间，像日期打印） | ✅ 完成 | **移植桌面引擎的 Date Back（RFC-031）**：液晶七段码 / 点阵 / 数据三种字面，从片背曝光到底片红感层；EXIF 解析照搬桌面版 `ShootingData` | 同上。胶片型号印在片边上（这就是真胶片上型号的位置），日期和拍摄数据由日期后背打印 |
| 3 | 实时拍摄预览 + 识别多个摄像头参数 | ✅ 完成 | CameraX 1.4.2 + Camera2 interop：枚举逻辑/物理摄像头、等效焦距、光圈、传感器尺寸、像素、变焦范围、硬件级别；镜头按钮；可选实时胶片预览 | 真机：取景、0.7×/1× 镜头切换、参数弹窗、拍照进编辑器 |
| 4a | JSR 高像素合成 | ❌ 按你的要求不做 | — | — |
| 4b | 多帧 HDR | ✅ 用手机自带能力 | ① CameraX 厂商扩展（HDR/夜景/自动）；② 没有扩展时用 Camera2 HAL 的 `CONTROL_SCENE_MODE_HDR`；③「系统相机」按钮交给手机自带相机拍 | 这台 vivo 只开放**夜景**扩展，HDR 走 ②（HAL 声明支持 scene mode 18）。**HDR 实际效果没有做客观对比**，需要逆光场景人工确认 |

上一轮的方案（Kotlin Canvas 画边框、叠加文字水印、未编译的相机代码）**全部删除**：它们不是桌面版的移植，也从未编译通过。

---

## 2. 架构：边框和日期为什么放在 C++ 引擎里

桌面版的设计原则（`engine/src/pipeline/overscan.cpp` 文件头）：真实的片边是**同一条胶片**——片门的阴影、片边上的颗粒、溢出画框的光晕、片边字样和雾化，和画面一样被曝光、显影。所以它们作为曝光画在**原始曝光平面**上（测光之后、光晕之前），后面的显影、印放、扫描全部照常作用。画上去的边框模拟不出来，所以安卓端也这样做。

```
EditState (Kotlin)                       ── filmEdge / dateBack 设置，持久化在 SharedPreferences
  └ toNative(seed, Frame)                ── Frame = 照片的 EXIF(ShootingData) + 帧种子 + 片边字样(EdgeText)
      → NativeParams (@JvmField, 按名字被 JNI 读取)
        → jni_bridge.cpp params_from()  ── RenderParams.overscan / .date
          → Engine::render()
             1. 开边框时把画面居中裁成片门比例（overscan_gate_crop）
             2. 像素尺寸 = 片门长边 / 画面长边（桌面版 Pipeline::set_source_long_edge）
             3. overscan_layout()        ── 所有随机量：机身种子 / 帧种子（桌面版三层随机）
             4. expose(): 测光 → rgb→raw → overscan_canvas()（片门+片边+雾化+漏光）
                          → overscan_imprints()（片边字样 + 日期后背）→ 光晕 → log
             5. develop()（颗粒、DIR 成色剂都作用在片边上）
             6. overscan_film_present()  ── 齿孔/片外：密度设为抵消片基的 cmy
             7. print_stage → scan_stage → 锐化之后 overscan_light()（齿孔透光与孔壁）
```

**输出尺寸会变**：开边框后输出是胶片画布（比输入大，如 1600×1200 → 1672×1545）。所以：
- C++：`render_output_size()`；Kotlin：`SpektraNative.outputSize(params, w, h)`；
- 所有分配输出位图的地方（`EditorViewModel.renderTier`、`export`）都必须先问尺寸；
- `PhotoViewer` 按**结果图**的宽高比布局，对比时原图在画布区域内等比居中。

**底片缓存**：`film_side_key()` 已加入全部边框/日期参数，所以只改相纸、放大机这类印放端参数时仍复用缓存的底片（主机测试：缓存渲染与冷渲染逐字节一致）。

---

## 3. 文件清单

### 新增

| 文件 | 内容 |
|---|---|
| `app/src/main/cpp/overscan.cpp`（2.5k 行） | 移植桌面版 `overscan.cpp` + `overscan.metal`。表格、字形、各胶片版式**原样摘取**：由 `tools/port_overscan/assemble.py` 按行号从桌面源码拼装，再加上该目录下的安卓片段（`*.part`）。**不要直接改 overscan.cpp**：改片段后重新拼装，`--check` 可校验两者一致。新写部分都标了 `Android:` |
| `app/src/main/cpp/overscan.hpp` | 参数结构（与桌面 `params.hpp` 的 `OverscanParams`/`DateImprintParams` 同名同义）、布局结构、对外函数 |
| `app/src/main/cpp/edge_sensitivity.inc` | 生成文件：25 款胶片的光谱感光度（engine.bin 里没有，片边打印光/雾化/日期灯的分层权重要用） |
| `app/src/main/cpp/third_party/stb_truetype.h` | 字体轮廓（取自仓库 `engine/third_party/stb`，公有领域） |
| `app/src/main/assets/fonts/` | TeX Gyre Heros 常规/粗体/窄体（GUST 字体许可）、DejaVu Sans Mono Bold（Bitstream Vera 许可），许可证同目录 |
| `java/.../FilmEdge.kt` | `FilmEdgeSettings` / `DateBackSettings` / `ShootingData`（EXIF）/ `EdgeText`（各胶片片边字样表，照搬 `Session+FilmEdge.swift`） |
| `java/.../camera/CameraInventory.kt` | Camera2 枚举：逻辑/物理摄像头、等效焦距、镜头档位、HDR 场景模式支持 |
| `java/.../camera/CameraViewModel.kt` | CameraX 绑定、拍摄模式（扩展 / HAL HDR）、闪光灯、变焦、拍照写 MediaStore、补写 35mm 等效焦距、实时胶片预览 |
| `java/.../ui/CameraScreen.kt` | 拍摄页 UI |
| `tools/bake_edge_sensitivity.py` | 生成 `edge_sensitivity.inc`（**胶片 profile 变了要重跑**） |
| `tools/parity/host_film_edge.cpp` | 主机端渲染样张 + 缓存一致性检查 |
| `tools/build_install_mac.command` | 在 Mac 上一键编译并通过 USB 安装（**未在 Mac 上实际运行过**） |
| `docs/` | 本文、UI 设计文档、配图 |

### 修改

| 文件 | 改动 |
|---|---|
| `cpp/spektra_engine.{hpp,cpp}` | `RenderParams` 加 `overscan`/`date`；`render()` 裁片门、布局、画布尺寸；`expose()` 接画布和字样；`scan_stage()` 接齿孔透光；缓存键；`render_output_size()`；`Engine` 构造函数接字体 |
| `cpp/jni_bridge.cpp` | 读取新参数；从 assets 加载字体；`nativeOutputSize`；**输出尺寸按画布校验与拷贝**（见 §7.1） |
| `cpp/CMakeLists.txt` | 加入 `overscan.cpp` |
| `engine/SpektraNative.kt` | `NativeParams` 新字段（带默认值）、`outputSize()` |
| `EditState.kt` | `filmEdge` / `dateBack` 字段、`dateCamera`、`Frame`、`toNative(seed, frame)`、持久化 |
| `EditorViewModel.kt` | `Photo.shooting`/`frameSeed`；首次运行抽取机身种子；渲染/导出按输出尺寸分配；`newFilmEdgeBody()`；实时预览参数；「恢复默认」保留边框和日期设置 |
| `ui/ControlPanel.kt` | 新「片边」标签页；「拍摄」页在边框开启时提示画幅由片边决定 |
| `ui/EditorScreen.kt` / `MainActivity.kt` | 顶栏和空状态加「拍摄」入口；编辑器 ↔ 拍摄页切换 |
| `ui/PhotoViewer.kt` | 结果图和原图宽高比不同时的布局 |
| `AndroidManifest.xml` | `CAMERA` 权限、`camera.any`（required=false）、应用名占位符 |
| `app/build.gradle.kts` | CameraX 依赖；debug 包名后缀 `.dev` |

---

## 4. 与桌面版的差异（移植时有意做的取舍）

| 项 | 桌面版 | 安卓版 | 影响 |
|---|---|---|---|
| 文字栅格化 | CoreText / CoreGraphics | stb_truetype 取轮廓 + 自写精确面积覆盖率光栅化 | 字形边缘质量相当 |
| 字体 | Helvetica Neue / Menlo Bold / Arial Narrow | TeX Gyre Heros / DejaVu Sans Mono Bold / TeX Gyre Heros Cn，**按大写字母高度对齐** | 字宽略有差别；片边版式按 Helvetica 实测，个别长字样的截断位置可能差一个字 |
| 内核 | Metal | CPU 线程池（`parallel_for`） | 135 画布在主机上 0.8–1.0 s/1600px（含整个流水线）；手机上未计时 |
| 光源权重 `light_weights` | 用光谱库里的黑体照明体 | 普朗克公式现算，归一化后比值相同 | 数值等价（只差采样） |
| 半格双联（pair） | 支持 | **未移植**（App 没有双联合成器） | `135_half` 单帧可用 |
| 裁切 | 用户在片门里构图 | **居中裁成片门比例**（App 还没有裁切工具） | 横竖构图自动判断 |
| 日期 + 数据，无边框时 | 数据行不印（无片边可印） | **数据行印在画面内日期上方**（`data_in_frame`，类似 Nikon MF-28） | 新增行为 |
| 片外区域 `carrier` | none/black/open | 固定 none（桌面 1.3.1 起的默认值） | — |

---

## 5. 构建

### 5.1 在你的 Mac 上（推荐以后用这个）
双击 `SpektraDroid/tools/build_install_mac.command`，或在 Android Studio 中打开 `SpektraDroid/` 运行。
- 需要 JDK 17（会自动找 Android Studio 自带的 jbr）和 `~/Library/Android/sdk`；
- NDK `27.2.12479018`、CMake `3.22.1` 由 AGP 自动下载；
- RAW 依赖先由 `bash tools/setup_libraw.sh` 获取固定 snapshot（需要联网一次）；脚本检查 commit、完整 tree 和源目录无改动，再把路径传给 CMake。也可设置 `SPEKTRALAB_LIBRAW_SOURCE` 指向同一份干净源码。
- 本轮已在 Apple silicon Mac 编译三个 ABI 并安装模拟器。当前机器 JDK/SDK/Gradle 缓存都在仓库外 `../../.android-dev/`；精确路径和命令见 RAW 验证文档。一键安装脚本本身仍未整段运行。

### 5.2 这次在 Linux arm64 VM 里是怎么编的（记录，以后不需要）
Google 不提供 Linux arm64 的 NDK 和 build-tools，所以拼了一套：JDK 17（Adoptium）+ 官方 SDK/NDK（只用其 sysroot、头文件和运行库）+ apt.llvm.org 的 clang-18（包装成 NDK 的 x86 clang，补上 `-rtlib=compiler-rt -unwindlib=libunwind -stdlib=libc++ -fuse-ld=lld`）+ CMake 3.22.1 arm64 + [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) 的静态 aapt2（`android.aapt2FromMavenOverride`）。`llvm-strip` 必须用名字是 `llvm-strip` 的软链（多调用二进制看 argv[0]）。全量编译约 15 s，三个 ABI 都能出。

### 5.3 签名与包名
- debug 构建包名 `com.spektralab.android.dev`，应用名「SpektraLab Dev」，和 release 并排安装；
- 原因：手机上的 1.1 版用你 Mac 的 debug 密钥签名，换一台机器编的 APK 无法覆盖安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）；
- release 正式包名 `com.spektralab.android`。1.2 起改用独立发布密钥，启用 R8 / 资源精简；旧 1.1 调试签名无法覆盖升级，需备份并卸载。构建配置见 [RELEASING.md](RELEASING.md)，验证结果见 [1.2 发布说明](releases/1.2.md)。

---

## 6. 测试

### 6.1 主机端（Linux/macOS 都能跑，不需要手机）
```bash
C=SpektraDroid/app/src/main/cpp
clang++ -std=c++20 -O2 -fno-fast-math -pthread -I$C -Iengine/third_party/stb \
  $C/spektra_engine.cpp $C/filters.cpp $C/cam16.cpp $C/overscan.cpp \
  SpektraDroid/tools/parity/host_film_edge.cpp -o host_film_edge
./host_film_edge SpektraDroid/app/src/main/assets/engine/engine.bin \
  SpektraDroid/app/src/main/assets/fonts photo.jpg out/sample 1600
```
输出 8 张样张（Kodak 135 / Gold 帧间日期 / Provia 黑底点阵 / X-Tra 半格 / CineStill / Pro 400H 6×7 / Portra 6×6 / 仅日期）和缓存一致性检查。加 `-O1 -g -fsanitize=address,undefined` 跑过一遍：无报告。原有 `host_engine_test.cpp`（含 ASan）全部通过。

样张见 `docs/img/edge_*.jpg`。

### 6.2 真机
- USB：`adb install -r -t app-debug.apk`。**vivo 每次 adb 安装都要在手机上点「继续安装」**（可能要输入 vivo 账号密码），约 15 秒不点会返回 `INSTALL_FAILED_ABORTED: User rejected permissions`；
- 查崩溃：`adb logcat -b crash`；查日志：`adb logcat -s SpektraNative SpektraCamera CameraInventory`；
- 符号化：未 strip 的 `.so` 在 `app/build/intermediates/merged_native_libs/debug/.../libspektra.so`，`llvm-addr2line -f -C -i -e libspektra.so 0x<pc>`。

### 6.3 已验证 / 未验证

| 已验证 | 未验证（需要人工） |
|---|---|
| 三个 ABI 编译、APK 打包、字体进 assets；1.2 release / R8 / 独立签名 | 各 ABI 真机性能 |
| 主机端 8 种边框/日期组合的版式与颜色 | 手机上各胶片片边字样的逐张目检 |
| ASan/UBSan、底片缓存逐字节一致 | 手机上渲染/导出耗时、4096 px 导出的内存 |
| 真机启动、拍摄页取景、镜头档、参数弹窗 | HDR 场景模式的实际效果（逆光对比） |
| 真机开启边框不再闪退（修复后 0 崩溃） | 夜景扩展拍摄、系统相机回传、前置摄像头 |
| | armeabi-v7a / x86_64 真机 |

---

## 7. 踩过的坑（务必读）

### 7.1 开边框必闪退（已修复）
JNI 里判断输出位图行距是否紧凑时用的是**输入**宽度，中转缓冲区也按输入尺寸分配；开边框后输出是更大的画布，引擎写越界（`spektra_engine.cpp:1027`，SIGSEGV）。现在全部按 `render_output_size()` 的 `ow × oh`。**以后任何改变输出尺寸的功能都要检查 JNI 的这段拷贝。**

### 7.2 崩溃线程名会骗人
`ThreadPool` 的工作线程由**第一个**调用 `parallel_for` 的线程创建，并继承它的名字。先进拍摄页再开照片时，编辑器渲染的崩溃会显示在名为 `spektra-live` 的线程上。看调用栈，别看线程名。

### 7.3 空绘制组的未定义行为（已修复）
某一侧片边什么都不印时，包围盒保持 ±1e30，转 int 是 UB（桌面原版也有）。arm64 上会饱和、函数随后返回，所以手机上没有表现出来；UBSan 抓到后已修。

### 7.4 vivo 的相机能力
- CameraX 扩展：只开放 **NIGHT**，没有 HDR / AUTO / BOKEH；
- HAL：所有摄像头都声明 `availableSceneModes` 含 18（HDR）、硬件级别 LEVEL_3；
- 后置是逻辑多摄（物理 id `[4 3]`、`[4 3 5]`），第三方应用看到的镜头档位是 0.7× 和 1×；长焦能否通过变焦比切到，需要实拍确认。

### 7.5 实时胶片预览
- 普通模式从 `PreviewView.getBitmap()` 取当前显示的、已经由手机厂商处理过的取景帧，再缩到 480 px 送入引擎；因此预览和 `ImageCapture` JPEG 共用同一条曝光、白平衡和色调链路。此前使用独立 `ImageAnalysis` 流时，vivo 普通模式会给分析流另一套 AE/色调，造成预览偏暗/偏灰，已改掉；
- 取帧轮询约 140 ms，后台引擎仍只允许一帧渲染，忙时丢弃当前帧；
- 扩展会话（夜景，或走扩展的 HDR）不支持从 `PreviewView` 取稳定画面，这些模式下「胶片预览」不可用；HAL HDR 场景模式仍可用；
- 预览期间界面一直在刷新，`uiautomator dump` 会等不到空闲而卡住，自动化测试要按坐标点击。

### 7.6 无线调试 / VM 环境（只和本次的远程测试有关）
- 我的命令只能在一个 Linux VM 里执行，它**连不到 Mac 本机的端口**（Mac 上 `adb -a` 只监听 IPv6），但能连到同一 Wi-Fi 的手机；所以最终是 `adb tcpip 5555` 后从 VM 直连手机；
- VM 里的静态 adb 服务进程起不来（fdsan），改用 Python 的 adb-shell；它的 sync push 和 Android 17 的 adbd 不兼容（卡死），安装改走 `exec:cmd package install -S`，**单个 WRTE 包超过 64 KB 会卡住**；
- 测完可在手机上关掉「USB 调试」或重启，无线 adb 就会关闭。

---

## 8. 已知限制与建议的下一步

1. **裁切工具**：现在边框是居中裁切，应该让用户在片门里构图（桌面版的 crop = 片门里的取景）。
2. **片边设置的取景器预览**：拍摄页的胶片预览不带边框和日期（取景器就该这样），但可以加一个半透明片门框。
3. **HDR**：给 `CaptureMode.HDR` 加实拍对比；HAL 场景模式在部分机型上是空实现，可考虑检测 `CaptureResult.CONTROL_SCENE_MODE` 回读。
4. **性能**：手机上给 `expose`（画布 + 字样栅格化）计时；字样栅格化可以按布局缓存（同一机身/帧种子不变时）。
5. **半格双联**：桌面版 `PairComposer` + `overscan_pair`，需要先做双图合成 UI。
6. **片边字样表**：`EdgeText`（Kotlin）和桌面 `Session+FilmEdge.swift` 是两份，新增胶片时要两边一起改；`edge_sensitivity.inc` 要重新生成。桌面引擎的 `overscan.cpp` 更新后，调整 `tools/port_overscan/assemble.py` 里的行号区间并重新拼装。
7. **6 个标签页**在窄屏上已经很挤（见 UI_DESIGN.md §6）。

---

## 9. Git

本轮按用户要求将代码与双语安卓 README 提交并发布 1.2；提交拆分为安卓功能/导入、双语文档、发布构建配置。最终标签 `v1.2` 指向发布提交，APK 和 SHA-256 文件作为 GitHub Release 资产，原始 RAW 和签名私钥保留在仓库外。

发布记录见 [releases/1.2.md](releases/1.2.md)，签名与构建步骤见 [RELEASING.md](RELEASING.md)。历史 Linux VM 操作仅保留为排错背景，当前 Mac 已能直接使用 SDK、NDK 和 adb。
