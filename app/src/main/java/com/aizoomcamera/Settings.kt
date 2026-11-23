package com.aizoomcamera

/** Everything the user can tune. Backed by the XML config through ConfigStore. */
data class Settings(
    val grid: Boolean = false,
    val aiEnabled: Boolean = true,
    val aiMinZoom: Float = 1.5f,
    val aiScale: Int = 0,
    val jpegQuality: Int = 95,
    val videoQuality: String = "FHD",
    val stabilization: Boolean = false,
    val exposureIndex: Int = 0,
    val manual: Boolean = false,
    val iso: Int = 100,
    val shutterDenom: Int = 100,
    val doubleTapImport: Boolean = true
)
