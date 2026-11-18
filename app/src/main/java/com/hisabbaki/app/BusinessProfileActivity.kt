package com.hisabbaki.app

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.BusinessProfile
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.LogoStore

/** Business name, owner, contact details, GSTIN and logo. Used in PDFs and shared statements. */
class BusinessProfileActivity : AppCompatActivity() {

    companion object {
        private val MOBILE_PATTERN = Regex("^[6-9][0-9]{9}\$")
        private val GSTIN_PATTERN = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]\$")
        private const val STATE_LOGO = "state_logo"
    }

    private var hasLogo = false

    private lateinit var tilMobile: TextInputLayout
    private lateinit var tilGstin: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var etOwner: TextInputEditText
    private lateinit var etMobile: TextInputEditText
    private lateinit var etAddress: TextInputEditText
    private lateinit var etGstin: TextInputEditText
    private lateinit var ivLogo: ImageView
    private lateinit var btnSave: MaterialButton

    private val logoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val appCtx = applicationContext
            Db.async({
                val ok = LogoStore.saveFromUri(appCtx, uri)
                if (ok) LogoStore.loadFile(appCtx) else null
            }) { result ->
                if (isFinishing || isDestroyed) return@async
                val bmp = result.getOrNull()
                if (bmp == null) {
                    Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                } else {
                    hasLogo = true
                    showLogo(bmp)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_business_profile)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        tilMobile = findViewById(R.id.tilBizMobile)
        tilGstin = findViewById(R.id.tilGstin)
        etName = findViewById(R.id.etBizName)
        etOwner = findViewById(R.id.etOwner)
        etMobile = findViewById(R.id.etBizMobile)
        etAddress = findViewById(R.id.etBizAddress)
        etGstin = findViewById(R.id.etGstin)
        ivLogo = findViewById(R.id.ivLogo)
        btnSave = findViewById(R.id.btnSave)

        findViewById<MaterialButton>(R.id.btnChooseLogo).setOnClickListener { logoPicker.launch("image/*") }
        findViewById<MaterialButton>(R.id.btnRemoveLogo).setOnClickListener {
            LogoStore.delete(applicationContext)
            hasLogo = false
            showLogo(null)
        }
        btnSave.setOnClickListener { save() }

        if (savedInstanceState == null) {
            load()
        } else {
            hasLogo = savedInstanceState.getBoolean(STATE_LOGO, false)
            showLogo(if (hasLogo) LogoStore.loadFile(applicationContext) else null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LOGO, hasLogo)
    }

    private fun load() {
        val appCtx = applicationContext
        Db.async({
            val profile = Repository.get(appCtx).getBusinessProfile()
            Pair(profile, if (profile.logoPath.isNotBlank()) LogoStore.loadFile(appCtx) else null)
        }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { data ->
                val p = data.first
                etName.setText(p.name)
                etOwner.setText(p.owner)
                etMobile.setText(p.mobile)
                etAddress.setText(p.address)
                etGstin.setText(p.gstin)
                hasLogo = data.second != null
                showLogo(data.second)
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLogo(bitmap: Bitmap?) {
        val d = resources.displayMetrics.density
        if (bitmap != null) {
            ivLogo.imageTintList = null
            ivLogo.setPadding(0, 0, 0, 0)
            ivLogo.setImageBitmap(bitmap)
        } else {
            ivLogo.setImageResource(R.drawable.ic_nav_suppliers)
            ivLogo.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary))
            val pad = (18 * d).toInt()
            ivLogo.setPadding(pad, pad, pad, pad)
        }
    }

    private fun save() {
        tilMobile.error = null
        tilGstin.error = null

        val mobile = etMobile.text.toString().replace(Regex("[\\s-]"), "")
        val gstin = etGstin.text.toString().trim().uppercase()

        var valid = true
        if (mobile.isNotEmpty() && !MOBILE_PATTERN.matches(mobile)) {
            tilMobile.error = getString(R.string.error_mobile_invalid)
            valid = false
        }
        if (gstin.isNotEmpty() && !GSTIN_PATTERN.matches(gstin)) {
            tilGstin.error = getString(R.string.error_gstin_invalid)
            valid = false
        }
        if (!valid) return

        val profile = BusinessProfile(
            name = etName.text.toString().trim(),
            owner = etOwner.text.toString().trim(),
            mobile = mobile,
            address = etAddress.text.toString().trim(),
            gstin = gstin,
            logoPath = if (hasLogo) LogoStore.FILE_NAME else ""
        )
        btnSave.isEnabled = false
        val appCtx = applicationContext
        Db.async({ Repository.get(appCtx).saveBusinessProfile(profile) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess {
                Toast.makeText(appCtx, R.string.saved_ok, Toast.LENGTH_SHORT).show()
                finish()
            }
            result.onFailure {
                btnSave.isEnabled = true
                Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
