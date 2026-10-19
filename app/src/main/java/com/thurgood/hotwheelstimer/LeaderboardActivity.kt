package com.thurgood.hotwheelstimer

import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.thurgood.hotwheelstimer.databinding.ActivityLeaderboardBinding
import com.thurgood.hotwheelstimer.databinding.ItemLeaderboardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows saved times, fastest first. Toggle between each car's best run and every saved run.
 * Tap a row to see the car's photo bigger, or to delete that run.
 */
class LeaderboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLeaderboardBinding
    private var rows: List<LeaderboardEntry> = emptyList()
    private var bestPerCar = true
    private val dateFormat = SimpleDateFormat("d MMM, HH:mm", Locale.UK)
    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLeaderboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterFullScreen(binding.root)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        bestPerCar = prefs.getBoolean("bestPerCar", true)

        binding.backButton.setOnClickListener { finish() }
        binding.modeButton.setOnClickListener {
            bestPerCar = !bestPerCar
            prefs.edit().putBoolean("bestPerCar", bestPerCar).apply()
            refresh()
        }
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, pos, _ -> showDetails(rows[pos], pos + 1) }
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun refresh() {
        rows = Leaderboard.ranked(Leaderboard.load(this), bestPerCar)
        binding.modeButton.text = if (bestPerCar) "Best per car" else "All runs"
        binding.list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun rankLabel(rank: Int) = when (rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> rank.toString()
    }

    private fun formatTime(s: Double) = String.format(Locale.UK, "%.3f s", s)

    private fun bindPhoto(entry: LeaderboardEntry, image: ImageView, placeholder: View, sizePx: Int) {
        val file = Leaderboard.photoFile(this, entry.photo)
        val key = "${entry.photo}@$sizePx"
        val bmp = file?.let { thumbs.get(key) ?: Leaderboard.loadThumb(it, sizePx)?.also { b -> thumbs.put(key, b) } }
        image.setImageBitmap(bmp)
        placeholder.visibility = if (bmp == null) View.VISIBLE else View.GONE
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = convertView?.tag as? ItemLeaderboardBinding
                ?: ItemLeaderboardBinding.inflate(layoutInflater, parent, false).also { it.root.tag = it }
            val e = rows[position]
            b.rank.text = rankLabel(position + 1)
            b.name.text = e.name
            val date = dateFormat.format(Date(e.savedAt))
            b.date.text = e.distanceM?.let { d ->
                String.format(Locale.UK, "%s · 🚀 %.0f scale mph", date, Speed.scaleMph(d, e.timeSecs))
            } ?: date
            b.time.text = formatTime(e.timeSecs)
            bindPhoto(e, b.photo, b.noPhoto, 160)
            return b.root
        }
    }

    private fun showDetails(e: LeaderboardEntry, rank: Int) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        Leaderboard.photoFile(this, e.photo)?.let { Leaderboard.loadThumb(it, 720) }?.let { bmp ->
            content.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (240 * density).toInt()))
        }
        content.addView(TextView(this).apply {
            text = "${rankLabel(rank)}  ${formatTime(e.timeSecs)}\n${dateFormat.format(Date(e.savedAt))}" +
                (e.distanceM?.let { "\n\n" + Speed.detailed(it, e.timeSecs) } ?: "")
            textSize = 18f
            setPadding(0, pad / 2, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setView(content)
            .setPositiveButton("Close", null)
            .setNegativeButton("Delete this run") { _, _ -> confirmDelete(e) }
            .show()
    }

    private fun confirmDelete(e: LeaderboardEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete this run?")
            .setMessage("${e.name} – ${formatTime(e.timeSecs)}\n\nThis can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                Leaderboard.delete(this, e.id)
                thumbs.evictAll()
                refresh()
            }
            .setNegativeButton("Keep", null)
            .show()
    }
}
