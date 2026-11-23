# AI Zoom Camera

Android camera app built with Kotlin, Jetpack Compose and CameraX.

## Features

- Photo zoom from 1x to 100x
- Video zoom from 1x to 30x
- AI detail enhancement through the Replicate API using Real-ESRGAN with face redraw turned off
- The original capture is always saved. The enhanced copy is saved next to it with an _AI suffix
- Files go to Pictures/AIZoomCamera and Movies/AIZoomCamera

## How the zoom works

The camera hardware zooms up to the limit the phone reports. Anything beyond that on photos is a centre crop, and the AI model rebuilds the lost detail. Video uses hardware zoom only, so the top video zoom is the smaller of 30x and what the phone allows.

AI enhancement only runs when the crop is 1.5x or more, because below that no detail was lost.

## Setup

1. Create a token at replicate.com and open the app.
2. Tap API token and paste it. It is stored encrypted on the device.
3. Push to main. GitHub Actions builds the APK and attaches it as the ai-zoom-camera-debug artifact in the Actions tab.
