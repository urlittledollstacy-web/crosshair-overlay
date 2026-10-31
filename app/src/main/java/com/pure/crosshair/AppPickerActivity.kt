package com.pure.crosshair

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pure.crosshair.databinding.ActivityAppPickerBinding
import com.pure.crosshair.databinding.RowAppBinding

/**
 * Picks the apps the crosshair should appear for.
 *
 * A full screen rather than a dialog, because a phone carries a few hundred launchable apps and
 * the list needs search and room to scroll. Selections are written back as they are tapped, so
 * there is no Save button to forget and no half applied state if the screen is killed.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppAdapter

    private var allApps: List<AppEntry> = emptyList()
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        selected += prefs.autoShowPackages

        binding.back.setOnClickListener { finish() }

        // Icon loading touches the package manager, so it happens once here rather than per bind.
        allApps = loadApps()
        adapter = AppAdapter(this, allApps)
        binding.appList.adapter = adapter
        binding.appList.setOnItemClickListener { _, _, position, _ -> toggle(adapter.getItem(position)) }

        binding.search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = applyFilter(s?.toString().orEmpty())
            }
        )

        applyFilter("")
        updateCount()
    }

    private fun toggle(entry: AppEntry) {
        if (!selected.add(entry.packageName)) selected.remove(entry.packageName)
        prefs.autoShowPackages = selected
        adapter.notifyDataSetChanged()
        updateCount()
        // Tell the running overlay at once, so it reacts without waiting for the next poll.
        Bridge.refresh()
    }

    private fun updateCount() {
        binding.selectedCount.text = if (selected.isEmpty()) {
            getString(R.string.auto_none)
        } else {
            getString(R.string.auto_count, selected.size)
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val shown = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.lowercase().contains(q) }
        }
        adapter.submit(shown)
        binding.empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Every app with a launcher icon.
     *
     * The launcher intent filter is what makes an app something a user can "get on", so it is
     * the right filter here. Our own package is left out: it is always shown anyway, so offering
     * it would only be a checkbox that does nothing.
     */
    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

        return resolved
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString().ifBlank { info.packageName },
                    icon = info.loadIcon(pm)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** One launchable app. The icon is resolved once, at load, not on every row bind. */
    private data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable
    )

    private inner class AppAdapter(
        private val context: Context,
        initial: List<AppEntry>
    ) : BaseAdapter() {

        private var items: List<AppEntry> = initial

        fun submit(next: List<AppEntry>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppEntry = items[position]
        override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView == null) {
                RowAppBinding.inflate(LayoutInflater.from(context), parent, false)
            } else {
                RowAppBinding.bind(convertView)
            }
            val entry = items[position]
            val chosen = entry.packageName in selected

            row.appIcon.setImageDrawable(entry.icon)
            row.appLabel.text = entry.label
            row.appCheck.visibility = if (chosen) View.VISIBLE else View.INVISIBLE
            row.root.isSelected = chosen
            return row.root
        }
    }
}
