package com.aizoomcamera

/** Lets the activity pass hardware key presses to the camera screen. */
object KeyBus {
    @Volatile
    var handler: ((Int, Int) -> Boolean)? = null
}
