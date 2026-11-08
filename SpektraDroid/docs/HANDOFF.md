# SpektraDroid 交接文档：USB 联机、全尺寸 RAW、胶片与拍摄

更新：2026-11-09（北京时间）· 分支：`dev`（1.2 之后的未发布开发变更；历史发布见 §9）· 拍摄测试：vivo V2454DA（Android 17 / API 37，arm64）；本轮 RAW 测试：arm64 Android 15 / API 35 模拟器

本文写给下一个接手 SpektraDroid（SpektraLab 的 Android 移植）的人。界面细节见同目录的 [UI_DESIGN.md](UI_DESIGN.md)。

---

## 0. 当前开发：Sony USB 联机与全尺寸输出

用户批准执行，连接方式明确为 **USB 直连**。目标 A7 V、A1 II、A7R V、A7 IV、A1、A6700、A7R IV/IVA、A7R III/IIIA、A7 III。
本轮没有实体 Sony 接入，**不能把协议 fixture / 演示监看 / RAW 解码测试说成相机硬件兼容性验证**。

- 新入口：编辑器空白页与「更多 → 连接 USB 相机」。Android USB Host 权限 + Kotlin PTP，复用 libgphoto2 已解决的索尼协议规则。设备 USB 模式需为 PC 遥控。具体机型 ID、参考提交、协议步骤见 [SONY_USB.md](SONY_USB.md)。
- `tether/PtpProtocol.kt`：分段/合并 bulk 流、USB传输终止空包兼容（回归先红后绿）、事务号检查、限长和流式照片写盘，OpenSession 事务为 0。`SonyUsbCamera.kt`：Sony 三阶段握手、新旧属性、2024+ 二级枚举。只在 D215 > 0x8000 时读取 FFFFC001（提前读可触发固件错误）；取景读 FFFFC002。串行轮询，无 interrupt 事件线程。
- 仅在相机声明可写且支持时设电脑＋相机、原始传输尺寸，记住旧值并正常断开时恢复；拔线后无法恢复，提示用户检查。应用不改 RAW 压缩、裁切、曝光、不 DeleteObject。RAW/JPEG 由相机设置决定，原文件存 Downloads/SpektraLab，最近一张可进入编辑器。
- 480p/720p 为画面上限、不放大、不裁切；胶片监看使用照片引擎生成的 17³ 色调 LUT，不做颗粒、光晕等空间效果。MP4 H.264 无声录制收到的取景帧；可将相机返回参数由手机叠加，也可录制纯取景流。不是相机 LCD 的逐像素镜像，无法删除相机已烧入画面的文字。
- 后台、主动断开、取景持续中断、低磁盘空间会结束并保存视频。完整媒体发布失败保留 `files/tether-recovery` 可重试；未完整照片删除。进程被杀/掉电不能保证未封装 MP4 恢复。调试版才有明确标识的演示入口，正式版无入口。
- `FullSizeExport.kt` + 编辑器导出：完整尺寸开关持久化，需 64 位进程和足够**当前可用**内存。先释放预览引擎缓存，再估算检查。RAW native maxEdge=0，核对解码宽高，完整胶片处理后 JPEG 输出不缩小；内存不足明确报错，不会重试小图。该模式本次关闭片边避免裁切，保留用户的片边设置和可选日期。无法恢复相机拍摄时已裁切/Reduced RAW 的像素。
- 内存估算：输入像素×24 + 输出像素×80 + 512 MiB；61MP 约6.9GB。它是防护估计，不是峰值测量或系统不会杀进程的保证。
- 九款完整尺寸公开 ARW（24–61MP）已实际 native decode → 默认完整效果 → JPEG → 尺寸复读通过。来源、SHA、耗时、复现命令、待实体测试表见 [USB_FULL_RAW_VALIDATION.md](USB_FULL_RAW_VALIDATION.md) 与 [full-raw-fixtures.json](full-raw-fixtures.json)。

开发环境在仓库外 `../.android-dev/`，模拟器 `SpektraLab_API35`、API35 arm64、内存参数 10240MiB。设置 `ANDROID_AVD_HOME=../.android-dev/avd`。卡 crash consent 的实例已停止，当前用 `-no-window -no-audio -no-snapshot -no-metrics -gpu swiftshader` 正常启动；可通过 adb 取屏。SDK、JDK、Gradle、ADB 35、LibRaw 路径参照现有本机脚本和 local.properties。不要将样片、签名或 APK 加入 Git。

验证记录：调试版原引擎/RAW/联机17项全过，15份RAW全过；新增编辑器全尺寸集成及USB空包回归后联机9项全过。正式精简包联机9项全过，旧引擎/导入10项及15份RAW通过；独立测试包跨R8访问需保留相关API，规则在 proguard-rules.pro。九份全尺寸RAW独立实测全过。两种构建 lint 均0错误、17警告。

最终开发 APK 另存为仓库外 `../.android-dev/builds/SpektraLab-USB-dev.apk`，调试包已安装在模拟器，实际手机当前未连接；崩溃日志为空。

本轮仅本地开发与构建，没有改 GitHub 已发布 v1.2。调试 APK 为 `.dev`，正式构建仍标 v1.2/3，**发布下一版前需确定新版本号并重新跑发布流程**。

## 0.1 追加：三个工作模式、拍摄参数与素材闭环

本地新增，尚未提交或发布。主要实现：

- `MainActivity` 底部固定工作台 / 相机 / 监视器，拍完留在相机，不自动跳编辑器。导出和拍照时保护切换；离开监视器等待录制与连接收尾。
- `CameraViewModel` 读取曝光补偿范围与步长，设置后等待设备确认；中央 / 点按 AE 区域与 AF，能力不足禁用。点坐标使用 PreviewView.meteringPointFactory。不是手动 ISO / 快门 / 光圈，也不是厂商私有的测光算法。
- `LiveFilmControls` 复用完整 ControlPanel；三模式复用同一套控件；工作台和拍摄设置的隔离见 §0.2。手机每帧读取最新参数；监视器通过 updateLook 更新 LUT，约180ms限流，连接期间无需重连。监看仍不计算照片空间效果，录制期间调整的色调会进入后续视频帧。
- `MediaLibrary` 持久化 URI、来源、类型、胶片参数及成片的原片关联；相机拍摄、系统回传、USB 接收、MP4 保存和工作台导出均入库。临时分享导入复制到私有 library-imports，SAF 尽量持久授权；不扫描用户相册。
- `MediaBrowser` 每模式素材缩略条、应用内原片 / RAW 预览、VideoView 回放；可进入工作台恢复参数，成片重新编辑会载入原片，避免再次叠加胶片。列表移除不删除原文件。RAW 缩略图串行解码并缓存；PhotoIO 保留编辑器当前 RAW 源缓存，防止缩略图切换误删导出输入。
- 胶片设置自动记忆到当前素材；切换相机 / 监视器后调色不改写工作台原片的记录。视频只存开始录制时的参数快照，不能据此重建整个动态调参时间线。

用户已在上一轮确认实体相机取景出现；具体机型未提供。本轮只连接模拟器，新增控件的实体机测光 / 曝光表现、Sony USB 断线与长时间录制仍需真机验证。测试与最终构建结果见 [WORKSTATION_VALIDATION.md](WORKSTATION_VALIDATION.md)。

## 0.2 追加优化：配方库、完整素材浏览与操作稳定性

2026-11-07（洛杉矶） / 2026-11-08（北京）。本地开发，尚未提交或发布。本轮只连接 Android 15 / API35 arm64 模拟器，没有实体手机或 Sony；原生 RAW / 胶片引擎本轮未改动。

- **命名胶片配方**：新增 `RecipeStore.kt` 和 `ui/RecipePicker.kt`。工作台「更多」及相机 / 监视器胶片参数面板均可保存 / 调用、改名、确认移除。保存完整 `EditState`（胶片、相纸、效果、片边与日期），不保存手机曝光、镜头和测光。独立 `film_recipes` SharedPreferences，写盘失败显示错误；名字去空白后1–40字，不区分英文大小写重名，不静默覆盖。
- **模式设置隔离**：工作台保留自身调色，相机和监视器共享拍摄调色；回工作台恢复原设置并重新渲染。拍摄调色不覆盖工作台素材的记忆记录。两个上下文在当前 ViewModel 生命周期内保留；命名配方跨重启持久化，两个未命名上下文尚未分别跨进程持久化。照片加载期间禁止模式切换，避免异步载入覆盖拍摄状态；导出期间锁住参数。
- **完整素材库**：缩略条保留最近30项，点「查看全部」进入网格，可搜索文件名 / 来源，筛选导入、手机拍摄、相机传输、冲印成片和照片 / RAW-TIFF / 视频；相机和监视器默认选择自身来源，也可改为全部。分类按扩展名，不代表新增了相应解码格式的验证承诺。
- **详情可靠性**：上一张 / 下一张遵循当前筛选顺序；图片支持双指缩放 / 平移至5倍与复位。加载中、失败、重试分别显示；原文件失效时即使存在旧缓存也显示错误。视频后台暂停、关闭释放。素材格子整块可点，文件名允许换行。
- **缓存与原片关联**：RAW缩略解码继续串行，缓存临时写入后替换，缓存写失败不隐藏已解码照片。恢复发布 URI 时同时更新成片的 `parentUri`，避免「编辑原片」指向旧恢复位置。列表移除仍保留原文件。
- **拍摄操作不被卷走**：快门、系统相机、前后切换固定于滚动区外；拍摄参数 / 胶片参数入口移到面板上方。监视器录制 / 停止和断开固定于顶栏下方。展开胶片设置后仍可停止视频。
- **Android15弹窗修复**：当前 Compose 全屏 Dialog 的内容曾超出系统给的实际窗口，导致详情底部移除按钮被遮。`MediaBrowser.kt` 中的 `Modifier.mediaDialogSize()` 展开主题 ContextWrapper 找 Activity，按主窗口系统栏 / 刘海边距限制高度；API26–29使用兼容 insets。后续更新 Compose 时可重审，但必须实测底部按钮。
- **回归与清理**：`WorkstationTest` 新增配方持久化 / 重名防护、组合搜索分类、模式隔离与原片记录保护；原有恢复 URI 回归补测子成片关联。`TetherTest` 现在清理自身新增目录和视频，避免测试向素材库残留失效记录。R8保留独立正式测试包需要访问的配方与分类API。

最终构建、测试数量 / 耗时、APK校验值和操作截图见 [WORKSTATION_VALIDATION.md](WORKSTATION_VALIDATION.md) 的追加验证。APK仍是1.2/3的本地开发产物，不代表GitHub新增发布。

后续优先级：实体 Sony 连接 / 断线 / 长录复核；批量冲印与可取消队列；编辑撤销 / 重做；构图裁切旋转；配方分享和备份；素材评级与项目分组。素材缩略缓存暂未设置容量上限，可由系统清理；不应自动删除原文件。

## 0.3 模拟器页面与交互评估（待修清单）

2026-11-07（洛杉矶）／2026-11-08（模拟器北京时间）。按用户要求打开当前 1.2-dev / 3，在 Android15/API35 模拟器实操竖屏与横屏；**本轮只评估与记录，未修改应用实现**。详细步骤、优先级和14张截图见 [UI_AUDIT.md](UI_AUDIT.md)。

导入 → 胶片预览／对比 → 导出 → 成片详情 → 编辑原片，以及手机拍照 → 素材详情 → 工作台均能走通；搜索／类型筛选、失效素材错误提示、监看演示录制与回放可用。本轮未观察到新增崩溃，日志中10:02的旧初始化崩溃早于当前包11:03的安装时间。监视器仅使用明确标识的演示入口，不能当作 Sony 实体兼容性验证。

优先修复：工作台查看区受参数／素材／导航挤压（横屏更严重）；相机展开胶片面板后缩略图只露窄条；新导入／新成片插入后缩略条保持旧位置，最新素材藏在左侧屏外；监视器收起胶片参数按钮在屏外。录制中点其他模式会立即保存并断开，缺少切换前提示；返回后旧画面和旧fps仍显示，应标“最后一帧”并清空实时指标。其余待办：控件类型与“拍摄”命名、空状态引导、小图重复导出尺寸、录制时长与小字对比度。暖黑／米白／琥珀色风格可保留。

模拟器已恢复竖屏、停止演示及录制，停留工作台。新增的审查样片／照片／视频／成片保留在模拟器，未清空或删除既有素材。没有提交、发布或更新 APK。

## 0.4 dev 分支提交前检查

2026-11-09（北京时间）。用户要求把当前全部安卓开发成果推送到 `dev`；基于远程 `main` 的 `d2c95c4` 创建该分支。代码包含 Sony USB 联机、监看录制、完整尺寸 RAW、三模式工作站、素材库与配方，以及上一轮 UI 评估。本轮未实现评估中的界面修复，也未创建新 APK 发布。

推送前重新完成 Debug 构建、测试包构建与 Lint（0错误、17警告、1条信息）；模拟器运行 `AndroidEngineTest`、`NativeImportTest`、`WorkstationTest`、`TetherTest` 共25项，88.430秒，全部通过、无跳过，其中外部真实RAW为模拟器现有8份。没有重跑历史15份或9份全尺寸真实RAW，也没有新增实体Sony验证。详细记录见 [WORKSTATION_VALIDATION.md](WORKSTATION_VALIDATION.md) 的 dev 提交前检查。

仅提交代码、说明、授权文本、样片索引和界面截图；签名、登录凭据、本机配置、APK、原始RAW及构建缓存均留在仓库外。此前章节中的“尚未提交”描述各轮当时状态，当前开发进度以本节和Git历史为准。

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
