package com.hisabbaki.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.CsvExport
import com.hisabbaki.app.util.FileOps
import com.hisabbaki.app.util.LogoStore
import com.hisabbaki.app.util.PdfExport
import com.hisabbaki.app.util.ReportBuilder
import com.hisabbaki.app.util.ReportTable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Shows one report as a table and lets the user share / save it as PDF or CSV. */
class ReportDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "report_type"
        const val EXTRA_FROM = "report_from"
        const val EXTRA_TO = "report_to"
    }

    private var type = ReportBuilder.SUMMARY
    private var from = 0L
    private var to = 0L
    private var table: ReportTable? = null

    private val pdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) savePdf(uri) }

    private val csvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) saveCsv(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_detail)

        type = intent.getStringExtra(EXTRA_TYPE) ?: ReportBuilder.SUMMARY
        from = intent.getLongExtra(EXTRA_FROM, 0L)
        to = intent.getLongExtra(EXTRA_TO, 0L)
        if (to <= from) {
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setTitle(ReportBuilder.titleRes(type))
        toolbar.inflateMenu(R.menu.report_menu)
        toolbar.overflowIcon = ContextCompat.getDrawable(this, R.drawable.ic_more)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share_pdf -> sharePdf()
                R.id.action_save_pdf -> pdfLauncher.launch("report.pdf")
                R.id.action_save_csv -> csvLauncher.launch("report.csv")
            }
            true
        }

        findViewById<RecyclerView>(R.id.rvTable).layoutManager = LinearLayoutManager(this)
        load()
    }

    private fun load() {
        val appCtx = applicationContext
        val t = type
        val f = from
        val e = to
        Db.async({ ReportBuilder.build(this, Repository.get(appCtx), t, f, e) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { bind(it) }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bind(t: ReportTable) {
        table = t
        val info = t.infoLines.joinToString("\n") { if (it.second.isEmpty()) it.first else it.first + ": " + it.second }
        findViewById<TextView>(R.id.tvInfo).text = info

        val header = findViewById<LinearLayout>(R.id.tableHeader)
        header.removeAllViews()
        val headerRow = TableRowFactory.create(this, t.weights, t.numericFrom, true)
        for (i in t.headers.indices) headerRow.cells[i].text = t.headers[i]
        header.addView(headerRow.view)

        val rv = findViewById<RecyclerView>(R.id.rvTable)
        rv.adapter = TableAdapter(
            t.weights, t.numericFrom,
            t.rows.map { row -> row.map { it.text } },
            t.footer?.map { it.text }
        )
        findViewById<View>(R.id.tvTableEmpty).visibility = if (t.rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sharePdf() {
        val t = table ?: return
        val appCtx = applicationContext
        Db.async({
            val repo = Repository.get(appCtx)
            val profile = repo.getBusinessProfile()
            val logo = LogoStore.load(appCtx, profile)
            val file = File(FileOps.shareDir(appCtx), "report.pdf")
            FileOutputStream(file).use { PdfExport.report(this, profile, logo, t, it) }
            file
        }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { FileOps.share(this, it, "application/pdf", getString(R.string.share_pdf)) }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun savePdf(uri: Uri) {
        val t = table ?: return
        val appCtx = applicationContext
        Db.async({
            val repo = Repository.get(appCtx)
            val profile = repo.getBusinessProfile()
            val logo = LogoStore.load(appCtx, profile)
            val out = appCtx.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open file")
            out.use { PdfExport.report(this, profile, logo, t, it) }
        }) { result ->
            result.onSuccess { Toast.makeText(appCtx, R.string.export_done, Toast.LENGTH_SHORT).show() }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun saveCsv(uri: Uri) {
        val t = table ?: return
        val appCtx = applicationContext
        Db.async({ FileOps.writeText(appCtx, uri, CsvExport.table(t)) }) { result ->
            result.onSuccess { Toast.makeText(appCtx, R.string.export_done, Toast.LENGTH_SHORT).show() }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }
}
