package io.rebble.libpebblecommon.util

import androidx.compose.ui.graphics.ImageBitmap

actual fun createImageBitmapFromPixelArray(pixels: IntArray, width: Int, height: Int): ImageBitmap? = null

actual fun isScreenshotFinished(buffer: DataBuffer, expectedSize: Int): Boolean = buffer.remaining == 0
