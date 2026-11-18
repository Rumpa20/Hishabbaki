package com.hisabbaki.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.DuplicateMobileException
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.Money

/** Add / edit a customer or a supplier. */
class PartyEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "party_id"
        const val EXTRA_SUPPLIER = "is_supplier"
        private val MOBILE_PATTERN = Regex("^[6-9][0-9]{9}\$")
    }

    private var partyId = 0L
    private var isSupplier = false

    private lateinit var tilName: TextInputLayout
    private lateinit var tilMobile: TextInputLayout
    private lateinit var tilOpening: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var etMobile: TextInputEditText
    private lateinit var etAddress: TextInputEditText
    private lateinit var etOpening: TextInputEditText
    private lateinit var etNotes: TextInputEditText
    private lateinit var toggle: MaterialButtonToggleGroup
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_party_edit)

        partyId = intent.getLongExtra(EXTRA_ID, 0L)
        isSupplier = intent.getBooleanExtra(EXTRA_SUPPLIER, false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val titleRes = if (isSupplier) {
            if (partyId == 0L) R.string.add_supplier else R.string.edit_supplier
        } else {
            if (partyId == 0L) R.string.add_customer else R.string.edit_customer
        }
        toolbar.setTitle(titleRes)

        tilName = findViewById(R.id.tilName)
        tilMobile = findViewById(R.id.tilMobile)
        tilOpening = findViewById(R.id.tilOpening)
        etName = findViewById(R.id.etName)
        etMobile = findViewById(R.id.etMobile)
        etAddress = findViewById(R.id.etAddress)
        etOpening = findViewById(R.id.etOpening)
        etNotes = findViewById(R.id.etNotes)
        toggle = findViewById(R.id.toggleOpening)
        btnSave = findViewById(R.id.btnSave)

        tilName.hint = getString(if (isSupplier) R.string.hint_supplier_name else R.string.hint_name)

        // New customers usually start with "You will get", new suppliers with "You will give".
        if (savedInstanceState == null) {
            toggle.check(if (isSupplier) R.id.btnGive else R.id.btnGet)
        }
        btnSave.setOnClickListener { save() }

        if (partyId != 0L && savedInstanceState == null) loadParty()
    }

    private fun loadParty() {
        val appCtx = applicationContext
        val supplier = isSupplier
        val id = partyId
        Db.async({ Repository.get(appCtx).getParty(supplier, id) }) { result ->
            if (isFinishing || isDestroyed) return@async
            val p = result.getOrNull()
            if (p == null) {
                Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                finish()
                return@async
            }
            etName.setText(p.name)
            etMobile.setText(p.mobile)
            etAddress.setText(p.address)
            etNotes.setText(p.notes)
            if (p.openingPaise != 0L) {
                val forYou = if (supplier) -p.openingPaise else p.openingPaise
                etOpening.setText(Money.toEditText(Math.abs(p.openingPaise)))
                toggle.check(if (forYou < 0) R.id.btnGive else R.id.btnGet)
            }
        }
    }

    private fun save() {
        tilName.error = null
        tilMobile.error = null
        tilOpening.error = null

        val name = etName.text.toString().trim()
        val mobile = etMobile.text.toString().replace(Regex("[\\s-]"), "")
        val address = etAddress.text.toString().trim()
        val notes = etNotes.text.toString().trim()
        val openingText = etOpening.text.toString().trim()

        var valid = true
        if (name.isEmpty()) {
            tilName.error = getString(R.string.error_name_required)
            valid = false
        }
        if (mobile.isNotEmpty() && !MOBILE_PATTERN.matches(mobile)) {
            tilMobile.error = getString(R.string.error_mobile_invalid)
            valid = false
        }
        var openingPaise = 0L
        if (openingText.isNotEmpty()) {
            val parsed = Money.parseToPaise(openingText)
            if (parsed == null) {
                tilOpening.error = getString(R.string.error_amount_invalid)
                valid = false
            } else {
                openingPaise = parsed
            }
        }
        if (!valid) return

        // "You will get" = positive for you; "You will give" = negative for you.
        val forYou = if (toggle.checkedButtonId == R.id.btnGive) -openingPaise else openingPaise
        // Customers store the balance as-is. Suppliers store what you owe (opposite sign).
        val stored = if (isSupplier) -forYou else forYou

        btnSave.isEnabled = false
        val appCtx = applicationContext
        val supplier = isSupplier
        val id = partyId
        Db.async({ Repository.get(appCtx).saveParty(supplier, id, name, mobile, address, stored, notes) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess {
                Toast.makeText(appCtx, R.string.saved_ok, Toast.LENGTH_SHORT).show()
                finish()
            }
            result.onFailure { error ->
                btnSave.isEnabled = true
                if (error is DuplicateMobileException) {
                    tilMobile.error = getString(R.string.error_mobile_duplicate)
                } else {
                    Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
