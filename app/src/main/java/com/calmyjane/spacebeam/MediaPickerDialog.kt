package com.calmyjane.spacebeam

import android.content.ContentUris
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.view.*
import android.widget.*
import android.util.Log
import java.util.concurrent.Executors

class MediaPickerDialog(
    private val activityContext: MainActivity,
    private val onMediaSelected: (List<android.net.Uri>) -> Unit
) {
    companion object {
        private val thumbnailCache = LruCache<String, Bitmap>(200)
        private val executor = Executors.newFixedThreadPool(4)
    }

    private var dialog: android.app.Dialog? = null
    private var gridView: GridView? = null
    private var confirmBtn: Button? = null
    private var pathTextView: TextView? = null
    private var loadingSpinner: ProgressBar? = null

    data class MediaEntry(val id: Long, val uri: android.net.Uri, val isVideo: Boolean, val durationStr: String, val dateAdded: Long, val path: String)

    class VDir(val name: String, val path: String, val parent: VDir?) {
        val subDirs = mutableMapOf<String, VDir>()
        val files = mutableListOf<MediaEntry>()
    }

    private val rootDir = VDir("Device", "", null)
    private var currentDir: VDir = rootDir

    private var displayItems = mutableListOf<Any>()
    private var filterMode = 0
    private val selectedUris = mutableSetOf<android.net.Uri>()

    private class MediaViewHolder(
        val container: FrameLayout,
        val img: ImageView,
        val dur: TextView,
        val sel: View,
        val chk: TextView,
        val folderLayout: LinearLayout,
        val folderIcon: TextView,
        val folderName: TextView
    )

    fun show() {
        dialog = android.app.Dialog(activityContext, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val rootLayout = FrameLayout(activityContext).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val mainLayout = LinearLayout(activityContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 40, 30, 30)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        val header = LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 15 }
        }

        header.addView(TextView(activityContext).apply {
            text = "BROWSER"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 20 }
        })

        fun createFilterBtn(textStr: String, mode: Int): Button {
            return Button(activityContext).apply {
                text = textStr
                textSize = 10f
                includeFontPadding = false
                setPadding(30, 15, 30, 15)
                setTextColor(if (filterMode == mode) Color.BLACK else Color.LTGRAY)
                background = GradientDrawable().apply {
                    setColor(if (filterMode == mode) Color.WHITE else Color.parseColor("#252525"))
                    cornerRadius = 15f
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 15 }
                setOnClickListener {
                    if (filterMode != mode) {
                        filterMode = mode
                        updateFilterButtons(header)
                        refreshGrid()
                    }
                }
            }
        }

        val filtersContainer = LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        filtersContainer.addView(createFilterBtn("ALL", 0))
        filtersContainer.addView(createFilterBtn("IMG", 1))
        filtersContainer.addView(createFilterBtn("VID", 2))
        header.addView(filtersContainer)
        mainLayout.addView(header)

        pathTextView = TextView(activityContext).apply {
            text = "Device > ..."
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 }
        }
        mainLayout.addView(pathTextView)

        val gridContainer = FrameLayout(activityContext).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        gridView = GridView(activityContext).apply {
            val dm = activityContext.resources.displayMetrics
            numColumns = if (dm.heightPixels > dm.widthPixels) 4 else 7
            verticalSpacing = 15
            horizontalSpacing = 15
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            adapter = MediaAdapter()
            setOnItemClickListener { _, _, position, _ ->
                when (val item = displayItems[position]) {
                    is String -> {
                        currentDir = currentDir.parent ?: rootDir
                        refreshGrid()
                    }
                    is VDir -> {
                        currentDir = item
                        refreshGrid()
                    }
                    is MediaEntry -> {
                        if (selectedUris.contains(item.uri)) selectedUris.remove(item.uri) else selectedUris.add(item.uri)
                        (adapter as BaseAdapter).notifyDataSetChanged()
                        updateConfirmButton()
                    }
                }
            }
        }
        gridContainer.addView(gridView)

        loadingSpinner = ProgressBar(activityContext).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }
        gridContainer.addView(loadingSpinner)
        mainLayout.addView(gridContainer)

        val footer = LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 140).apply { topMargin = 20 }
        }

        footer.addView(Button(activityContext).apply {
            text = "CANCEL"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 20f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = 15 }
            setOnClickListener { dialog?.dismiss() }
        })

        confirmBtn = Button(activityContext).apply {
            text = "ADD"
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#444444")); cornerRadius = 20f }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = 15 }
            isEnabled = false
            setOnClickListener {
                onMediaSelected(selectedUris.toList())
                dialog?.dismiss()
            }
        }
        footer.addView(confirmBtn)
        mainLayout.addView(footer)

        rootLayout.addView(mainLayout)
        dialog?.setContentView(rootLayout)
        dialog?.setOnDismissListener {
            activityContext.hideSystemUI()
        }
        dialog?.show()

        loadFileSystem()
    }

    private fun updateFilterButtons(header: LinearLayout) {
        val container = header.getChildAt(1) as LinearLayout
        for (i in 0..2) {
            val btn = container.getChildAt(i) as Button
            val isActive = filterMode == i
            btn.setTextColor(if (isActive) Color.BLACK else Color.LTGRAY)
            (btn.background as GradientDrawable).setColor(if (isActive) Color.WHITE else Color.parseColor("#252525"))
        }
    }

    private fun updateConfirmButton() {
        confirmBtn?.text = if (selectedUris.isEmpty()) "ADD" else "ADD (${selectedUris.size})"
        confirmBtn?.isEnabled = selectedUris.isNotEmpty()
        (confirmBtn?.background as GradientDrawable).setColor(
            if (selectedUris.isNotEmpty()) Color.parseColor("#0066CC") else Color.parseColor("#444444")
        )
    }

    private fun loadFileSystem() {
        executor.execute {
            val allMedia = mutableListOf<MediaEntry>()
            val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            fun query(coll: android.net.Uri, isVid: Boolean) {
                val projList = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
                if (isQ) {
                    projList.add(MediaStore.MediaColumns.RELATIVE_PATH)
                    projList.add(MediaStore.MediaColumns.DISPLAY_NAME)
                } else {
                    projList.add(MediaStore.MediaColumns.DATA)
                }
                if (isVid) projList.add(MediaStore.Video.Media.DURATION)

                try {
                    activityContext.contentResolver.query(coll, projList.toTypedArray(), null, null, null)?.use { c ->
                        val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                        val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                        val durCol = if (isVid) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

                        val relPathCol = if (isQ) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                        val nameCol = if (isQ) c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME) else -1
                        val dataCol = if (!isQ) c.getColumnIndex(MediaStore.MediaColumns.DATA) else -1

                        while (c.moveToNext()) {
                            if (idCol == -1) continue
                            val id = c.getLong(idCol)

                            val rawPath = if (isQ) {
                                val rp = if (relPathCol >= 0) c.getString(relPathCol) ?: "" else ""
                                val nm = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                                "Storage/" + rp.trim('/') + "/" + nm
                            } else {
                                c.getString(dataCol) ?: continue
                            }

                            var dStr = ""
                            if (isVid && durCol >= 0) {
                                val ms = c.getLong(durCol)
                                dStr = String.format("%d:%02d", (ms/60000), (ms%60000)/1000)
                            }
                            val dateAdded = if (dateCol >= 0) c.getLong(dateCol) else 0L
                            allMedia.add(MediaEntry(id, ContentUris.withAppendedId(coll, id), isVid, dStr, dateAdded, rawPath))
                        }
                    }
                } catch (e: Exception) { Log.e("MediaPicker", "Error querying", e) }
            }

            query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)

            rootDir.subDirs.clear()
            rootDir.files.clear()

            for (entry in allMedia) {
                val cleanPath = entry.path.replace("//", "/")
                val parts = cleanPath.split("/").filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                val folderParts = parts.dropLast(1)

                var curr = rootDir
                var curPath = ""
                for (part in folderParts) {
                    curPath += "/$part"
                    curr = curr.subDirs.getOrPut(part) { VDir(part, curPath, curr) }
                }
                curr.files.add(entry)
            }

            var start = rootDir
            while (start.subDirs.size == 1 && start.files.isEmpty()) {
                start = start.subDirs.values.first()
            }
            currentDir = start

            activityContext.runOnUiThread {
                loadingSpinner?.visibility = View.GONE
                refreshGrid()
            }
        }
    }

    private fun refreshGrid() {
        displayItems.clear()

        var bc = currentDir.path.replace("/", " > ")
        if (bc.startsWith(" > ")) bc = bc.substring(3)
        pathTextView?.text = if (bc.isEmpty()) "Device" else "Device > $bc"

        if (currentDir.parent != null) {
            displayItems.add("UP")
        }

        displayItems.addAll(currentDir.subDirs.values.sortedBy { it.name.lowercase() })

        val filteredFiles = currentDir.files.filter { entry ->
            when (filterMode) {
                1 -> !entry.isVideo
                2 -> entry.isVideo
                else -> true
            }
        }.sortedByDescending { it.dateAdded }

        displayItems.addAll(filteredFiles)
        (gridView?.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    inner class MediaAdapter : BaseAdapter() {
        override fun getCount() = displayItems.size
        override fun getItem(position: Int) = displayItems[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: MediaViewHolder
            if (convertView == null) {
                val dm = activityContext.resources.displayMetrics
                val cols = if (activityContext.resources.configuration.orientation == 1) 4 else 7
                val size = dm.widthPixels / cols

                val container = FrameLayout(activityContext).apply {
                    layoutParams = AbsListView.LayoutParams(-1, size)
                    setPadding(5, 5, 5, 5)
                }

                val img = ImageView(activityContext).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#222222")); cornerRadius = 15f }
                    clipToOutline = true
                }
                container.addView(img)

                val dur = TextView(activityContext).apply {
                    textSize = 10f; setTextColor(Color.WHITE); setPadding(10, 4, 10, 4)
                    background = GradientDrawable().apply { setColor(Color.argb(180, 0, 0, 0)); cornerRadius = 8f }
                    layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(15, 15, 15, 15) }
                }
                container.addView(dur)

                val sel = View(activityContext).apply {
                    background = GradientDrawable().apply { setColor(Color.argb(100, 0, 120, 255)); cornerRadius = 15f }
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                }
                container.addView(sel)

                val chk = TextView(activityContext).apply {
                    text = "✓"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    background = GradientDrawable().apply { setColor(Color.parseColor("#0066CC")); shape = GradientDrawable.OVAL; setStroke(3, Color.WHITE) }
                    layoutParams = FrameLayout.LayoutParams(60, 60, Gravity.TOP or Gravity.END).apply { setMargins(15, 15, 15, 15) }
                }
                container.addView(chk)

                val folderLayout = LinearLayout(activityContext).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { setColor(Color.parseColor("#2A2A2A")); cornerRadius = 15f }
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                }
                val folderIcon = TextView(activityContext).apply {
                    text = "📁"; textSize = 40f; gravity = Gravity.CENTER
                }
                val folderName = TextView(activityContext).apply {
                    textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(10, 5, 10, 0)
                }
                folderLayout.addView(folderIcon); folderLayout.addView(folderName)
                container.addView(folderLayout)

                holder = MediaViewHolder(container, img, dur, sel, chk, folderLayout, folderIcon, folderName)
                container.tag = holder
            } else {
                holder = convertView.tag as MediaViewHolder
            }

            val item = displayItems[position]

            holder.img.visibility = View.GONE
            holder.dur.visibility = View.GONE
            holder.sel.visibility = View.GONE
            holder.chk.visibility = View.GONE
            holder.folderLayout.visibility = View.GONE

            when (item) {
                is String -> {
                    holder.folderLayout.visibility = View.VISIBLE
                    holder.folderLayout.background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = 15f }
                    holder.folderIcon.text = "🔙"
                    holder.folderName.text = "Back"
                }
                is VDir -> {
                    holder.folderLayout.visibility = View.VISIBLE
                    holder.folderLayout.background = GradientDrawable().apply { setColor(Color.parseColor("#2A2A2A")); cornerRadius = 15f }
                    holder.folderIcon.text = "📁"
                    holder.folderName.text = item.name
                }
                is MediaEntry -> {
                    holder.img.visibility = View.VISIBLE
                    if (item.isVideo) {
                        holder.dur.visibility = View.VISIBLE
                        holder.dur.text = item.durationStr
                    }

                    val isSelected = selectedUris.contains(item.uri)
                    if (isSelected) {
                        holder.sel.visibility = View.VISIBLE
                        holder.chk.visibility = View.VISIBLE
                    }

                    val uriKey = item.uri.toString()
                    holder.img.setImageBitmap(null)
                    holder.img.tag = uriKey

                    val cached = thumbnailCache.get(uriKey)
                    if (cached != null) {
                        holder.img.setImageBitmap(cached)
                    } else {
                        executor.execute {
                            try {
                                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    activityContext.contentResolver.loadThumbnail(item.uri, android.util.Size(300, 300), null)
                                } else null
                                if (bmp != null) {
                                    thumbnailCache.put(uriKey, bmp)
                                    activityContext.runOnUiThread {
                                        if (holder.img.tag == uriKey) holder.img.setImageBitmap(bmp)
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
            return holder.container
        }
    }
}



