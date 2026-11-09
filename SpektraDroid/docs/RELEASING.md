# Android release builds / 安卓发布构建

The Android project is `SpektraDroid/`. Run commands below from that directory with JDK 17 and Android SDK configured. Release builds use the production package `com.spektralab.android`, R8 minification and resource shrinking. Debug builds use `.dev` and require no release credentials.

安卓工程为 `SpektraDroid/`。以下命令在该目录执行，需配置 JDK 17 与 Android SDK。正式包启用 R8 和资源精简，包名 `com.spektralab.android`；调试包使用 `.dev`，不需要发布密钥。

## Private signing / 私有签名

Set `SPEKTRALAB_SIGNING_PROPERTIES` to an **absolute path outside the repository**. This UTF-8 properties file contains:

将 `SPEKTRALAB_SIGNING_PROPERTIES` 指向**仓库外的绝对路径**。UTF-8 配置文件格式如下：

```properties
storeFile=/absolute/private/path/release.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

Use restrictive filesystem permissions and back up both the keystore and credentials securely. Never commit or upload these files. Use the same key for future updates. Builds requesting a release task fail if credentials are absent; there is no debug-key fallback.

限制文件访问权限，安全备份密钥和凭据，禁止提交或上传这些文件。后续版本必须沿用相同密钥；缺少配置时正式构建会失败，不会自动改用调试签名。

From 1.3 on, releases are signed in GitHub Actions with the key that signed 1.1 (certificate SHA-256 `ba6855f0…67f122`, alias `spektralab-release`; see [../signing/README.md](../signing/README.md)). 1.1 installs update in place. 1.2 was signed with a separate key, so Android rejects an in-place update from 1.2: those users back up settings and uninstall once. `.dev` and `.usb` builds can coexist.

自 1.3 起，正式版在 GitHub Actions 中用签署 1.1 的密钥签名（别名 `spektralab-release`，见 [../signing/README.md](../signing/README.md)）。1.1 用户可直接覆盖升级；1.2 使用另一把密钥，1.2 用户需先备份设置并卸载一次。`.dev`、`.usb` 开发包可并存。

## Build and verify / 构建与检查

```bash
bash tools/setup_libraw.sh
export SPEKTRALAB_SIGNING_PROPERTIES=/absolute/private/path/release.properties
./gradlew assembleRelease assembleReleaseAndroidTest lintRelease -PtestBuildType=release
```

- App: `app/build/outputs/apk/release/app-release.apk`.
- Test: `app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`.
- R8 mapping: `app/build/outputs/mapping/release/mapping.txt` (retain privately for diagnostics).
- Release lint: `app/build/reports/lint-results-release.html`.

Install the app and its release test APK on a disposable emulator. The optional `testBuildType` property selects release instrumentation without making the shipped app debuggable. The test APK uses the same release certificate.

在测试模拟器中安装正式应用和对应测试包。`testBuildType` 只选择测试目标，不会把发布应用改成可调试；测试包与应用使用同一发布证书。

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r -t app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w -r \
  -e class com.spektralab.android.NativeImportTest,com.spektralab.android.AndroidEngineTest \
  com.spektralab.android.test/androidx.test.runner.AndroidJUnitRunner
```

Real RAW tests require readable external fixtures. A non-debuggable app cannot use `run-as`; on a rooted test emulator, copy fixtures to `/data/user/0/com.spektralab.android/cache/raw-fixtures`, set owner to the app UID and run with `-e rawFixtureDir ... -e rawFixtureCount 15`. See [RAW_VALIDATION.md](RAW_VALIDATION.md) for sample provenance and assertions. A run that skips external RAW fixtures does not validate those cameras.

真实 RAW 测试需应用可读样片。正式应用不能使用 `run-as`；可在允许 root 的模拟器上把样片放入内部缓存并设置应用 UID，再指定目录与数量。样片来源及断言见 [RAW_VALIDATION.md](RAW_VALIDATION.md)，跳过样片的绿灯不代表相机实测通过。

Check APK metadata, `android:debuggable=false`, certificate, included ABIs, licences and `0.23.0-Snapshot202611` native version. Compute SHA-256 after copying to the final release filename. Publish the APK and checksum as GitHub Release assets, retain the R8 mapping, tag the tested source commit, then download the published APK and compare the checksum.

检查包名、版本、不可调试标记、签名、ABI、许可及原生库版本；最终命名后计算 SHA-256。GitHub Release 上传 APK 与校验文件，保留 R8 mapping，将标签指向对应源码提交，最后下载线上 APK 核对哈希。不要把 APK、私钥、原始相机大文件放进代码提交。
