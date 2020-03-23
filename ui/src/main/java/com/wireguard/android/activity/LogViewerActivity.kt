/*
 * Copyright © 2020 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.activity

import android.content.Intent
import android.graphics.Typeface.BOLD
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.wireguard.android.R
import com.wireguard.android.databinding.LogViewerActivityBinding
import com.wireguard.android.util.resolveAttribute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

class LogViewerActivity: AppCompatActivity() {

    private lateinit var logAdapter: LogEntryAdapter
    private var logLines = arrayListOf<LogLine>()
    private var rawLogLines = arrayListOf<String>()
    private var recyclerView: RecyclerView? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val year by lazy {
        val yearFormatter: DateFormat = SimpleDateFormat("yyyy", Locale.US)
        yearFormatter.format(Date())
    }
    private val defaultColor by lazy { resolveAttribute(android.R.attr.textColorPrimary) }
    @Suppress("Deprecation") private val errorColor by lazy { resources.getColor(R.color.error_tag_color) }
    @Suppress("Deprecation") private val infoColor by lazy { resources.getColor(R.color.info_tag_color) }
    @Suppress("Deprecation") private val warningColor by lazy { resources.getColor(R.color.warning_tag_color) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = LogViewerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        logAdapter = LogEntryAdapter()
        binding.recyclerView.apply {
            recyclerView = this
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
            addItemDecoration(DividerItemDecoration(context, LinearLayoutManager.VERTICAL))
        }

        coroutineScope.launch { streamingLog() }

        binding.shareFab.setOnClickListener {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, rawLogLines.joinToString("\n"))
                putExtra(Intent.EXTRA_TITLE, "wireguard-log.txt")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, null))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else
            super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }

    private suspend fun streamingLog() = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder().command("logcat", "-b", "all", "-v", "threadtime", "*:V")
        builder.environment()["LC_ALL"] = "C"
        val process = try {
            builder.start()
        } catch (e: IOException) {
            e.printStackTrace()
            return@withContext
        }
        val stdout = BufferedReader(InputStreamReader(process!!.inputStream, StandardCharsets.UTF_8))
        while(true) {
            val line = stdout.readLine() ?: break
            val logLine = parseLine(line)
            if (logLine != null) {
                withContext(Dispatchers.Main) {
                    recyclerView?.let {
                        val shouldScroll = it.canScrollVertically(1)
                        rawLogLines.add(line)
                        logLines.add(logLine)
                        logAdapter.notifyDataSetChanged()
                        if (!shouldScroll)
                            it.scrollToPosition(logLines.size - 1)
                    }
                }
            }
        }
    }

    private fun parseTime(timeStr: String): Date? {
        val formatter: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return try {
            formatter.parse("$year-$timeStr")
        } catch (e: ParseException) {
            null
        }
    }

    private fun parseLine(line: String): LogLine? {
        val m: Matcher = THREADTIME_LINE.matcher(line)
        return if (m.matches()) {
            LogLine(m.group(2)!!.toInt(), m.group(3)!!.toInt(), parseTime(m.group(1)!!), m.group(4)!!, m.group(5)!!, m.group(6)!!)
        } else {
            null
        }
    }

    private data class LogLine(val pid: Int, val tid: Int, val time: Date?, val level: String, val tag: String, val msg: String)

    companion object {
        /**
         * Match a single line of `logcat -v threadtime`, such as:
         *
         * <pre>05-26 11:02:36.886 5689 5689 D AndroidRuntime: CheckJNI is OFF.</pre>
         */
        private val THREADTIME_LINE: Pattern = Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3})(?:\\s+[0-9A-Za-z]+)?\\s+(\\d+)\\s+(\\d+)\\s+([A-Z])\\s+(.+?)\\s*: (.*)$")
    }

    private inner class LogEntryAdapter : RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {

        private inner class ViewHolder(val layout: View, var isSingleLine: Boolean = true) : RecyclerView.ViewHolder(layout)

        private fun levelToColor(level: String): Int {
            return when (level) {
                "E" -> errorColor
                "W" -> warningColor
                "I" -> infoColor
                else -> defaultColor
            }
        }

        override fun getItemCount() = logLines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.log_viewer_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val line = logLines[position]
            val spannable = if (position > 0 && logLines[position - 1].tag == line.tag)
                SpannableString(line.msg)
            else
                SpannableString("${line.tag}: ${line.msg}").apply {
                    setSpan(StyleSpan(BOLD), 0, "${line.tag}:".length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(levelToColor(line.level)),
                            0, "${line.tag}:".length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            holder.layout.apply {
                findViewById<MaterialTextView>(R.id.log_date).text = line.time.toString()
                findViewById<MaterialTextView>(R.id.log_msg).apply {
                    setSingleLine()
                    text = spannable
                    setOnClickListener {
                        isSingleLine = !holder.isSingleLine
                        holder.isSingleLine = !holder.isSingleLine
                    }
                }
            }
        }
    }
}
