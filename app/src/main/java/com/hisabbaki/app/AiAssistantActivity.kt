package com.hisabbaki.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.hisabbaki.app.data.BillingRepo
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.ExpenseRepo
import com.hisabbaki.app.data.InventoryRepo
import com.hisabbaki.app.data.Party
import com.hisabbaki.app.data.Product
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.Money
import com.hisabbaki.app.util.Qty
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * Answers business questions from the local database. Optionally forwards the
 * same computed snapshot to a user-configured OpenAI-compatible API.
 */
class AiAssistantActivity : AppCompatActivity() {

    private data class Brief(
        val todaySalesPaise: Long,
        val monthSalesPaise: Long,
        val monthPurchasesPaise: Long,
        val monthExpensesPaise: Long,
        val monthProfitPaise: Long,
        val receivablePaise: Long,
        val payablePaise: Long,
        val lowStock: List<Product>,
        val outOfStockCount: Int,
        val stockValuePaise: Long,
        val topCustomers: List<Party>,
        val topSuppliers: List<Party>
    )

    private lateinit var tvBrief: TextView
    private lateinit var tvQuestion: TextView
    private lateinit var tvAnswer: TextView
    private lateinit var tvAnswerMeta: TextView

    private var brief: Brief? = null
    private var requestId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_assistant)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_ai)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_ai_config) {
                showAiConfigDialog()
                true
            } else {
                false
            }
        }

        tvBrief = findViewById(R.id.tvBrief)
        tvQuestion = findViewById(R.id.tvQuestion)
        tvAnswer = findViewById(R.id.tvAnswer)
        tvAnswerMeta = findViewById(R.id.tvAnswerMeta)
        tvAnswerMeta.text = aiConfigNote()

        findViewById<MaterialButton>(R.id.btnAsk).setOnClickListener { onAsk() }
        loadBrief()
    }

    private fun bizId(): Int = Prefs.currentBusinessId(this)

    private fun aiConfigured(): Boolean =
        Prefs.aiBaseUrl(this).isNotBlank() && Prefs.aiModel(this).isNotBlank()

    private fun aiConfigNote(): String =
        if (aiConfigured()) {
            getString(R.string.ai_remote_note) + " (" + Prefs.aiModel(this) + ")"
        } else {
            getString(R.string.ai_local_note)
        }

    // ------------------------------------------------------------ local snapshot

    private fun loadBrief() {
        val appCtx = applicationContext
        val businessId = bizId()
        requestId += 1
        val myId = requestId

        Db.async({
            val now = System.currentTimeMillis()
            val dayStart = ExpenseRepo.dayStart(now)
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val monthStart = ExpenseRepo.dayStart(cal.timeInMillis)
            cal.add(Calendar.MONTH, 1)
            val monthEnd = ExpenseRepo.dayStart(cal.timeInMillis)

            val billing = BillingRepo.get(appCtx)
            val inventory = InventoryRepo.get(appCtx)
            val repo = Repository.get(appCtx)
            val todayT = billing.getBusinessTotals(businessId, dayStart, dayStart + DAY_MS)
            val monthT = billing.getBusinessTotals(businessId, monthStart, monthEnd)
            val stats = inventory.getProductStats(businessId)
            Brief(
                todaySalesPaise = todayT.salesPaise - todayT.salesReturnsPaise,
                monthSalesPaise = monthT.salesPaise - monthT.salesReturnsPaise,
                monthPurchasesPaise = monthT.purchasesPaise - monthT.purchaseReturnsPaise,
                monthExpensesPaise = monthT.expensesPaise,
                monthProfitPaise = monthT.netProfitPaise,
                receivablePaise = monthT.receivablePaise,
                payablePaise = monthT.payablePaise,
                lowStock = stats.lowStock,
                outOfStockCount = stats.outOfStockCount,
                stockValuePaise = stats.stockValuePaise,
                topCustomers = repo.getParties(false, "", true).filter { it.balancePaise > 0 }.take(5),
                topSuppliers = repo.getParties(true, "", true).filter { it.balancePaise > 0 }.take(5)
            )
        }) { result ->
            if (isFinishing || isDestroyed || myId != requestId) return@async
            result.onSuccess {
                brief = it
                tvBrief.text = buildBriefText(it)
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildBriefText(b: Brief): String = buildString {
        append(getString(R.string.ai_today_sales)).append(": ")
        append(Money.format(b.todaySalesPaise)).append('\n')
        append(getString(R.string.ai_month_sales)).append(": ")
        append(Money.format(b.monthSalesPaise)).append('\n')
        append(getString(R.string.ai_purchases)).append(": ")
        append(Money.format(b.monthPurchasesPaise)).append('\n')
        append(getString(R.string.ai_expenses)).append(": ")
        append(Money.format(b.monthExpensesPaise)).append('\n')
        append(getString(R.string.ai_profit)).append(": ")
        append(Money.format(b.monthProfitPaise)).append('\n')
        append(getString(R.string.ai_receivables)).append(": ")
        append(Money.format(b.receivablePaise)).append('\n')
        append(getString(R.string.ai_payables)).append(": ")
        append(Money.format(b.payablePaise)).append('\n')
        append(getString(R.string.ai_low_stock)).append(": ").append(b.lowStock.size)
        if (b.outOfStockCount > 0) {
            append("  (\u26a0 ").append(b.outOfStockCount).append(')')
        }
        append('\n')
        append(getString(R.string.ai_stock_value)).append(": ").append(Money.format(b.stockValuePaise))
    }

    // ------------------------------------------------------------ ask

    private fun onAsk() {
        val etQuestion = findViewById<TextInputEditText>(R.id.etQuestion)
        val question = etQuestion.text.toString().trim()
        if (question.isEmpty()) return
        val b = brief ?: return

        tvQuestion.text = question
        tvQuestion.visibility = View.VISIBLE
        tvAnswer.text = getString(R.string.ai_thinking)
        etQuestion.setText("")

        if (aiConfigured()) {
            Db.async({ callApi(question, buildBriefText(b)) }) { result ->
                if (isFinishing || isDestroyed) return@async
                result.onSuccess { answer ->
                    tvAnswer.text = answer
                    tvAnswerMeta.text = aiConfigNote()
                }
                result.onFailure {
                    tvAnswer.text = localAnswer(b, question)
                    tvAnswerMeta.text = getString(R.string.ai_network_error)
                }
            }
        } else {
            tvAnswer.text = localAnswer(b, question)
            tvAnswerMeta.text = aiConfigNote()
        }
    }

    /** Offline answers computed from the snapshot; supports EN / BN / HI keywords. */
    private fun localAnswer(b: Brief, question: String): String {
        val q = question.lowercase()
        val wantsSales = hasAny(q, "sale", "sales", "bikri", "bikroy", "\u09ac\u09bf\u0995\u09cd\u09b0\u09bf", "\u092c\u093f\u0915\u094d\u0930\u0940", "\u0935\u093f\u0915\u094d\u0930\u092f")
        val wantsToday = hasAny(q, "today", "\u0986\u099c", "\u0906\u091c")
        val wantsPurchases = hasAny(q, "purchase", "\u0995\u09c7\u09a8\u09be", "\u0995\u09c7\u09a8\u09be\u0995\u09be\u099f\u09be", "\u0996\u09b0\u09bf\u09a6", "\u0916\u0930\u0940\u0926")
        val wantsExpenses = hasAny(q, "expense", "\u0996\u09b0\u099a", "\u0996\u09b0\u09cd\u099a", "\u0916\u0930\u094d\u091a", "\u0935\u094d\u092f\u092f")
        val wantsProfit = hasAny(q, "profit", "\u09b2\u09be\u09ad", "\u09ae\u09c1\u09a8\u09be\u09ab\u09bc\u09be", "\u0932\u093e\u09ad", "\u092e\u0941\u0928\u093e\u092b\u093c\u093e")
        val wantsReceivable = hasAny(q, "receivable", "pending", "udhar", "due", "customer", "\u09ac\u09be\u0995\u09bf", "\u0989\u09a7\u09be\u09b0", "\u09aa\u09be\u0993\u09a8\u09be", "\u092c\u093e\u0915\u0940", "\u0909\u0927\u093e\u0930", "\u092c\u0915\u093e\u092f\u093e", "\u0917\u094d\u0930\u093e\u0939\u0915", "\u0995\u09be\u09b8\u09cd\u099f\u09ae\u09be\u09b0")
        val wantsPayable = hasAny(q, "payable", "supplier", "owe", "\u09a6\u09c7\u09a8\u09be", "\u09b8\u09be\u09aa\u09cd\u09b2\u09be\u09af\u09bc\u09be\u09b0", "\u0926\u0947\u092f", "\u0938\u092a\u094d\u0932\u093e\u092f\u0930")
        val wantsStock = hasAny(q, "stock", "inventory", "\u09b8\u09cd\u099f\u0995", "\u09ae\u099c\u09c1\u09a6", "\u09ae\u09be\u09b2", "\u0938\u094d\u091f\u0949\u0915", "\u0935\u0938\u094d\u0924\u0941", "\u092e\u093e\u09b2")

        if (!wantsSales && !wantsPurchases && !wantsExpenses && !wantsProfit &&
            !wantsReceivable && !wantsPayable && !wantsStock
        ) {
            return buildBriefText(b)
        }

        val lines = ArrayList<String>()
        if (wantsSales) {
            if (wantsToday) {
                lines.add(getString(R.string.ai_today_sales) + ": " + Money.format(b.todaySalesPaise))
            } else {
                lines.add(getString(R.string.ai_month_sales) + ": " + Money.format(b.monthSalesPaise))
            }
        }
        if (wantsPurchases) {
            lines.add(getString(R.string.ai_purchases) + ": " + Money.format(b.monthPurchasesPaise))
        }
        if (wantsExpenses) {
            lines.add(getString(R.string.ai_expenses) + ": " + Money.format(b.monthExpensesPaise))
        }
        if (wantsProfit) {
            lines.add(getString(R.string.ai_profit) + ": " + Money.format(b.monthProfitPaise))
        }
        if (wantsReceivable) {
            lines.add(getString(R.string.ai_receivables) + ": " + Money.format(b.receivablePaise))
            if (b.topCustomers.isEmpty()) {
                lines.add(getString(R.string.ai_top_customers_title) + ": " + getString(R.string.ai_none))
            } else {
                lines.add(getString(R.string.ai_top_customers_title) + ":")
                for (p in b.topCustomers) {
                    lines.add("\u2022 " + p.name + " \u2013 " + Money.format(p.balancePaise))
                }
            }
        }
        if (wantsPayable) {
            lines.add(getString(R.string.ai_payables) + ": " + Money.format(b.payablePaise))
            for (p in b.topSuppliers) {
                lines.add("\u2022 " + p.name + " \u2013 " + Money.format(p.balancePaise))
            }
        }
        if (wantsStock) {
            lines.add(getString(R.string.ai_low_stock) + ": " + b.lowStock.size)
            lines.add(getString(R.string.ai_stock_value) + ": " + Money.format(b.stockValuePaise))
            for (p in b.lowStock.take(5)) {
                lines.add("\u2022 " + p.name + " \u2013 " + Qty.format(p.stockMilli, p.unit))
            }
        }
        return lines.joinToString("\n")
    }

    private fun hasAny(q: String, vararg keys: String): Boolean = keys.any { q.contains(it) }

    // ------------------------------------------------------------ remote API

    private fun callApi(question: String, snapshot: String): String {
        val base = Prefs.aiBaseUrl(this).trim().trimEnd('/')
        val model = Prefs.aiModel(this).trim()
        val key = Prefs.aiApiKey(this).trim()
        val url = URL(if (base.endsWith("/chat/completions")) base else base + "/chat/completions")

        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", SYSTEM_PROMPT + "\n\n" + snapshot)
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", question)
            )
        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", 600)
            .put("messages", messages)

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            val choices = JSONObject(body).getJSONArray("choices")
            return choices.getJSONObject(0).getJSONObject("message")
                .getString("content").trim()
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ config dialog

    private fun showAiConfigDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_ai_config, null)
        val etBaseUrl = view.findViewById<TextInputEditText>(R.id.etAiBaseUrl)
        val etModel = view.findViewById<TextInputEditText>(R.id.etAiModel)
        val etKey = view.findViewById<TextInputEditText>(R.id.etAiApiKey)
        etBaseUrl.setText(Prefs.aiBaseUrl(this))
        etModel.setText(Prefs.aiModel(this))
        etKey.setText(Prefs.aiApiKey(this))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_config)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                Prefs.setAiConfig(
                    this,
                    etBaseUrl.text.toString(),
                    etModel.text.toString(),
                    etKey.text.toString()
                )
                tvAnswerMeta.text = aiConfigNote()
                Toast.makeText(this, R.string.ai_saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.ai_clear, null)
            .show()
            .also { dialog ->
                // Clear must run after the positive handler never fires; use dedicated button
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    Prefs.clearAiConfig(applicationContext)
                    dialog.dismiss()
                    tvAnswerMeta.text = aiConfigNote()
                    Toast.makeText(this, R.string.ai_saved, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        const val SYSTEM_PROMPT =
            "You are the AI assistant inside the Hishabbaki business app. " +
                "Answer in the same language the user asked in. " +
                "Use only the business snapshot below; do not invent numbers. " +
                "Keep answers short and practical."
    }
}
