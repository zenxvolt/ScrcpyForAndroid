# Scrcpy for Android

- This application is android port to desktop applicaton [**Scrcpy**](https://github.com/Genymobile/scrcpy).

- This application mirrors display and touch controls from a remote android device to android device.

- Scrcpy for Android uses the ADB interface to connect to the android device to be mirrored, either over the network (WiFi) or over a USB cable.

## Download

- [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/org.client.scrcpy)
- [GitHub APK: scrcpy-release.apk](https://github.com/zwc456baby/ScrcpyForAndroid/releases)

## Screenshot

![home](home.jpg)

## Easiest ways to connect

The home screen has quick actions under the address field:

- **Find devices** – lists phones on the same Wi-Fi that have *Wireless debugging* (Android 11+) or *ADB over Wi-Fi* enabled. Tap one to use it, no typing needed.
- **Pair (Android 11+)** – on the target open *Developer options → Wireless debugging → Pair device with pairing code*, enter the IP:port and 6-digit code. The app pairs, finds the connection port automatically and connects.
- **USB → Wi-Fi** – plug the target in once with a USB/OTG cable and tap it. The app enables ADB over Wi-Fi (port 5555) through USB, reads the target's IP and connects; you can then unplug the cable.
- When a connection fails, the app explains why (not authorized, wrong port, not on the same network, …) and what to do next. Pasted text such as `adb connect 192.168.1.5:5555` is cleaned up automatically.

## Instructions to use (WiFi)

- Make sure both devices are on same local network.
- Enable **ADB-connect/ADB-wireless/ADB over network** on the device to be mirrored. 
- Open scrcpy-android app and enter ip address of device to be mirrored.
- Select display parameters and bitrate from drop-down menu(1280x720 and 2Mbps works best).
- Set **Navbar** switch if the device to be mirrored has only hardware navigation buttons.
- Hit **start** button.
- Accept and trust(check always allow from this computer) the ADB connection prompt on target device(Some custom roms don't have this prompt).
- Thats all! You should be seeing the screen of remote android device.
- To wake up the remote device, **tap the mirrored screen once** (the first tap only wakes it). Turn on *Turn target screen off while mirroring* to keep the target's panel dark while you keep using it remotely.
- To put the remote device to sleep, **close proxmity sensor and double tap anywhere on the screen**. 
- To bring back the local android system navbar while mirroring the remote device, **swipe up from the bottom edge of screen**.



## Instructions to use (USB)

- Connect the target device to this phone with a USB cable (USB-C to USB-C, or an OTG adapter).
- Enable **USB debugging** in Developer options on the target device.
- Open the app. When an ADB-capable USB device is detected, the address field is automatically pre-selected to `USB: <device>`.
  - You can also tap the address drop-down and choose the `USB:` entry manually.
- Select display parameters and bitrate from the drop-down menus, same as the WiFi flow.
- Hit **start**.
- Grant the USB permission prompt on this phone, then accept the **Allow USB debugging?** prompt on the target device (check *Always allow from this computer* to skip it next time).


## Connecting to public network devices



>  The public network port of the device needs to be open for access



### Connection Example

- 192.168.1.222

- host.example.com:5555

- [2000:2000:2000:2000::2000]:5555

## Code Reference

- [scrcpy-android](https://gitlab.com/las2mile/scrcpy-android)
- [scrcpy](https://github.com/Genymobile/scrcpy)

## Building

GitHub Actions (`.github/workflows/build.yml`) builds the app on every push, pull request, or manually from the *Actions* tab:

- an installable **debug APK** is always uploaded as a workflow artifact;
- a **signed release APK** is built too when the `SIGNING_KEY`, `KEY_STORE_PASSWORD`, `ALIAS` and `KEY_PASSWORD` secrets are set;
- `libadb.so` is built from `platform-tools-34/` with NDK r26d on the first run and cached afterwards.

`android.yml` remains available to publish a GitHub Release.

## Bundled adb binary

The `libadb.so` files in `app/src/main/jniLibs/` are built from
[lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools).

That project's sources are vendored in [`platform-tools/`](platform-tools)
(see [VENDOR.md](platform-tools/VENDOR.md)), and
[`.github/workflows/build-adb.yml`](.github/workflows/build-adb.yml) builds the four ABIs
from the vendored copy. The build no longer downloads the upstream repository,
so force-pushes there can't break it.

> Note: [`platform-tools/`](platform-tools) builds platform-tools **36.0.0**
> (API 30, Android 11+), while [`platform-tools-34/`](platform-tools-34)
> builds platform-tools **34.0.0** for **API 24 (Android 7.0+)**, matching the
> existing `jniLibs` binaries. Their workflows are
> [`build-adb.yml`](.github/workflows/build-adb.yml) and
> [`build-adb-34.yml`](.github/workflows/build-adb-34.yml).

