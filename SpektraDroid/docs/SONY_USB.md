# Sony USB tethering and monitor / 索尼 USB 联机与监看

Development scope, 2026-11-07. Physical camera validation is pending. Model IDs and
protocol references are evidence for implementation, not a claim of tested hardware.

## Connection / 连接

Use a data-capable USB cable with an Android USB host / OTG phone. On the camera,
choose PC Remote / Remote Shooting, USB connection. PC save destination should be
PC + Camera, file size Original; choose RAW/JPEG in the camera menu. These labels
vary by firmware. Close Imaging Edge, Creators' App and other USB camera applications.

使用 USB 数据线，手机需支持 USB Host/OTG。相机选择 PC 遥控/远程拍摄，连接方式 USB；
PC 保存目的地选电脑＋相机，尺寸选原始，RAW/JPEG 格式按需设置。相机菜单名称随固件不同。
关闭其他占用相机的应用；手机首次连接需允许 USB 访问。

Open **连接 USB 相机** from the editor menu or empty state. The app enumerates Sony
USB devices and claims the still-image bulk interface after Android permission.
It does not use undocumented phone camera IDs, root, Wi-Fi or an HDMI capture card.

## Target models / 目标机型

| Camera | PC Remote USB product ID (Sony vendor 054c) | Evidence | Physical test |
|---|---|---|---|
| A7 V / ILCE-7M5 | 0f56 | libgphoto2 capture + preview entry | Pending |
| A1 II / ILCE-1M2 | 0eed | same | Pending |
| A7R V / ILCE-7RM5 | 0e0c | same | Pending |
| A7 IV / ILCE-7M4 | 0da7 | same | Pending |
| A1 / ILCE-1 | 0d1c | same | Pending |
| A6700 / ILCE-6700 | 0e78 | same | Pending |
| A7R IV / IVA | 0ccc / 0d9f | same | Pending |
| A7R III / IIIA | 0c33 / 0d9b | same | Pending |
| A7 III | 0c34 | same | Pending |

Devices are not rejected solely because the product ID is new: the Sony vendor,
still-image interface, DeviceInfo and SDIO capability handshake are decisive.
MTP or mass-storage mode alone does not provide live view. Target models require
individual firmware/phone/cable tests before compatibility can be confirmed.

## Implemented transport / 已实现通路

- PTP OpenSession transaction 0; Sony SDIO phases 1, 2, GetExtDeviceInfo (0x12c, 1),
  phase 3. Negotiates old/new property access and consumes 2024+ secondary enums.
- Serialized USB bulk transactions. Handles fragmented/coalesced containers,
  partial writes, transaction/code validation, length limits and I/O timeout.
- Live view: GetObjectInfo + GetObject on virtual object `ffffc002`, JPEG payload
  offset preferred to a naive marker search (gphoto issue 389). AccessDenied,
  InvalidObjectHandle and DeviceBusy may be transient.
- Photo readiness: poll GetAllExtDevicePropInfo, read ObjectInMemory `d215`.
  Only access `ffffc001` when value **> 0x8000**, as libgphoto2 warns early access
  can crash camera firmware. RAW/JPEG bytes stream to a temporary file, with
  size verification, before publishing to Downloads/SpektraLab. No DeleteObject.
- Requests card + PC and Original transfer size only if the camera advertises
  those values. Retains previous values and restores on orderly disconnect.
  Unplugging prevents restoration: inspect camera settings after disconnect.
  It does not change sensor crop, RAW compression, shutter or aperture.
- Completed files that cannot be published remain in the app's private recovery
  directory. **重试保存未完成的文件** republishes them. Incomplete transfers are removed;
  this path cannot resume a partial RAM download after unplugging.

相机拍摄后自动接收原始文件，不主动下载相机原始视频。照片保留在公开下载目录，最近一张可进入编辑器；
持续拍摄时保持监看。首版未实现手机遥控快门、曝光控制、卡片浏览、Wi-Fi、UVC 或 HDMI 输入。

## Monitor and recording / 监看与录制

480p and 720p are **bounding boxes**, 16:9 width limit, keeping source aspect ratio
and never upscaling. The effective camera feed may be smaller. One sequential
pipeline bounds latency; photos temporarily occupy the USB link.

The colour LUT samples a 17³ RGB cube through the native photo engine with selected
film/paper/exposure settings. Native trilinear interpolation applies it per frame.
Auto metering, grain, halation, spatial couplers, glare, border and date are disabled
in this monitor LUT. It is a colour approximation, not the complete photo pipeline.

MP4 / H.264 records the received, optionally filtered frames, without audio.
**录入相机参数** draws camera-reported ISO/aperture/shutter; otherwise only the feed
is encoded. This is an app-drawn parameter overlay, not a pixel-perfect mirror of
the camera LCD. Text already baked into the camera JPEG cannot be removed. Source
stalls / photo transfers retain elapsed-time gaps, not fabricated 30 fps motion.
Backgrounding, disconnect or low storage finalizes the current video. Process death,
power loss and faulty codecs cannot guarantee recovery of an unfinished MP4.

调试包提供明确标识的模拟器演示：运动测试画面、模拟快门，与真正 USB 通路的协议测试分开。
发布版不提供演示入口。真实相机的帧率、延迟、画面干净程度和连续接收需实体设备验证。

## Reference and attribution / 参考与署名

[libgphoto2](https://github.com/gphoto/libgphoto2), commit
`26a57b27caeb865a6b1d415aa5a80b47837c9023`:

- [library.c](https://github.com/gphoto/libgphoto2/blob/26a57b27caeb865a6b1d415aa5a80b47837c9023/camlibs/ptp2/library.c): models, handshake, preview, safe RAM read.
- [ptp.c](https://github.com/gphoto/libgphoto2/blob/26a57b27caeb865a6b1d415aa5a80b47837c9023/camlibs/ptp2/ptp.c) / ptp.h: operations and properties.
- [ptp-pack.c](https://github.com/gphoto/libgphoto2/blob/26a57b27caeb865a6b1d415aa5a80b47837c9023/camlibs/ptp2/ptp-pack.c): old/new Sony datasets.
- config.c: card+PC and original-size value mappings.

Authors include Mariusz Woloszyn, Marcus Meissner, Linus Walleij, Axel Waggershauser
and other credited contributors. PTP source headers are LGPL-2.0-or-later;
project COPYING supplies LGPL-2.1. Notices/text are bundled under assets/licenses/libgphoto2.
The GPL-3.0-or-later app adapts these protocol facts in Kotlin; no proprietary Sony
SDK or Google Camera binary is embedded.

## Full-size photo export / 全尺寸照片导出

The export dialog offers **允许全尺寸处理 → 完整尺寸**. This is JPEG development
from the full decoded active image (EXIF orientation applied), not a new RAW file.
The editor stays at its interactive preview sizes. Native RAW/TIFF decoding uses
maxEdge=0 for this path and verifies the decoded width/height before rendering.
Film Edge is disabled for that export, preserving composition and dimensions;
the user's border preference is retained. Date Back can remain active.

Requires a 64-bit process and sufficient **currently available** memory. The
advisory estimate is source pixels × 24 + output pixels × 80 + 512 MiB.
A 61 MP file estimates about 6.9 GB. It is a guard, not a promise of peak memory
or OS survival; other processes and manufacturers' per-app limits still matter.
There is no OOM retry at a smaller size. Insufficient memory produces an explicit
error. A smaller/APS-C RAW made in-camera cannot be expanded back to full frame.

Nine real Sony 24–61 MP samples passed full decode, default film effects and JPEG
dimension checks on the high-memory emulator. Results and repeatable commands:
[USB_FULL_RAW_VALIDATION.md](USB_FULL_RAW_VALIDATION.md).
