package deckers.thibault.aves.smartsearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import deckers.thibault.aves.storage.StorageUtils
import deckers.thibault.aves.utils.LogUtils
import deckers.thibault.aves.utils.MimeTypes
import deckers.thibault.aves.utils.UriUtils.tryParseId
import java.io.IOException

// Loads small bitmaps for embedding, without Glide, so that indexing neither evicts
// the grid thumbnail caches nor shares pooled bitmaps with concurrent UI decoding.
// Callers must `recycle` the returned bitmap once its pixels are copied.
class MediaBitmapLoader(private val context: Context) {
    class Result(
        val bitmap: Bitmap,
        // transformation still to apply to the bitmap to get the entry orientation
        val rotationDegrees: Int,
        val flipped: Boolean,
    )

    sealed class LoadResult {
        class Loaded(val value: Result) : LoadResult()

        // the media can be read, but not decoded
        object Undecodable : LoadResult()

        // the media cannot be read now (e.g. missing, unmounted storage, revoked access),
        // or decoding failed for a reason that may not happen again (e.g. out of memory, I/O error)
        object Unavailable : LoadResult()
    }

    fun load(uri: Uri, mimeType: String, rotationDegrees: Int, isFlipped: Boolean, target: Int): LoadResult {
        val result = try {
            loadBitmap(uri, mimeType, rotationDegrees, isFlipped, target)
        } catch (e: OutOfMemoryError) {
            Log.w(LOG_TAG, "OOM when loading uri=$uri mimeType=$mimeType", e)
            return LoadResult.Unavailable
        } catch (e: IOException) {
            Log.w(LOG_TAG, "I/O error when loading uri=$uri mimeType=$mimeType", e)
            return LoadResult.Unavailable
        }
        if (result != null) return LoadResult.Loaded(result)
        return if (canRead(uri)) LoadResult.Undecodable else LoadResult.Unavailable
    }

    // Empty files (e.g. failed copies) are readable but can never be decoded, so they are not retried.
    // If they are written later, their size changes, and so does their fingerprint.
    private fun canRead(uri: Uri): Boolean {
        return try {
            StorageUtils.openInputStream(context, uri)?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun loadBitmap(uri: Uri, mimeType: String, rotationDegrees: Int, isFlipped: Boolean, target: Int): Result? {
        val isVideo = MimeTypes.isVideo(mimeType)
        val isMediaStore = StorageUtils.isMediaStoreContentUri(uri)
        try {
            if (isMediaStore) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Media Store thumbnails are rotated according to EXIF orientation for known formats, but never flipped
                    if (!isFlipped) {
                        // thumbnails fit within the requested size, so we request more than the model input
                        // to get a short side of at least `target` (e.g. 126x168 when requesting 224x224)
                        val requested = target * THUMBNAIL_OVERSAMPLING
                        val bitmap = ensureSoftware(context.contentResolver.loadThumbnail(uri, Size(requested, requested), null))
                        if (!isDegenerate(bitmap)) {
                            val needRotation = !isVideo && MimeTypes.needRotationAfterContentResolverThumbnail(mimeType)
                            val thumbnail = Result(bitmap, if (needRotation) rotationDegrees else 0, false)
                            // thumbnails can be much smaller than requested (e.g. embedded EXIF thumbnails of 160x120,
                            // or power-of-2 downsampling), in which case we decode the image itself
                            if (isVideo || minOf(bitmap.width, bitmap.height) >= target) return thumbnail
                            val decoded = try {
                                decodeImage(uri, rotationDegrees, isFlipped, target)
                            } catch (e: IOException) {
                                throw e
                            } catch (e: Exception) {
                                Log.d(LOG_TAG, "failed to decode uri=$uri mimeType=$mimeType", e)
                                null
                            }
                            if (decoded != null && !isDegenerate(decoded.bitmap)) {
                                bitmap.recycle()
                                return decoded
                            }
                            decoded?.bitmap?.recycle()
                            return thumbnail
                        }
                        // e.g. blank thumbnails of some Pixel portrait photos: decode the media itself instead
                        Log.d(LOG_TAG, "degenerate Media Store thumbnail for uri=$uri mimeType=$mimeType")
                        bitmap.recycle()
                    }
                } else {
                    val contentId = uri.tryParseId()
                    if (contentId != null) {
                        @Suppress("DEPRECATION")
                        val bitmap = if (isVideo) {
                            MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, contentId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                        } else {
                            MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, contentId, MediaStore.Images.Thumbnails.MINI_KIND, null)
                        }
                        // before Android 10 (API 29), image thumbnails are not rotated
                        if (bitmap != null) {
                            val software = ensureSoftware(bitmap)
                            if (!isDegenerate(software)) return Result(software, if (isVideo) 0 else rotationDegrees, !isVideo && isFlipped)
                            software.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(LOG_TAG, "failed to get Media Store thumbnail for uri=$uri mimeType=$mimeType", e)
        }

        // out of memory and I/O errors are propagated, as they may not happen again
        return try {
            if (isVideo) decodeVideoFrame(uri, target) else decodeImage(uri, rotationDegrees, isFlipped, target)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Log.d(LOG_TAG, "failed to decode uri=$uri mimeType=$mimeType", e)
            null
        }
    }

    private fun decodeImage(uri: Uri, rotationDegrees: Int, isFlipped: Boolean, target: Int): Result? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decoding bounds only fills the options, and always returns null
        val input = StorageUtils.openInputStream(context, uri) ?: return null
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImagePreprocessor.sampleSizeFor(bounds.outWidth, bounds.outHeight, target)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = StorageUtils.openInputStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return Result(bitmap, rotationDegrees, isFlipped)
    }

    private fun decodeVideoFrame(uri: Uri, target: Int): Result? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val timeUs = minOf(durationMs * 1000 / 2, FRAME_TIME_US)
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // scaled frames fit within the requested size
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, target * THUMBNAIL_OVERSAMPLING, target * THUMBNAIL_OVERSAMPLING)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return null
            // this fallback only serves videos outside the Media Store, and does not apply their rotation metadata
            return Result(ensureSoftware(bitmap), 0, false)
        } finally {
            retriever.release()
        }
    }

    // blank or single color bitmaps, as returned for some media instead of an actual thumbnail
    private fun isDegenerate(bitmap: Bitmap): Boolean {
        if (bitmap.width < 2 || bitmap.height < 2) return true
        val first = bitmap.getPixel(0, 0)
        for (y in 0 until DEGENERACY_GRID) {
            for (x in 0 until DEGENERACY_GRID) {
                val pixel = bitmap.getPixel(x * (bitmap.width - 1) / (DEGENERACY_GRID - 1), y * (bitmap.height - 1) / (DEGENERACY_GRID - 1))
                if (pixel != first) return false
            }
        }
        return true
    }

    // hardware bitmaps cannot be drawn on a software canvas
    private fun ensureSoftware(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()
            return copy
        }
        return bitmap
    }

    companion object {
        private val LOG_TAG = LogUtils.createTag<MediaBitmapLoader>()
        private const val FRAME_TIME_US = 1_000_000L
        private const val THUMBNAIL_OVERSAMPLING = 4
        private const val DEGENERACY_GRID = 8
    }
}
