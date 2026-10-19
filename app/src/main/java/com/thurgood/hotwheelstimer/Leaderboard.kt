package com.thurgood.hotwheelstimer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One saved run. [photo] is a file name inside the app's private "cars" folder, or null. */
data class LeaderboardEntry(
    val id: String,
    val name: String,
    val timeSecs: Double,
    val photo: String?,
    val savedAt: Long,
)

/**
 * Saved times, kept in a small JSON file in the app's private storage, with car photos alongside.
 * Everything stays on the phone; uninstalling the app deletes it.
 */
object Leaderboard {

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "leaderboard.json"))

    fun photoDir(ctx: Context) = File(ctx.filesDir, "cars").apply { mkdirs() }

    fun photoFile(ctx: Context, photo: String?): File? =
        photo?.let { File(photoDir(ctx), it) }?.takeIf { it.exists() }

    @Synchronized
    fun load(ctx: Context): MutableList<LeaderboardEntry> {
        val f = file(ctx)
        if (!f.baseFile.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(String(f.readFully(), Charsets.UTF_8))
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                LeaderboardEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    timeSecs = o.getDouble("time"),
                    photo = o.optString("photo").ifEmpty { null },
                    savedAt = o.optLong("savedAt"),
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun write(ctx: Context, entries: List<LeaderboardEntry>) {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("time", it.timeSecs)
                put("photo", it.photo ?: "")
                put("savedAt", it.savedAt)
            })
        }
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    fun add(ctx: Context, name: String, timeSecs: Double, photo: String?) {
        val list = load(ctx)
        list.add(LeaderboardEntry(UUID.randomUUID().toString(), name.trim(), timeSecs, photo, System.currentTimeMillis()))
        write(ctx, list)
    }

    /** Deletes a run, and its photo if no other run uses it. */
    fun delete(ctx: Context, id: String) {
        val list = load(ctx)
        val gone = list.firstOrNull { it.id == id } ?: return
        list.remove(gone)
        write(ctx, list)
        if (gone.photo != null && list.none { it.photo == gone.photo }) {
            File(photoDir(ctx), gone.photo).delete()
        }
    }

    /** Car names already used, most recent first, for the name suggestions. */
    fun carNames(ctx: Context): List<String> =
        load(ctx).sortedByDescending { it.savedAt }.map { it.name }.distinctBy { it.lowercase() }

    /** The most recent photo saved for a car with this name (case-insensitive), if any. */
    fun photoForCar(ctx: Context, name: String): String? =
        load(ctx).filter { it.name.equals(name.trim(), ignoreCase = true) && it.photo != null }
            .maxByOrNull { it.savedAt }?.photo
            ?.takeIf { photoFile(ctx, it) != null }

    /** Fastest first. With [bestPerCar], only each car's quickest run is kept. */
    fun ranked(entries: List<LeaderboardEntry>, bestPerCar: Boolean): List<LeaderboardEntry> {
        val sorted = entries.sortedWith(compareBy({ it.timeSecs }, { it.savedAt }))
        return if (bestPerCar) sorted.distinctBy { it.name.lowercase() } else sorted
    }

    /**
     * Takes a full-size camera photo, shrinks it, turns it the right way up and stores it.
     * Returns the stored file name, or null if the photo couldn't be read.
     */
    fun storePhoto(ctx: Context, source: File, maxSize: Int = 720): String? {
        if (!source.exists() || source.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
        var bmp = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val degrees = when (
            runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bmp.width, bmp.height))
        if (degrees != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }

        val name = "${UUID.randomUUID()}.jpg"
        File(photoDir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return name
    }

    /** Loads a photo scaled down to roughly [targetPx] for showing in lists and previews. */
    fun loadThumb(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
