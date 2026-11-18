package com.hisabbaki.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hisabbaki.app.data.Db
import com.hisabbaki.app.data.Repository
import com.hisabbaki.app.util.BackupException
import com.hisabbaki.app.util.BackupInfo
import com.hisabbaki.app.util.BackupManager
import com.hisabbaki.app.util.CsvExport
import com.hisabbaki.app.util.FileOps
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    // Language tag -> native name
    private val languages = listOf(
        "en" to "English",
        "bn" to "বাংলা",
        "hi" to "हिन्दी",
        "mr" to "मराठी",
        "te" to "తెలుగు",
        "ta" to "தமிழ்",
        "gu" to "ગુજરાતી",
        "ur" to "اردو",
        "kn" to "ಕನ್ನಡ",
        "ml" to "മലയാളം",
        "pa" to "ਪੰਜਾਬੀ"
    )

    private var pendingExport = 0

    private val csvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) runExport(uri) }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) runBackup(uri) }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) inspectBackup(uri) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val appCtx = requireContext().applicationContext

        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        val checked = when (Prefs.getThemeMode(appCtx)) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnLight
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.btnDark
            else -> R.id.btnSystem
        }
        group.check(checked)
        group.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.btnLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btnDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode != Prefs.getThemeMode(appCtx)) Prefs.setThemeMode(appCtx, mode)
        }

        view.findViewById<TextView>(R.id.tvCurrentLanguage).text = currentLanguageName()
        view.findViewById<View>(R.id.rowLanguage).setOnClickListener { showLanguageDialog() }

        view.findViewById<View>(R.id.rowBusiness).setOnClickListener {
            startActivity(Intent(requireContext(), BusinessProfileActivity::class.java))
        }
        view.findViewById<View>(R.id.rowBusinesses).setOnClickListener {
            startActivity(Intent(requireContext(), BusinessListActivity::class.java))
        }
        view.findViewById<View>(R.id.rowInventory).setOnClickListener {
            startActivity(Intent(requireContext(), ProductsActivity::class.java))
        }
        view.findViewById<View>(R.id.rowExpenses).setOnClickListener {
            startActivity(Intent(requireContext(), ExpensesActivity::class.java))
        }
        view.findViewById<View>(R.id.rowBilling).setOnClickListener {
            startActivity(Intent(requireContext(), InvoicesActivity::class.java))
        }
        view.findViewById<View>(R.id.rowCashbook).setOnClickListener {
            startActivity(Intent(requireContext(), CashbookActivity::class.java))
        }
        view.findViewById<View>(R.id.rowExport).setOnClickListener { showExportDialog() }
        view.findViewById<View>(R.id.rowBackup).setOnClickListener { showBackupDialog() }
        view.findViewById<View>(R.id.rowLock).setOnClickListener { showAppLockDialog() }

        val switchAuto = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switchAutoBackup
        )
        switchAuto.isChecked = Prefs.isAutoBackupEnabled(requireContext())
        switchAuto.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoBackupEnabled(requireContext(), checked)
        }
        view.findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyPolicyActivity::class.java))
        }

        view.findViewById<TextView>(R.id.tvVersion).text = versionName()
    }

    // ------------------------------------------------------------ App lock

    private fun biometricsAvailable(): Boolean =
        androidx.biometric.BiometricManager.from(requireContext())
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

    private fun showAppLockDialog() {
        val hasPin = Prefs.hasPin(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_app_lock, null)
        val tilCurrent = view.findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.tilCurrent
        )
        val etCurrent = view.findViewById<TextView>(R.id.etCurrent)
        val etNew = view.findViewById<TextView>(R.id.etNew)
        val etConfirm = view.findViewById<TextView>(R.id.etConfirm)
        val switchBio = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switchBio
        )

        tilCurrent.visibility = if (hasPin) android.view.View.VISIBLE else android.view.View.GONE
        switchBio.visibility = if (biometricsAvailable()) android.view.View.VISIBLE else android.view.View.GONE
        switchBio.isChecked = Prefs.isBioUnlock(requireContext())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_lock)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()

        if (hasPin) {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).text =
                getString(R.string.pin_disable)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val current = etCurrent.text.toString()
                if (!Prefs.checkPin(requireContext(), current)) {
                    tilCurrent.error = getString(R.string.current_pin_wrong)
                    return@setOnClickListener
                }
                Prefs.clearPin(requireContext())
                Toast.makeText(requireContext(), R.string.pin_disabled, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            tilCurrent.error = null
            val current = etCurrent.text.toString()
            val newPin = etNew.text.toString()
            if (hasPin && !Prefs.checkPin(requireContext(), current)) {
                tilCurrent.error = getString(R.string.current_pin_wrong)
                return@setOnClickListener
            }
            if (newPin.length < 4 || newPin.length > 8) {
                Toast.makeText(requireContext(), R.string.pin_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (newPin != etConfirm.text.toString()) {
                Toast.makeText(requireContext(), R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Prefs.setPin(requireContext(), newPin)) {
                Toast.makeText(requireContext(), R.string.pin_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.setBioUnlock(requireContext(), switchBio.isChecked)
            Toast.makeText(requireContext(), R.string.pin_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    // ------------------------------------------------------------ CSV export

    private fun showExportDialog() {
        val items = arrayOf(
            getString(R.string.export_customers),
            getString(R.string.export_suppliers),
            getString(R.string.export_transactions)
        )
        val fileNames = arrayOf("customers.csv", "suppliers.csv", "transactions.csv")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_data)
            .setItems(items) { _, which ->
                pendingExport = which
                csvLauncher.launch(fileNames[which])
            }
            .show()
    }

    private fun runExport(uri: Uri) {
        val kind = pendingExport
        val appCtx = requireContext().applicationContext
        Db.async({
            val repo = Repository.get(appCtx)
            val text = when (kind) {
                0 -> CsvExport.parties(repo.getParties(false, "", false))
                1 -> CsvExport.parties(repo.getParties(true, "", false))
                else -> CsvExport.transactions(repo.getTransactionsInRange(0L, Long.MAX_VALUE))
            }
            FileOps.writeText(appCtx, uri, text)
        }) { result ->
            result.onSuccess { Toast.makeText(appCtx, R.string.export_done, Toast.LENGTH_SHORT).show() }
            result.onFailure { Toast.makeText(appCtx, R.string.export_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    // ------------------------------------------------------------ backup / restore

    private fun showBackupDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_restore)
            .setMessage(R.string.backup_note)
            .setPositiveButton(R.string.backup_create) { _, _ -> backupLauncher.launch(backupFileName()) }
            .setNegativeButton(R.string.backup_restore_action) { _, _ -> restoreLauncher.launch(arrayOf("*/*")) }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun backupFileName(): String =
        "HisabBakiPro_backup_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".db"

    private fun runBackup(uri: Uri) {
        val appCtx = requireContext().applicationContext
        Db.async({ BackupManager.createBackup(appCtx, uri) }) { result ->
            result.onSuccess { Toast.makeText(appCtx, R.string.backup_saved, Toast.LENGTH_SHORT).show() }
            result.onFailure { Toast.makeText(appCtx, R.string.backup_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    /** Step 1: validate the chosen file. Nothing is changed yet. */
    private fun inspectBackup(uri: Uri) {
        val appCtx = requireContext().applicationContext
        Db.async({ BackupManager.inspect(appCtx, uri) }) { result ->
            if (!isAdded) return@async
            result.onSuccess { info -> confirmRestore(info) }
            result.onFailure { error ->
                val msg = if (error is BackupException) error.messageRes else R.string.restore_failed
                Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Step 2: the user must confirm before any data is replaced. */
    private fun confirmRestore(info: BackupInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_confirm_title)
            .setMessage(
                getString(R.string.restore_confirm_msg, info.customers, info.suppliers, info.transactions)
            )
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> Db.async({ info.file.delete() }) { } }
            .setPositiveButton(R.string.restore_confirm_btn) { _, _ -> doRestore(info) }
            .show()
    }

    private fun doRestore(info: BackupInfo) {
        val appCtx = requireContext().applicationContext
        Db.async({ BackupManager.restore(appCtx, info) }) { result ->
            result.onSuccess {
                Toast.makeText(appCtx, R.string.restore_done, Toast.LENGTH_LONG).show()
                // Start again from Home so every screen reads the restored data.
                val intent = Intent(appCtx, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                appCtx.startActivity(intent)
            }
            result.onFailure {
                Toast.makeText(appCtx, R.string.restore_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------ language

    private fun showLanguageDialog() {
        val names = languages.map { it.second }.toTypedArray()
        val current = languages.indexOfFirst { it.first == currentTag() }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.language)
            .setSingleChoiceItems(names, current) { dialog, which ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languages[which].first)
                )
                dialog.dismiss()
            }
            .show()
    }

    private fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "en" else (locales[0]?.language ?: "en")
    }

    private fun currentLanguageName(): String =
        languages.firstOrNull { it.first == currentTag() }?.second ?: "English"

    private fun versionName(): String = try {
        val ctx = requireContext()
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
