package com.calmyjane.spacebeam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.File
import kotlin.math.min

class SettingsMenu(private val activity: MainActivity, private val parentView: ViewGroup) {
    private var overlay: FrameLayout? = null
    private var confirmationOverlay: FrameLayout? = null
    private var scrollContainer: ScrollView? = null
    private var autoPlayDurationControl: PropertyControl? = null

    fun isOpen(): Boolean = overlay != null && overlay!!.parent != null

    fun getScrollY(): Int {
        return scrollContainer?.scrollY ?: 0
    }

    fun restoreScrollY(y: Int) {
        scrollContainer?.post {
            scrollContainer?.scrollTo(0, y)
        }
    }

    fun cleanup() {
        overlay?.animate()?.cancel()
        confirmationOverlay?.animate()?.cancel()

        PropertyControl.closeActiveMenu()
        autoPlayDurationControl = null

        if (overlay != null && overlay!!.parent != null) {
            parentView.removeView(overlay)
        }
        overlay = null
        confirmationOverlay = null
        scrollContainer = null
    }

    fun show() {
        if (overlay != null) {
            if (overlay!!.parent == null) {
                parentView.addView(overlay, ViewGroup.LayoutParams(-1, -1))
            }
            overlay!!.bringToFront()
            overlay!!.alpha = 1f
            return
        }

        overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            elevation = 500f
            setOnClickListener { dismiss() }
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        }

        val dm = activity.resources.displayMetrics
        val targetWidth = (min(dm.widthPixels, dm.heightPixels) * 0.9f).toInt()

        scrollContainer = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                setMargins(20, 50, 20, 50)
            }
            setOnClickListener { /* consume click */ }
            background = getPanelBackground()
            elevation = 510f
            isVerticalScrollBarEnabled = false
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 50, 40, 50)
        }

        // --- TITLE ---
        contentLayout.addView(TextView(activity).apply {
            text = "SETTINGS"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        })

        // --- GENERAL ITEMS ---
        contentLayout.addView(createStyledButton("help") {
            showHelp()
        })

        contentLayout.addView(createStyledButton("reset presets") {
            showConfirmation(
                "RESET ALL PRESETS?",
                "This cannot be undone. All saved presets in slots 1-9 will be permanently replaced with factory defaults."
            ) {
                activity.resetPresetsToDefault()
                dismiss()
            }
        })

        contentLayout.addView(createStyledButton("edit mask") {
            dismiss()
            activity.showMaskEditor()
        })

        val screenOnBtn = createStyledButton(
            if (activity.forceScreenOn) "force screen on: ON" else "force screen on: OFF"
        ) {}
        screenOnBtn.setOnClickListener {
            if (!activity.forceScreenOn) {
                if (android.provider.Settings.System.canWrite(activity)) {
                    activity.forceScreenOn = true
                    screenOnBtn.text = "force screen on: ON"
                    activity.applyForceScreenOn()
                    activity.getSharedPreferences("SpaceBeam_Settings", Context.MODE_PRIVATE)
                        .edit().putBoolean("FORCE_SCREEN_ON", true).apply()
                } else {
                    showCustomDialog(
                        "FORCE SCREEN ON",
                        "Some devices (e.g. Samsung) ignore the standard Android keep-screen-on flag. " +
                        "This option overrides the system screen timeout to prevent the display from turning off during a performance.\n\n" +
                        "The original timeout is restored when you leave the app."
                    ) { panel ->
                        val allowBtn = Button(activity).apply {
                            text = "OPEN SETTINGS"
                            setTextColor(Color.WHITE)
                            setTypeface(null, Typeface.BOLD)
                            background = GradientDrawable().apply {
                                setColor(Color.argb(255, 45, 45, 50))
                                cornerRadius = 15f
                                setStroke(2, Color.parseColor("#555555"))
                            }
                            setOnClickListener {
                                dismissConfirmation()
                                activity.forceScreenOn = true
                                screenOnBtn.text = "force screen on: ON"
                                activity.getSharedPreferences("SpaceBeam_Settings", Context.MODE_PRIVATE)
                                    .edit().putBoolean("FORCE_SCREEN_ON", true).apply()
                                activity.requestForceScreenOnPermission()
                            }
                        }
                        panel.addView(allowBtn)
                    }
                }
            } else {
                activity.forceScreenOn = false
                activity.restoreScreenTimeout()
                screenOnBtn.text = "force screen on: OFF"
                activity.getSharedPreferences("SpaceBeam_Settings", Context.MODE_PRIVATE)
                    .edit().putBoolean("FORCE_SCREEN_ON", false).apply()
            }
        }
        contentLayout.addView(screenOnBtn)

        contentLayout.addView(createStyledButton("connect bluetooth midi") {
            showMidiScanner()
        })

        contentLayout.addView(createStyledDivider())

        val bpmRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply { bottomMargin = 10 }
        }

        bpmRow.addView(TextView(activity).apply {
            text = "CURRENT BPM"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        val bpmInput = EditText(activity).apply {
            setText(activity.bpmManager.bpm.toInt().toString())
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.DKGRAY)
                cornerRadius = 10f
            }
            setPadding(20, 10, 20, 10)
            layoutParams = LinearLayout.LayoutParams(150, -2)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val newBpm = v.text.toString().toFloatOrNull()
                    if (newBpm != null && newBpm > 0) {
                        activity.bpmManager.bpm = newBpm.coerceIn(30f, 300f)
                        v.text = activity.bpmManager.bpm.toInt().toString()
                        Toast.makeText(activity, "BPM set to ${activity.bpmManager.bpm.toInt()}", Toast.LENGTH_SHORT).show()
                    }
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                    true
                } else false
            }
        }

        bpmRow.addView(bpmInput)
        contentLayout.addView(bpmRow)

        val undoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply { bottomMargin = 10 }
        }
        undoRow.addView(TextView(activity).apply {
            text = "UNDO STEPS"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        undoRow.addView(EditText(activity).apply {
            setText(activity.undoHistorySize.toString())
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.DKGRAY)
                cornerRadius = 10f
            }
            setPadding(20, 10, 20, 10)
            layoutParams = LinearLayout.LayoutParams(150, -2)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val newVal = v.text.toString().toIntOrNull()
                    if (newVal != null && newVal > 0) {
                        val clamped = newVal.coerceIn(1, 999)
                        activity.undoHistorySize = clamped
                        activity.undoManager.maxHistory = clamped
                        v.text = clamped.toString()
                    }
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                    true
                } else false
            }
        })
        contentLayout.addView(undoRow)

        // --- MIDI MAPPING SECTION (Visible only if connected) ---
        if (activity.midiHelper.isConnected) {
            contentLayout.addView(createStyledDivider())
            contentLayout.addView(TextView(activity).apply {
                text = "MIDI MAPPING"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 5)
            })

            contentLayout.addView(TextView(activity).apply {
                val displayName = if (activity.midiHelper.isModified) "Custom" else activity.midiHelper.mappingName
                text = "CURRENT: \"$displayName\""
                textSize = 12f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
            })

            val mapRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120).apply {
                    bottomMargin = 10
                }
            }

            val btnLoad = Button(activity).apply {
                text = "LOAD"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    rightMargin = 15
                }
                setOnClickListener { showLoadOptions() }
            }

            val btnSave = Button(activity).apply {
                text = "SAVE"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = getButtonBg()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = 15
                }
                setOnClickListener { showSaveOptions() }
            }

            mapRow.addView(btnLoad)
            mapRow.addView(btnSave)
            contentLayout.addView(mapRow)

            contentLayout.addView(createStyledButton("clear midi mappings...") {
                activity.showMidiClearOverlay()
                dismiss()
            })
        }

        contentLayout.addView(createStyledDivider())

        // --- AUTO-PLAY SECTION ---
        contentLayout.addView(TextView(activity).apply {
            text = "AUTO-PLAY"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        })

        // Random Toggle
        val randomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply { bottomMargin = 10 }
        }
        randomRow.addView(TextView(activity).apply {
            text = "RANDOM ORDER"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val randomCheck = CheckBox(activity).apply {
            isChecked = activity.autoPlayRandom
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnCheckedChangeListener { _, isChecked -> activity.autoPlayRandom = isChecked }
        }
        randomRow.addView(randomCheck)
        contentLayout.addView(randomRow)

        // Preset Filter Row (Grid 1-9)
        contentLayout.addView(TextView(activity).apply {
            text = "INCLUDE PRESETS:"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10; bottomMargin = 5 }
        })

        // Container for the 9 checkboxes
        val filterContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 15
            }
        }

        for (i in 1..9) {
            val cbContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val cb = CheckBox(activity).apply {
                isChecked = activity.autoPlayFilter.contains(i)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                scaleX = 0.8f; scaleY = 0.8f; setPadding(0,0,0,0)
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) activity.autoPlayFilter.add(i) else activity.autoPlayFilter.remove(i)
                    activity.updatePlayButtonState()
                }
            }
            val lbl = TextView(activity).apply { text = "$i"; textSize = 10f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
            cbContainer.addView(lbl); cbContainer.addView(cb)
            filterContainer.addView(cbContainer)
        }
        contentLayout.addView(filterContainer)

        // Auto-Play Duration
        autoPlayDurationControl = PropertyControl(
            "AUTO_DUR", "DURATION",
            min = 0, max = 300000, sliderMax = 60000,
            defaultValue = activity.autoPlayDurationMs.toInt(),
            layoutStyle = PropertyControl.LayoutStyle.ROW,
            includeInPreset = false, hasModulation = false, logPower = 2, showValue = true,
            valueFormatter = { "%.1fs".format(it / 1000f) }
        ) { activity.autoPlayDurationMs = it.toLong() }

        autoPlayDurationControl?.popupElevation = 600f
        autoPlayDurationControl?.attachTo(activity, contentLayout)

        contentLayout.addView(createStyledDivider())

        // --- SENSOR SMOOTHING ---
        contentLayout.addView(TextView(activity).apply {
            text = "SENSOR SMOOTHING"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        })

        fun addSmoothRow(name: String, getVal: () -> Int, setVal: (Int) -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
            }
            row.addView(TextView(activity).apply {
                text = name; textSize = 14f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(130, -2)
            })
            row.addView(SeekBar(activity).apply {
                max = 1000; progress = getVal()
                thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
                thumbOffset = 0
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) setVal(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
            contentLayout.addView(row)
        }
        addSmoothRow("Pitch", { activity.sensorHelper.pitchSmoothing }) { activity.sensorHelper.pitchSmoothing = it }
        addSmoothRow("Roll",  { activity.sensorHelper.rollSmoothing  }) { activity.sensorHelper.rollSmoothing  = it }
        addSmoothRow("Yaw",   { activity.sensorHelper.yawSmoothing   }) { activity.sensorHelper.yawSmoothing   = it }

        contentLayout.addView(createStyledDivider())

        // --- CLOSE ---
        contentLayout.addView(Button(activity).apply {
            text = "close"
            setTextColor(Color.LTGRAY)
            background = null
            textSize = 16f
            setPadding(0, 30, 0, 0)
            setOnClickListener { dismiss() }
        })

        // --- FOOTER ---
        contentLayout.addView(createStyledDivider())

        val footerLogoSize = (48 * activity.resources.displayMetrics.density).toInt()
        contentLayout.addView(ImageView(activity).apply {
            setImageResource(R.drawable.logo)
            setColorFilter(Color.LTGRAY, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(footerLogoSize, footerLogoSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 20
            }
        })

        contentLayout.addView(TextView(activity).apply {
            text = "Created by Calmy Jane"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        })

        fun footerRow(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 16)
                addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    setColorFilter(Color.LTGRAY)
                    layoutParams = LinearLayout.LayoutParams(64, 64).apply { rightMargin = 20 }
                })
                addView(TextView(activity).apply {
                    text = label
                    textSize = 22f
                    setTextColor(Color.LTGRAY)
                })
                setOnClickListener { onClick() }
            }
        }

        contentLayout.addView(footerRow(android.R.drawable.ic_dialog_email, "info@calmyjane.com") {
            val clip = android.content.ClipData.newPlainText("email", "info@calmyjane.com")
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(activity, "Email copied", Toast.LENGTH_SHORT).show()
        })

        contentLayout.addView(footerRow(android.R.drawable.ic_menu_view, "www.calmyjane.com") {
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.calmyjane.com")))
        })

        contentLayout.addView(footerRow(android.R.drawable.ic_menu_share, "GitHub") {
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/calmyjane/spacebeam")))
        })

        scrollContainer!!.addView(contentLayout)
        overlay!!.addView(scrollContainer)
        parentView.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        overlay!!.bringToFront()
    }

    private fun getButtonBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#333333"))
            cornerRadius = 15f
            setStroke(1, Color.GRAY)
        }
    }

    private fun showHelp() {
        Thread {
            try {
                val md = activity.assets.open("help/README.md").bufferedReader().readText()

                // Find which images the markdown actually references
                val referencedImages = Regex("!\\[[^\\]]*\\]\\(([^)]+)\\)").findAll(md)
                    .map { it.groupValues[1].substringAfterLast("/") }.toSet()

                // Load only referenced images as base64 data URIs
                val imageMap = mutableMapOf<String, String>()
                val assetFiles = activity.assets.list("help/readme_content") ?: emptyArray()
                for (name in assetFiles) {
                    if (name !in referencedImages) continue
                    val bytes = activity.assets.open("help/readme_content/$name").readBytes()
                    val ext = name.substringAfterLast('.').lowercase()
                    val mime = when (ext) {
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "svg" -> "image/svg+xml"
                        else -> "image/png"
                    }
                    imageMap[name] = "data:$mime;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
                }

                inlineImageMap = imageMap
                val html = markdownToHtml(md)
                inlineImageMap = null

                val helpDir = File(activity.cacheDir, "help")
                helpDir.mkdirs()
                val htmlFile = File(helpDir, "help.html")
                htmlFile.writeText(html)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    activity, "${activity.packageName}.fileprovider", htmlFile
                )
                activity.runOnUiThread {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "text/html")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { activity.startActivity(intent) }
                    catch (e: Exception) {
                        Toast.makeText(activity, "No browser found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Help not available — rebuild app", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun markdownToHtml(md: String): String {
        val sb = StringBuilder()
        var inCodeBlock = false
        var inTable = false
        var tableHeaderDone = false
        var inList = false
        var listOrdered = false

        for (rawLine in md.lines()) {
            val line = rawLine.trimEnd()

            // Code blocks
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) { sb.append("</code></pre>"); inCodeBlock = false }
                else { sb.append("<pre><code>"); inCodeBlock = true }
                continue
            }
            if (inCodeBlock) { sb.append(escapeHtml(line)).append("\n"); continue }

            // Close lists if needed
            if (inList && !line.startsWith("- ") && !line.startsWith("  -") && !line.matches(Regex("^\\d+\\.\\s.*"))) {
                sb.append(if (listOrdered) "</ol>" else "</ul>"); inList = false
            }

            // Close table if needed
            if (inTable && !line.startsWith("|")) { sb.append("</table>"); inTable = false; tableHeaderDone = false }

            // Blank line — skip, spacing handled by CSS margins
            if (line.isBlank()) continue

            // Horizontal rule
            if (line.matches(Regex("^-{3,}$"))) { sb.append("<hr>"); continue }

            // Headers
            if (line.startsWith("#")) {
                val level = line.takeWhile { it == '#' }.length.coerceAtMost(6)
                val text = inline(line.drop(level).trim())
                sb.append("<h$level>$text</h$level>")
                continue
            }

            // Table
            if (line.startsWith("|")) {
                if (line.replace("|", "").replace("-", "").replace(" ", "").isBlank()) continue // separator row
                if (!inTable) { sb.append("<table>"); inTable = true; tableHeaderDone = false }
                val tag = if (!tableHeaderDone) { tableHeaderDone = true; "th" } else "td"
                sb.append("<tr>")
                line.trim('|').split("|").forEach { cell ->
                    sb.append("<$tag>${inline(cell.trim())}</$tag>")
                }
                sb.append("</tr>")
                continue
            }

            // Unordered list
            if (line.startsWith("- ") || line.startsWith("  -")) {
                if (!inList || listOrdered) {
                    if (inList) sb.append("</ol>")
                    sb.append("<ul>"); inList = true; listOrdered = false
                }
                sb.append("<li>${inline(line.trimStart().removePrefix("- ").trim())}</li>")
                continue
            }

            // Ordered list
            val olMatch = Regex("^(\\d+)\\.\\s(.*)").find(line)
            if (olMatch != null) {
                if (!inList || !listOrdered) {
                    if (inList) sb.append("</ul>")
                    sb.append("<ol>"); inList = true; listOrdered = true
                }
                sb.append("<li>${inline(olMatch.groupValues[2])}</li>")
                continue
            }

            // Paragraph
            sb.append("<p>${inline(line)}</p>")
        }

        if (inCodeBlock) sb.append("</code></pre>")
        if (inList) sb.append(if (listOrdered) "</ol>" else "</ul>")
        if (inTable) sb.append("</table>")

        return """<!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
body { background: #1a1a1a; color: #ddd; font-family: -apple-system, sans-serif; padding: 16px; line-height: 1.4; }
p { margin: 4px 0; }
h1 { color: #fff; border-bottom: 1px solid #444; padding-bottom: 6px; margin: 16px 0 8px; }
h2 { color: #eee; border-bottom: 1px solid #333; padding-bottom: 4px; margin: 20px 0 6px; }
h3 { color: #ccc; margin: 14px 0 4px; }
a { color: #6cacff; }
img { max-width: 100%; border-radius: 8px; margin: 12px 0; }
code { background: #2a2a2a; padding: 2px 6px; border-radius: 4px; font-size: 0.9em; }
pre { background: #2a2a2a; padding: 12px; border-radius: 8px; overflow-x: auto; }
pre code { padding: 0; background: none; }
table { border-collapse: collapse; width: 100%; margin: 12px 0; }
th, td { border: 1px solid #444; padding: 8px 12px; text-align: left; }
th { background: #2a2a2a; color: #fff; }
hr { border: none; border-top: 1px solid #444; margin: 24px 0; }
blockquote { border-left: 3px solid #555; padding-left: 12px; color: #aaa; }
li { margin: 4px 0; }
</style></head><body>${sb}</body></html>"""
    }

    private var inlineImageMap: MutableMap<String, String>? = null

    private fun inline(text: String): String {
        var s = escapeHtml(text)
        // Images: ![alt](src)
        s = s.replace(Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")) { m ->
            val rawSrc = m.groupValues[2]
            val fileName = rawSrc.substringAfterLast("/")
            val src = inlineImageMap?.get(fileName) ?: rawSrc
            "<img src=\"$src\" alt=\"${m.groupValues[1]}\">"
        }
        // Links: [text](url)
        s = s.replace(Regex("\\[([^\\]]*)\\]\\(([^)]+)\\)")) { m ->
            "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
        }
        // Bold: **text**
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
        // Italic: *text*
        s = s.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")) { "<em>${it.groupValues[1]}</em>" }
        // Inline code: `text`
        s = s.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
        return s
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun showSaveOptions() {
        showCustomDialog("SAVE MAPPING", "Select destination:") { container ->
            container.addView(createStyledButton("save to file") {
                val json = activity.midiHelper.exportConfig()
                val safeName = activity.midiHelper.mappingName.replace("[^a-zA-Z0-9]".toRegex(), "_")
                activity.saveMidiMappingToFile("MidiMap_$safeName.json", json)
                dismissConfirmation()
            })

            container.addView(createStyledButton("copy to clipboard") {
                val json = activity.midiHelper.exportConfig()
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("SpaceBeam Map", json)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, "Copied to Clipboard", Toast.LENGTH_SHORT).show()
                dismissConfirmation()
            })
        }
    }

    private fun showLoadOptions() {
        showCustomDialog("LOAD MAPPING", "Select source:") { container ->
            container.addView(createStyledButton("factory default") {
                activity.midiHelper.loadDefault()
                dismissConfirmation()
                dismiss()
            })

            container.addView(createStyledButton("load from file") {
                activity.loadMappingLauncher.launch(arrayOf("application/json"))
                dismissConfirmation()
                dismiss() // Close settings to refresh name
            })

            container.addView(createStyledButton("ENTER TEXT") {
                dismissConfirmation()
                // Use the new fullscreen dialog method
                showPasteDialog()
            })
        }
    }

    private fun showPasteDialog() {
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        // Root container (Black background)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
        }

        // Close Button (Top Right)
        val closeBtn = Button(activity).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.GRAY)
            background = null
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(150, 150).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 30
                rightMargin = 30
            }
            setOnClickListener {
                dialog.dismiss()
                activity.hideSystemUI()
            }
        }

        // Content Container (Centered)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                leftMargin = 50
                rightMargin = 50
            }
        }

        // Title
        panel.addView(TextView(activity).apply {
            text = "ENTER TEXT"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        // Input Field (Big, Multi-line)
        val inputObj = EditText(activity).apply {
            hint = "Paste JSON code here..."
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.DKGRAY)
                cornerRadius = 15f
            }
            // Multi-line configuration
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600) // Taller height
            setHorizontallyScrolling(false)
            minLines = 10
        }
        panel.addView(inputObj)

        // Buttons Row
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2).apply { topMargin = 40 }
        }

        val pasteBtn = Button(activity).apply {
            text = "PASTE"
            setTextColor(Color.BLACK)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.LTGRAY); cornerRadius = 15f }
            layoutParams = LinearLayout.LayoutParams(0, 120, 1f).apply { rightMargin = 20 }
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                if (clipboard.hasPrimaryClip()) {
                    val text = clipboard.primaryClip?.getItemAt(0)?.text
                    if (text != null) inputObj.setText(text)
                }
            }
        }

        val loadBtn = Button(activity).apply {
            text = "LOAD"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0066CC")); cornerRadius = 15f }
            layoutParams = LinearLayout.LayoutParams(0, 120, 1f).apply { leftMargin = 20 }
            setOnClickListener {
                val txt = inputObj.text.toString()
                if (txt.isNotEmpty()) {
                    val success = activity.midiHelper.importConfig(txt)
                    if (success) {
                        Toast.makeText(activity, "Loaded: ${activity.midiHelper.mappingName}", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        dismiss() // Close the underlying settings menu
                        activity.hideSystemUI()
                    } else {
                        Toast.makeText(activity, "Invalid Mapping Data", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnRow.addView(pasteBtn)
        btnRow.addView(loadBtn)
        panel.addView(btnRow)

        root.addView(panel)
        root.addView(closeBtn)

        dialog.setContentView(root)
        dialog.setOnDismissListener { activity.hideSystemUI() }
        // Force soft keyboard mode for visibility
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun showMidiScanner() {
        val scanOverlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(230, 0, 0, 0))
            elevation = 600f
            isClickable = true
            isFocusable = true
        }

        scanOverlay.setOnClickListener {
            activity.midiHelper.stopLeScan()
            this@SettingsMenu.overlay?.removeView(scanOverlay)
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(800, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = getPanelBackground()
            setPadding(40,40,40,40)
            isClickable = true
            setOnClickListener { }
        }

        content.addView(TextView(activity).apply { text = "SCANNING FOR MIDI..."; setTextColor(Color.WHITE); textSize=18f; gravity=Gravity.CENTER })

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 600)
            setPadding(0, 20, 0, 20)
        }
        val scroller = ScrollView(activity).apply { addView(listContainer) }
        content.addView(scroller)

        val foundMacs = mutableSetOf<String>()

        activity.midiHelper.onDeviceFound = { device ->
            activity.runOnUiThread {
                if (!foundMacs.contains(device.address)) {
                    foundMacs.add(device.address)
                    val btn = Button(activity).apply {
                        val dName = if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) device.name else "Unknown"
                        text = "${dName ?: "Unknown"}\n${device.address}"

                        setTextColor(Color.LTGRAY)
                        textSize = 12f
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#333333"))
                            setStroke(1, Color.DKGRAY)
                            cornerRadius = 8f
                        }
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin=10 }
                        setOnClickListener {
                            activity.midiHelper.connectToDevice(device)
                            this@SettingsMenu.overlay?.removeView(scanOverlay)
                            // Refresh menu to show mapping section
                            dismiss()
                            show()
                        }
                    }
                    listContainer.addView(btn)
                }
            }
        }

        content.addView(Button(activity).apply {
            text = "CANCEL"
            setTextColor(Color.WHITE)
            background = null
            setOnClickListener {
                activity.midiHelper.stopLeScan()
                this@SettingsMenu.overlay?.removeView(scanOverlay)
            }
        })

        scanOverlay.addView(content)
        overlay?.addView(scanOverlay, ViewGroup.LayoutParams(-1,-1))

        activity.midiHelper.startLeScan()
    }

    private fun showCustomDialog(title: String, subtitle: String, contentFiller: (LinearLayout) -> Unit) {
        if (confirmationOverlay != null) return

        confirmationOverlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            isClickable = true
            elevation = 550f
            setOnClickListener { dismissConfirmation() }
            alpha = 0f
            animate().alpha(1f).setDuration(150).start()
        }

        val dm = activity.resources.displayMetrics
        val targetWidth = (min(dm.widthPixels, dm.heightPixels) * 0.85f).toInt()

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = getPanelBackground()
            setPadding(40, 40, 40, 40)
            setOnClickListener { }
        }

        panel.addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(activity).apply {
            text = subtitle
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 10, 0, 30)
        })

        contentFiller(panel)

        val cancelBtn = Button(activity).apply {
            text = "CANCEL"
            setTextColor(Color.GRAY)
            background = null
            setOnClickListener { dismissConfirmation() }
        }
        panel.addView(cancelBtn)

        confirmationOverlay!!.addView(panel)
        overlay!!.addView(confirmationOverlay, ViewGroup.LayoutParams(-1, -1))
    }

    fun dismiss() {
        overlay?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            cleanup()
        }?.start()
    }

    private fun showConfirmation(titleStr: String, messageStr: String, onConfirm: () -> Unit) {
        if (confirmationOverlay != null) return

        confirmationOverlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            isClickable = true
            elevation = 520f
            setOnClickListener { dismissConfirmation() }
            alpha = 0f
            animate().alpha(1f).setDuration(150).start()
        }

        val dm = activity.resources.displayMetrics
        val targetWidth = (min(dm.widthPixels, dm.heightPixels) * 0.85f).toInt()

        val dialogPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = getPanelBackground()
            setPadding(50, 50, 50, 50)
            setOnClickListener { /* consume click */ }
            elevation = 530f
        }

        dialogPanel.addView(TextView(activity).apply {
            text = titleStr
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.RED)
            gravity = Gravity.CENTER
        })

        dialogPanel.addView(TextView(activity).apply {
            text = messageStr
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 50)
        })

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val cancelBtn = Button(activity).apply {
            text = "CANCEL"
            setTextColor(Color.LTGRAY)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { dismissConfirmation() }
        }

        val confirmBtn = Button(activity).apply {
            text = "CONFIRM"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA2200"))
                cornerRadius = 15f
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 20 }
            setOnClickListener {
                onConfirm()
                dismissConfirmation()
            }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(confirmBtn)
        dialogPanel.addView(buttonRow)

        confirmationOverlay!!.addView(dialogPanel)
        overlay!!.addView(confirmationOverlay, ViewGroup.LayoutParams(-1, -1))
        confirmationOverlay!!.bringToFront()
    }

    private fun dismissConfirmation() {
        confirmationOverlay?.animate()?.alpha(0f)?.setDuration(100)?.withEndAction {
            overlay?.removeView(confirmationOverlay)
            confirmationOverlay = null
        }?.start()
    }

    private fun getPanelBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.argb(240, 28, 28, 30))
            cornerRadius = 30f
            setStroke(3, Color.argb(150, 70, 70, 70))
        }
    }

    private fun createStyledButton(textStr: String, action: () -> Unit): Button {
        return Button(activity).apply {
            text = textStr
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 8, 0, 8)
            }
            setPadding(20, 12, 20, 12)
            minHeight = 0
            minimumHeight = 0
            background = GradientDrawable().apply {
                setColor(Color.argb(255, 45, 45, 50))
                cornerRadius = 15f
                setStroke(2, Color.parseColor("#555555"))
            }
            elevation = 10f
            setOnClickListener { action() }

            // Force the scroll view to let this click through
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }

    private fun createStyledDivider(): View {
        return View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(20, 25, 20, 25)
            }
            setBackgroundColor(Color.argb(50, 255, 255, 255))
        }
    }
}

