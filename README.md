# AI Zoom Camera

Android camera app built with Kotlin, Jetpack Compose and CameraX.

## Features

- Photo zoom from 1x to 100x
- Video zoom from 1x to 30x
- AI detail enhancement through the Replicate API using Real-ESRGAN with face redraw turned off
- The original capture is always saved. The enhanced copy is saved next to it with an _AI suffix
- Files go to Pictures/AIZoomCamera and Movies/AIZoomCamera
- XML config import and export in the same format LMC uses
- Rule of thirds grid, manual ISO and shutter, exposure compensation, video quality and video stabilization

## How the zoom works

The camera hardware zooms up to the limit the phone reports. Anything beyond that on photos is a centre crop, and the AI model rebuilds the lost detail. Video uses hardware zoom only, so the top video zoom is the smaller of 30x and what the phone allows.

AI enhancement only runs when the crop reaches the AI start value, 1.5x by default, because below that no detail was lost.

## XML configs

LMC and other GCam ports store settings as Android SharedPreferences XML, a map element with string, boolean, int, long, float and set children. This app reads and writes that same layout.

To import, open Settings and tap Import XML, or double tap an empty area of the viewfinder, the same gesture LMC uses. Pick the file in the system picker. Imports merge into the current config, and Export XML saves the whole config back to a file.

Every entry from an imported file is kept and exported again unchanged, including the GCam keys. Only keys starting with aizoom_ change how this app behaves, because the GCam keys tune the HDR+ engine inside the GCam app and this app does not have that engine.

| Key | Type | Meaning |
| --- | --- | --- |
| aizoom_grid | boolean | Show the thirds grid |
| aizoom_ai_enabled | boolean | Run AI enhancement |
| aizoom_ai_min_zoom | float | Crop factor where AI starts, 1 to 10 |
| aizoom_ai_scale | int | 0 for auto, or 2 or 4 |
| aizoom_jpeg_quality | int | 50 to 100 |
| aizoom_video_quality | string | HD, FHD or UHD |
| aizoom_video_stabilization | boolean | Ask the phone for stabilization |
| aizoom_exposure_index | int | Exposure compensation step |
| aizoom_manual | boolean | Manual exposure on |
| aizoom_iso | int | ISO when manual is on |
| aizoom_shutter_denom | int | Shutter as 1 over this number |
| aizoom_double_tap_import | boolean | Double tap opens import |

## Setup

1. Create a token at replicate.com and open the app.
2. Open Settings, tap API token and paste it. It is stored encrypted on the device.
3. Push to main. GitHub Actions builds the APK and attaches it as the ai-zoom-camera-debug artifact in the Actions tab.
