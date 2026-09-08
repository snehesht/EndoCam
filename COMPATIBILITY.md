# Compatibility

## Endoscopes

EndoCam supports cameras built on the Geek szitman "supercamera" chip. These are sold under many brand names, often listed for the "Usee Plus" app. Check the USB vendor and product ID before you buy.

| USB ID | Name in descriptors | Firmware tested | Status |
|---|---|---|---|
| `2ce3:3828` | Geek szitman supercamera | 1.00 | Works |
| `0329:2022` | Geek szitman supercamera | 1.00 | Works |

How to read the USB ID on Linux:

```
lsusb | grep -i -E "2ce3|0329"
```

On the phone, the app's USB permission dialog names the device. Any other ID does not match the filter and the app never opens for it.

Camera facts that apply to every listed device:

| Item | Value |
|---|---|
| Resolution | 640×480, regardless of the box label |
| Stream format | JPEG frames over USB bulk |
| Lens | Fixed focus, sharp at about 3 to 8 cm |
| Light | LED ring with a dial on the cable, not controllable by software |
| Button | One hardware button on the probe. Short press reported to the app. Hold switches the lens on dual-lens models, done by the firmware. |
| Second lens | Dual-lens models switch with a hold. Both lenses use the same stream. |

## Cameras that do not work

| Type | Why |
|---|---|
| UVC webcam endoscopes | Different protocol. Android has no built-in UVC support in this app. Use a UVC app instead. |
| Wi-Fi endoscopes | No USB. The phone connects to their Wi-Fi with the vendor app. |
| Lightning endoscopes with an Apple MFi chip | iPhone only. |

## Phones

Requirements:

- Android 10 (API 29) or later
- USB host support, also called USB OTG. Most phones from 2016 on have it. Some budget phones do not.
- A USB-C to USB-A adapter, or a USB-C endoscope cable

Tested phones:

| Phone | Android | Result | Notes |
|---|---|---|---|
| Add yours | | | Open an issue or a pull request with the result |

Report format for a new entry: phone model, Android version, whether the live view appears within a few seconds of plugging in, and the frames per second from `adb logcat -s EndoCam`.

Known non-working platforms:

| Platform | Why |
|---|---|
| iPhone, all models | iOS gives apps no raw USB access. This camera has no MFi chip. |
| iPad with M-series chip | Possible in theory with a DriverKit extension and an Apple entitlement. Not built. |
| Android without USB host | The phone cannot power or talk to the camera. |
| Android 9 and earlier | Not tested. The app targets API 29 and later. |

## Desktop

The same camera works on Linux with the C++ tool in [EndoscopeCamera](https://github.com/snehesht/EndoscopeCamera). Only one program can hold the USB device at a time.
