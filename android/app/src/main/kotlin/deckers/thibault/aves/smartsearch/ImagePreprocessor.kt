package deckers.thibault.aves.smartsearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.scale
import java.nio.FloatBuffer
import kotlin.math.max

// Converts bitmaps to normalized CHW float tensors, reusing its buffers across calls (not thread safe).
class ImagePreprocessor(private val spec: ModelSpec) {
    private val size = spec.imageSize
    private val pixels = IntArray(size * size)
    private val canvasBitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    // `rotationDegrees` and `flipped` describe the transformation to apply to the source bitmap
    fun process(original: Bitmap, rotationDegrees: Int, flipped: Boolean, out: FloatBuffer) {
        // a single bilinear pass would alias when downscaling by more than 2x,
        // so we first halve large bitmaps with filtering (the model was trained on antialiased inputs)
        var source = original
        while (minOf(source.width, source.height) >= size * 2) {
            val halved = source.scale(source.width / 2, source.height / 2, filter = true)
            if (source !== original) source.recycle()
            source = halved
        }
        try {
            draw(source, rotationDegrees, flipped, out)
        } finally {
            if (source !== original) source.recycle()
        }
    }

    private fun draw(source: Bitmap, rotationDegrees: Int, flipped: Boolean, out: FloatBuffer) {
        val rotated = rotationDegrees % 180 != 0
        val srcW = (if (rotated) source.height else source.width).toFloat()
        val srcH = (if (rotated) source.width else source.height).toFloat()
        val scaleX: Float
        val scaleY: Float
        when (spec.resize) {
            ResizeMode.SHORT_SIDE_CROP -> {
                val scale = max(size / srcW, size / srcH)
                scaleX = scale
                scaleY = scale
            }

            ResizeMode.SQUASH -> {
                scaleX = size / srcW
                scaleY = size / srcH
            }
        }

        // orient around the center, scale, then center in the square canvas (cropping overflow)
        matrix.reset()
        matrix.postTranslate(-source.width / 2f, -source.height / 2f)
        if (flipped) matrix.postScale(-1f, 1f)
        if (rotationDegrees != 0) matrix.postRotate(rotationDegrees.toFloat())
        matrix.postScale(scaleX, scaleY)
        matrix.postTranslate(size / 2f, size / 2f)

        canvasBitmap.eraseColor(0)
        canvas.drawBitmap(source, matrix, paint)
        canvasBitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        toChw(pixels, size, spec.mean, spec.std, out)
    }

    fun release() {
        canvasBitmap.recycle()
    }

    companion object {
        // `pixels` are ARGB packed ints, row major; output is RGB planes normalized with `mean` and `std`
        fun toChw(pixels: IntArray, size: Int, mean: FloatArray, std: FloatArray, out: FloatBuffer) {
            val plane = size * size
            val rScale = 1f / (255f * std[0])
            val gScale = 1f / (255f * std[1])
            val bScale = 1f / (255f * std[2])
            val rOffset = mean[0] / std[0]
            val gOffset = mean[1] / std[1]
            val bOffset = mean[2] / std[2]
            out.clear()
            for (i in 0 until plane) {
                val p = pixels[i]
                out.put(i, ((p shr 16) and 0xFF) * rScale - rOffset)
                out.put(plane + i, ((p shr 8) and 0xFF) * gScale - gOffset)
                out.put(2 * plane + i, (p and 0xFF) * bScale - bOffset)
            }
        }

        // decoding sample size so that the decoded image is at least `target` on its short side
        fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
            var sample = 1
            val shortSide = minOf(width, height)
            while (shortSide / (sample * 2) >= target) sample *= 2
            return sample
        }
    }
}
