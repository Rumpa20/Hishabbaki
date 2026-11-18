package com.hisabbaki.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hisabbaki.app.data.DatabaseHelper
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.DuplicateSkuException
import com.hisabbaki.app.data.InventoryRepo
import com.hisabbaki.app.data.Product
import com.hisabbaki.app.data.ProductCategory
import com.hisabbaki.app.util.Money
import com.hisabbaki.app.util.Qty
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File

class ProductEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
    }

    private var productId = 0L
    private var imagePath = ""
    private var selectedCategoryId = 0L
    private var categories: List<ProductCategory> = emptyList()
    private var unit = "pcs"

    private lateinit var tilName: TextInputLayout
    private lateinit var tilSku: TextInputLayout
    private lateinit var tilPurchase: TextInputLayout
    private lateinit var tilSelling: TextInputLayout
    private lateinit var tilMrp: TextInputLayout
    private lateinit var tilOpening: TextInputLayout
    private lateinit var tilMin: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var etSku: TextInputEditText
    private lateinit var etBarcode: TextInputEditText
    private lateinit var actCategory: AutoCompleteTextView
    private lateinit var actUnit: AutoCompleteTextView
    private lateinit var etPurchase: TextInputEditText
    private lateinit var etSelling: TextInputEditText
    private lateinit var etMrp: TextInputEditText
    private lateinit var etOpening: TextInputEditText
    private lateinit var etMin: TextInputEditText
    private lateinit var switchAllowNegative: MaterialSwitch
    private lateinit var etNotes: TextInputEditText
    private lateinit var imgProduct: ImageView

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) importPhoto(uri) }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (!text.isNullOrBlank()) etBarcode.setText(text.trim())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_product_edit)

        productId = intent.getLongExtra(EXTRA_PRODUCT_ID, 0L)
        val editing = productId != 0L

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(if (editing) R.string.edit_product else R.string.add_product)
        toolbar.setNavigationOnClickListener { finish() }

        tilName = findViewById(R.id.tilName)
        tilSku = findViewById(R.id.tilSku)
        tilPurchase = findViewById(R.id.tilPurchase)
        tilSelling = findViewById(R.id.tilSelling)
        tilMrp = findViewById(R.id.tilMrp)
        tilOpening = findViewById(R.id.tilOpening)
        tilMin = findViewById(R.id.tilMin)
        etName = findViewById(R.id.etName)
        etSku = findViewById(R.id.etSku)
        etBarcode = findViewById(R.id.etBarcode)
        actCategory = findViewById(R.id.actCategory)
        actUnit = findViewById(R.id.actUnit)
        etPurchase = findViewById(R.id.etPurchase)
        etSelling = findViewById(R.id.etSelling)
        etMrp = findViewById(R.id.etMrp)
        etOpening = findViewById(R.id.etOpening)
        etMin = findViewById(R.id.etMin)
        switchAllowNegative = findViewById(R.id.switchAllowNegative)
        etNotes = findViewById(R.id.etNotes)
        imgProduct = findViewById(R.id.imgProduct)

        actUnit.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, DatabaseHelper.UNITS)
        )
        actUnit.setText(unit, false)
        actUnit.setOnItemClickListener { _, _, position, _ ->
            unit = DatabaseHelper.UNITS[position]
        }

        actCategory.setOnClickListener { showCategoryPicker() }

        findViewById<ImageButton>(R.id.btnScan).setOnClickListener { launchScanner() }
        findViewById<View>(R.id.btnChoosePhoto).setOnClickListener { photoPicker.launch("image/*") }
        findViewById<View>(R.id.btnRemovePhoto).setOnClickListener {
            imagePath = ""
            showImage()
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }

        loadCategories()
        showImage()
        if (editing) load()
    }

    private fun bizId() = Prefs.currentBusinessId(this)

    private fun load() {
        val appCtx = applicationContext
        val id = productId
        Db.async({ InventoryRepo.get(appCtx).getProduct(bizId(), id) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess { p ->
                if (p == null) {
                    Toast.makeText(appCtx, R.string.error_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@onSuccess
                }
                etName.setText(p.name)
                etSku.setText(p.sku)
                etBarcode.setText(p.barcode)
                etPurchase.setText(if (p.purchasePricePaise == 0L) "" else Money.toEditText(p.purchasePricePaise))
                etSelling.setText(if (p.sellingPricePaise == 0L) "" else Money.toEditText(p.sellingPricePaise))
                etMrp.setText(if (p.mrpPaise == 0L) "" else Money.toEditText(p.mrpPaise))
                etOpening.setText(Qty.toEditText(p.openingStockMilli))
                etMin.setText(if (p.minStockMilli == 0L) "" else Qty.toEditText(p.minStockMilli))
                switchAllowNegative.isChecked = p.allowNegative
                etNotes.setText(p.notes)
                imagePath = p.imagePath
                unit = if (p.unit.isBlank()) "pcs" else p.unit
                selectedCategoryId = p.categoryId
                actUnit.setText(unit, false)
                refreshCategoryLabel()
                showImage()
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.error_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadCategories() {
        val appCtx = applicationContext
        Db.async({ InventoryRepo.get(appCtx).getCategories(bizId()) }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess {
                categories = it
                refreshCategoryLabel()
            }
        }
    }

    private fun refreshCategoryLabel() {
        val name = categories.firstOrNull { it.id == selectedCategoryId }?.name
            ?: getString(R.string.category_none)
        actCategory.setText(name, false)
    }

    private fun showCategoryPicker() {
        val options = ArrayList<String>()
        options.add(getString(R.string.category_none))
        options.addAll(categories.map { it.name })
        options.add(getString(R.string.new_category))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category)
            .setItems(options.toTypedArray()) { dialog, which ->
                if (which == options.size - 1) {
                    dialog.dismiss()
                    promptNewCategory()
                } else if (which == 0) {
                    selectedCategoryId = 0L
                    refreshCategoryLabel()
                } else {
                    selectedCategoryId = categories[which - 1].id
                    refreshCategoryLabel()
                }
            }
            .show()
    }

    private fun promptNewCategory() {
        val input = TextInputEditText(this)
        input.setHint(R.string.category)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_category)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val appCtx = applicationContext
                Db.async({ InventoryRepo.get(appCtx).saveCategory(bizId(), 0L, name) }) { result ->
                    result.onSuccess { id ->
                        val biz = bizId()
                        Db.async({ InventoryRepo.get(appCtx).getCategories(biz) }) { r ->
                            if (isFinishing || isDestroyed) return@async
                            r.onSuccess {
                                categories = it
                                selectedCategoryId = id
                                refreshCategoryLabel()
                            }
                        }
                    }
                    result.onFailure {
                        Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun launchScanner() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
        options.setPrompt(getString(R.string.scan_prompt))
        options.setBeepEnabled(false)
        options.setOrientationLocked(true)
        try {
            barcodeLauncher.launch(options)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importPhoto(uri: Uri) {
        val appCtx = applicationContext
        try {
            val dir = File(appCtx.filesDir, "products")
            dir.mkdirs()
            val dest = File(dir, "product_${System.currentTimeMillis()}.jpg")
            appCtx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.length() > 0) {
                imagePath = dest.absolutePath
                showImage()
            }
        } catch (e: Exception) {
            Toast.makeText(appCtx, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImage() {
        if (imagePath.isEmpty()) {
            imgProduct.setImageResource(R.drawable.bg_avatar)
            return
        }
        val file = File(imagePath)
        if (file.exists()) {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(imagePath, options)
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSizeFor(options, 256, 256)
            val bmp = BitmapFactory.decodeFile(imagePath, options)
            if (bmp != null) {
                imgProduct.setImageBitmap(bmp)
                return
            }
        }
        imgProduct.setImageResource(R.drawable.bg_avatar)
    }

    private fun sampleSizeFor(options: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (options.outHeight > reqH || options.outWidth > reqW) {
            var h = options.outHeight
            var w = options.outWidth
            while (h / 2 >= reqH || w / 2 >= reqW) {
                h /= 2
                w /= 2
                sample *= 2
            }
        }
        return sample
    }

    private fun save() {
        tilName.error = null
        tilPurchase.error = null
        tilSelling.error = null
        tilMrp.error = null
        tilOpening.error = null
        tilMin.error = null

        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            tilName.error = getString(R.string.error_product_name)
            return
        }
        val sku = etSku.text.toString().trim()
        val barcode = etBarcode.text.toString().trim()
        val notes = etNotes.text.toString().trim()
        val allowNegative = switchAllowNegative.isChecked
        val categoryId = selectedCategoryId
        val path = imagePath
        val id = productId

        val purchase = readMoney(etPurchase, tilPurchase) ?: return
        val selling = readMoney(etSelling, tilSelling) ?: return
        val mrp = readMoney(etMrp, tilMrp) ?: return
        val opening = readQty(etOpening, tilOpening) ?: return
        val minStock = readQty(etMin, tilMin) ?: return

        val appCtx = applicationContext
        Db.async({
            InventoryRepo.get(appCtx).saveProduct(
                bizId(), id, categoryId, name, sku, barcode, unit, path,
                purchase, selling, mrp, opening, minStock, allowNegative, notes
            )
        }) { result ->
            if (isFinishing || isDestroyed) return@async
            result.onSuccess {
                Toast.makeText(appCtx, R.string.product_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
            result.onFailure { error ->
                when (error) {
                    is DuplicateSkuException -> tilSku.error = getString(R.string.error_duplicate_sku)
                    is IllegalArgumentException -> tilName.error = error.message
                    else -> {
                        val msg = error.message
                        Toast.makeText(appCtx, msg ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun readMoney(field: TextInputEditText, layout: TextInputLayout): Long? {
        val text = field.text.toString().trim()
        if (text.isEmpty()) return 0L
        val value = Money.parseToPaise(text)
        if (value == null) layout.error = getString(R.string.error_price)
        return value
    }

    private fun readQty(field: TextInputEditText, layout: TextInputLayout): Long? {
        val text = field.text.toString().trim()
        if (text.isEmpty()) return 0L
        val value = Qty.parseToMilli(text)
        if (value == null) layout.error = getString(R.string.error_qty)
        return value
    }
}
