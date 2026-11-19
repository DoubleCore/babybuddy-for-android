package eu.pkgsoftware.babybuddywidgets.backup

import android.content.Intent
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.MainActivity
import eu.pkgsoftware.babybuddywidgets.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

object BackupCenterBottomSheet {

    fun show(
        activity: MainActivity,
        onCompleted: () -> Unit = {},
    ) {
        val ctx = activity
        val res = ctx.resources
        val density = res.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val manager = BackupImportExportManager(activity.repository)
        val sheet = BottomSheetDialog(ctx)
        val scroll = ScrollView(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
        }
        scroll.addView(container)

        val titleView = TextView(ctx).apply {
            text = res.getString(R.string.backup_center_title)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        }
        container.addView(titleView)

        val subView = TextView(ctx).apply {
            text = res.getString(R.string.backup_center_subtitle)
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            setPadding(0, dp(4), 0, dp(12))
        }
        container.addView(subView)

        fun makeActionButton(labelRes: Int, filled: Boolean, onClick: () -> Unit): MaterialButton {
            val styleAttr = if (filled) {
                com.google.android.material.R.attr.materialButtonStyle
            } else {
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            }
            return MaterialButton(ctx, null, styleAttr).apply {
                text = res.getString(labelRes)
                isAllCaps = false
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46)
                ).apply {
                    topMargin = dp(8)
                }
                setOnClickListener { onClick() }
            }
        }

        fun promptImportMode(onChosen: (BackupImportExportManager.ImportMode) -> Unit) {
            val modes = arrayOf(
                res.getString(R.string.backup_mode_merge),
                res.getString(R.string.backup_mode_replace),
            )
            AlertDialog.Builder(ctx)
                .setTitle(R.string.backup_choose_import_mode_title)
                .setItems(modes) { _, which ->
                    val mode = if (which == 1) {
                        BackupImportExportManager.ImportMode.REPLACE_ALL
                    } else {
                        BackupImportExportManager.ImportMode.MERGE_DEDUPLICATE
                    }
                    onChosen(mode)
                }
                .show()
        }

        fun reportImportSuccess(summary: BackupImportExportManager.ImportSummary) {
            val msg = Phrase.from(res, R.string.backup_import_success_pattern)
                .put("entries", summary.entriesImported)
                .put("children", summary.childrenCount)
                .format()
                .toString()
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            onCompleted()
        }

        fun reportError(e: Exception) {
            val msg = e.message ?: res.getString(R.string.activity_store_failure_message)
            activity.binding.globalErrorBubble.flashMessage(msg)
        }

        val todayIso = LocalDate.now().toString()

        // 1. Export Full ZIP
        container.addView(makeActionButton(R.string.backup_export_zip_button, filled = true) {
            sheet.dismiss()
            activity.createExportDocument("application/zip", "babybuddy-backup-$todayIso.zip") { uri ->
                if (uri == null) return@createExportDocument
                activity.scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            manager.exportZipToUri(activity, uri)
                        }
                        Toast.makeText(activity, R.string.backup_export_success, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        reportError(e)
                    }
                }
            }
        })

        // 2. Share Full ZIP via FileProvider
        container.addView(makeActionButton(R.string.backup_share_zip_button, filled = false) {
            sheet.dismiss()
            activity.scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        manager.writeShareableBackupZip(activity)
                    }
                    val shareUri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        file
                    )
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(sendIntent, res.getString(R.string.backup_share_zip_button)))
                } catch (e: Exception) {
                    reportError(e)
                }
            }
        })

        // 3. Restore / Import ZIP
        container.addView(makeActionButton(R.string.backup_restore_zip_button, filled = false) {
            sheet.dismiss()
            promptImportMode { mode ->
                activity.openImportDocument(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) { uri ->
                    if (uri == null) return@openImportDocument
                    activity.scope.launch {
                        try {
                            val summary = withContext(Dispatchers.IO) {
                                manager.importZipFromUri(activity, uri, mode)
                            }
                            reportImportSuccess(summary)
                        } catch (e: Exception) {
                            reportError(e)
                        }
                    }
                }
            }
        })

        // 4. Export JSON
        container.addView(makeActionButton(R.string.backup_export_json_button, filled = false) {
            sheet.dismiss()
            activity.createExportDocument("application/json", "babybuddy-backup-$todayIso.json") { uri ->
                if (uri == null) return@createExportDocument
                activity.scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            manager.exportJsonToUri(activity, uri)
                        }
                        Toast.makeText(activity, R.string.backup_export_success, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        reportError(e)
                    }
                }
            }
        })

        // 5. Import JSON
        container.addView(makeActionButton(R.string.backup_import_json_button, filled = false) {
            sheet.dismiss()
            promptImportMode { mode ->
                activity.openImportDocument(arrayOf("application/json", "text/plain", "*/*")) { uri ->
                    if (uri == null) return@openImportDocument
                    activity.scope.launch {
                        try {
                            val summary = withContext(Dispatchers.IO) {
                                manager.importJsonFromUri(activity, uri, mode)
                            }
                            reportImportSuccess(summary)
                        } catch (e: Exception) {
                            reportError(e)
                        }
                    }
                }
            }
        })

        // 6. Export CSV
        container.addView(makeActionButton(R.string.backup_export_csv_button, filled = false) {
            sheet.dismiss()
            activity.createExportDocument("text/csv", "babybuddy-records-$todayIso.csv") { uri ->
                if (uri == null) return@createExportDocument
                activity.scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            manager.exportCsvToUri(activity, uri)
                        }
                        Toast.makeText(activity, R.string.backup_export_success, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        reportError(e)
                    }
                }
            }
        })

        // 7. Import CSV
        container.addView(makeActionButton(R.string.backup_import_csv_button, filled = false) {
            sheet.dismiss()
            promptImportMode { mode ->
                activity.openImportDocument(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) { uri ->
                    if (uri == null) return@openImportDocument
                    val defaultChildId = activity.repository.listChildren().firstOrNull()?.id
                    activity.scope.launch {
                        try {
                            val summary = withContext(Dispatchers.IO) {
                                manager.importCsvFromUri(activity, uri, defaultChildId, mode)
                            }
                            reportImportSuccess(summary)
                        } catch (e: Exception) {
                            reportError(e)
                        }
                    }
                }
            }
        })

        sheet.setContentView(scroll)
        sheet.show()
    }
}
