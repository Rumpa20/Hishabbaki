package com.hisabbaki.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Party
import com.hisabbaki.app.data.ReminderRecord
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.Money

/**
 * Payment reminder for a customer. Nothing is sent automatically: the WhatsApp / SMS app opens
 * with the message filled in and the user presses send there.
 */
class ReminderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PARTY_ID = "party_id"
    }

    private class Loaded(val party: Party?, val businessName: String, val history: List<ReminderRecord>)

    private var partyId = 0L
    private var party: Party? = null
    private var businessName = ""
    private val historyAdapter = ReminderAdapter()

    private lateinit var etMessage: TextInputEditText
    private lateinit var btnWhatsApp: MaterialButton
    private lateinit var btnSms: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder)

        partyId = intent.getLongExtra(EXTRA_PARTY_ID, 0L)
        if (partyId == 0L) {
            finish()
            return
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        etMessage = findViewById(R.id.etMessage)
        btnWhatsApp = findViewById(R.id.btnWhatsApp)
        btnSms = findViewById(R.id.btnSms)

        val rv = findViewById<RecyclerView>(R.id.rvReminders)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = historyAdapter

        btnWhatsApp.setOnClickListener { send("WHATSAPP") }
        btnSms.setOnClickListener { send("SMS") }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener { fillDefaultMessage() }

        load(savedInstanceState == null)
    }

    private fun load(fillMessage: Boolean) {
        val appCtx = applicationContext
        val id = partyId
        Db.async({
            val repo = Repository.get(appCtx)
            Loaded(repo.getParty(false, id), repo.getBusinessProfile().name, repo.getReminders(id))
        }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { data ->
                val p = data.party
                if (p == null) {
                    Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@onSuccess
                }
                party = p
                businessName = data.businessName
                findViewById<TextView>(R.id.tvReminderName).text = p.name
                val amount = p.forYouPaise
                findViewById<TextView>(R.id.tvReminderAmount).text = Money.format(Math.max(amount, 0L))
                val canSend = amount > 0L
                btnWhatsApp.isEnabled = canSend
                btnSms.isEnabled = canSend
                findViewById<TextView>(R.id.tvReminderWarning).visibility =
                    if (canSend) android.view.View.GONE else android.view.View.VISIBLE
                if (fillMessage && canSend) fillDefaultMessage()

                historyAdapter.submit(data.history)
                findViewById<TextView>(R.id.tvReminderEmpty).visibility =
                    if (data.history.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fillDefaultMessage() {
        val p = party ?: return
        val business = if (businessName.isBlank()) getString(R.string.app_name) else businessName
        etMessage.setText(
            getString(R.string.reminder_template, p.name, Money.format(Math.max(p.forYouPaise, 0L)), business)
        )
    }

    private fun send(channel: String) {
        val p = party ?: return
        val message = etMessage.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(this, R.string.error_message_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (p.mobile.isEmpty()) {
            Toast.makeText(this, R.string.error_no_mobile, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = if (channel == "SMS") {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + p.mobile))
            i.putExtra("sms_body", message)
            i
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/91" + p.mobile + "?text=" + Uri.encode(message)))
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_app_missing, Toast.LENGTH_SHORT).show()
            return
        }

        // The other app is now open for the user to confirm. Record it in the history.
        val appCtx = applicationContext
        val amount = p.forYouPaise
        val id = partyId
        Db.async({ Repository.get(appCtx).addReminder(id, amount, channel, message) }) { result ->
            result.onSuccess { if (!isFinishing && !isDestroyed) load(false) }
        }
    }
}
