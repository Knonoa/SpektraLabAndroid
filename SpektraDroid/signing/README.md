# Signing / 签名

| key | where | signs | secret? |
|---|---|---|---|
| **Development** `dev.keystore` | this folder, committed | debug builds only: `com.spektralab.android.dev` / `.usb` | **No — public by design** |
| **Release** (`CN=Knonoa, OU=SpektraLabAndroid`) | GitHub Actions secrets only | `com.spektralab.android`, published APKs | **Yes — never commit** |

## Development key / 开发签名

`./gradlew assembleDebug` on any computer signs with `dev.keystore`, so a dev
build made on one machine installs over one made on another — no uninstall.
Its passwords are in `app/build.gradle.kts` in plain text on purpose. Because
everyone can read it, it must never sign anything users install: it only
covers the `.dev` / `.usb` application IDs, which coexist with the store app.

任何电脑执行 `assembleDebug` 都用 `dev.keystore` 签名，不同电脑构建的开发包可以直接覆盖安装。
它的密码有意明文写在 `app/build.gradle.kts` 里，因此**只能用于 `.dev` / `.usb` 开发包**，绝不用于正式版。

- Alias `spektralab-dev`, store/key password `spektralab-dev`, PKCS12, valid to 2056.
- Certificate SHA-256 `52:DD:0D:30:6B:E7:57:C7:67:4E:CB:8C:C0:D6:4B:F8:44:18:FE:A6:E0:15:55:4D:52:29:8B:68:33:35:33:54`.
- First switch only: a dev build signed by a machine's old `~/.android/debug.keystore`
  must be uninstalled once (`adb uninstall com.spektralab.android.dev`).
  首次切换时，用各电脑旧调试签名装过的开发包需要卸载一次。

## Release key / 正式签名

The release key is **not** in this repository (it is public): anyone holding it
could sign an update that installs over SpektraLab on users' phones. Instead
it lives in the repository's encrypted GitHub Actions secrets, and
`.github/workflows/android-release.yml` builds and publishes signed APKs in the
cloud. No developer machine needs the key.

正式密钥**不在仓库里**（仓库公开，持有密钥的人能签出覆盖安装到用户手机的更新）。
它只保存在仓库的 GitHub Actions 加密 Secrets 中，由 `.github/workflows/android-release.yml`
在云端构建并发布签名 APK，开发电脑都不需要持有密钥。

| secret | value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the `.jks` / `.p12`, base64 |
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Set them once under **Settings › Secrets and variables › Actions**, or with the
GitHub CLI, in PowerShell, from the folder holding `release.jks` on the machine that has the key:

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path release.jks)))
gh secret set ANDROID_KEYSTORE_BASE64 -R Knonoa/SpektraLabAndroid -b $b64
gh secret set ANDROID_KEYSTORE_PASSWORD -R Knonoa/SpektraLabAndroid   # prompts
gh secret set ANDROID_KEY_ALIAS -R Knonoa/SpektraLabAndroid
gh secret set ANDROID_KEY_PASSWORD -R Knonoa/SpektraLabAndroid
```

Keep an offline backup of the `.jks` and its passwords (e.g. a password
manager). If it is lost, no future version can update existing installs.

请另外离线备份 `.jks` 与密码（如密码管理器）。密钥丢失后，任何新版本都无法覆盖安装已有用户的应用。

### Releasing / 发布

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`; add `docs/releases/<version>.md`.
2. `git tag v<versionName> && git push origin v<versionName>`.
3. The workflow builds `assembleRelease`, checks the tag matches `versionName`
   and that the APK carries the pinned release certificate
   (SHA-256 `1fb15d9a…6f6946`), then creates the GitHub Release with
   `SpektraLab-<version>.apk` and its `.sha256`.

**Actions › Android release › Run workflow** builds and verifies the signed APK
as a workflow artifact without publishing — use it to check the secrets.
手动运行工作流只构建并校验签名、产出 artifact，不发布，可用来验证 Secrets 是否正确。
