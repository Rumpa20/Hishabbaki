package com.hisabbaki.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fab: FloatingActionButton
    private var pendingQuery: String = ""
    private var currentTab: Int = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNav)
        fab = findViewById(R.id.fabAdd)

        fab.setOnClickListener {
            when (currentTab) {
                R.id.nav_suppliers -> openPartyEditor(true)
                R.id.nav_customers -> openPartyEditor(false)
                else -> chooseNewParty()
            }
        }

        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        if (savedInstanceState == null) {
            bottomNav.menu.findItem(R.id.nav_home).isChecked = true
            showTab(R.id.nav_home)
        }
    }

    // After recreation (theme / language change) the selected tab is restored here,
    // so this is the right place to sync the floating button.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currentTab = bottomNav.selectedItemId
        updateFab(currentTab)
    }

    /** Called by the Home search box: opens the Customers tab with the search text filled in. */
    fun openCustomers(query: String) {
        pendingQuery = query
        if (bottomNav.selectedItemId == R.id.nav_customers) {
            showTab(R.id.nav_customers)
        } else {
            bottomNav.selectedItemId = R.id.nav_customers
        }
    }

    private fun openPartyEditor(isSupplier: Boolean) {
        val intent = Intent(this, PartyEditActivity::class.java)
        intent.putExtra(PartyEditActivity.EXTRA_SUPPLIER, isSupplier)
        startActivity(intent)
    }

    private fun chooseNewParty() {
        val items = arrayOf(getString(R.string.add_customer), getString(R.string.add_supplier))
        MaterialAlertDialogBuilder(this)
            .setItems(items) { _, which -> openPartyEditor(which == 1) }
            .show()
    }

    private fun showTab(id: Int) {
        currentTab = id
        val fragment: Fragment = when (id) {
            R.id.nav_customers -> {
                val f = PartiesFragment.newInstance(false, pendingQuery)
                pendingQuery = ""
                f
            }
            R.id.nav_suppliers -> PartiesFragment.newInstance(true, "")
            R.id.nav_reports -> ReportsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        updateFab(id)
    }

    private fun updateFab(id: Int) {
        if (id == R.id.nav_home || id == R.id.nav_customers || id == R.id.nav_suppliers) {
            fab.show()
        } else {
            fab.hide()
        }
    }
}
