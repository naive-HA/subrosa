/*
 * Copyright (C) 2022-2023 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package acab.naiveha.subrosa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import acab.naiveha.subrosa.databinding.DialogAboutBinding
import acab.naiveha.subrosa.ui.management.ManagementViewModel
import acab.naiveha.subrosa.ui.openpgp.OpenPgpViewModel
import acab.naiveha.subrosa.ui.yubiotp.OtpViewModel
import android.os.Build
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import java.security.Security
import kotlin.properties.Delegates

class MainActivity : AppCompatActivity() {
    private val logger = LoggerFactory.getLogger(MainActivity::class.java)
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController
    private lateinit var navView: NavigationView

    private val viewModel: MainViewModel by viewModels()

    private lateinit var yubikit: YubiKitManager
    private val nfcConfiguration = NfcConfiguration().timeout(15000)

    private var hasNfc by Delegates.notNull<Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)
        navController = findNavController(R.id.nav_host_fragment)
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_management, R.id.nav_yubiotp, R.id.nav_piv,
                R.id.nav_oath, R.id.nav_openpgp, R.id.nav_licenses
            ),
            drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            ViewModelProvider(this)[OpenPgpViewModel::class.java].requestClearUi()
            ViewModelProvider(this)[OtpViewModel::class.java].requestClearUi()
            ViewModelProvider(this)[ManagementViewModel::class.java].requestClearUi()
            if (destination.id == R.id.nav_management) {
                viewModel.yubiKey.value?.let {viewModel.yubiKey.postValue(it)}
            }
        }

        navView.setNavigationItemSelectedListener { item ->
            val handled = NavigationUI.onNavDestinationSelected(item, navController)
            if (handled) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                if (item.itemId == R.id.nav_management) {
                    navController.navigate(R.id.nav_management)
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
            handled || item.itemId == R.id.nav_management
        }

        val footerView = layoutInflater.inflate(R.layout.nav_footer_main, navView, false)
        navView.addView(footerView)
        val params = footerView.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.BOTTOM
        footerView.layoutParams = params

        footerView.findViewById<TextView>(R.id.footer_title_version).text =
            getString(R.string.footer_title_version, BuildConfig.VERSION_NAME)
        footerView.findViewById<TextView>(R.id.footer_github_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.github_url).toUri()))
        }

        val drawerCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                logger.info("onBackPressed: closing drawer")
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
        onBackPressedDispatcher.addCallback(this, drawerCallback)

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                logger.info("onDrawerOpened: enabling drawerCallback")
                drawerCallback.isEnabled = true
            }
            override fun onDrawerClosed(drawerView: View) {
                logger.info("onDrawerClosed: disabling drawerCallback")
                drawerCallback.isEnabled = false
            }
        })

        yubikit = YubiKitManager(this)

        viewModel.handleYubiKey.observe(this) {
            if (it) {
                logger.info("Enable listening")
                yubikit.startUsbDiscovery(UsbConfiguration()) { device ->
                    logger.info("USB device attached {}, current: {}", device, viewModel.yubiKey.value)
                    viewModel.yubiKey.postValue(device)
                    device.setOnClosed {
                        logger.info("Device removed {}", device)
                        viewModel.yubiKey.postValue(null)
                    }
                }
                try {
                    yubikit.startNfcDiscovery(nfcConfiguration, this) { device ->
                        if (viewModel.yubiKey.value is UsbYubiKeyDevice) {
                            logger.info("Ignoring NFC device connected because USB device is already connected")
                            return@startNfcDiscovery
                        }
                        logger.info("NFC device connected {}", device)
                        viewModel.yubiKey.apply {
                            runOnUiThread {
                                value = device
                                postValue(null)
                            }
                        }
                    }
                    hasNfc = true
                } catch (e: NfcNotAvailable) {
                    hasNfc = false
                    logger.error("Error starting NFC listening", e)
                }
            } else {
                logger.info("Disable listening")
                yubikit.stopNfcDiscovery(this)
                yubikit.stopUsbDiscovery()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val action = intent.action
        val mimeType = intent.type
        logger.info("handleIncomingIntent: action=$action, mimeType=$mimeType")

        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return

        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            logger.info("handleIncomingIntent: ignoring intent from history")
            return
        }

        if (action == Intent.ACTION_SEND && mimeType?.startsWith("image/") == true) {
            val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (imageUri != null) {
                viewModel.onOcrIntent(imageUri)

                if (navController.currentDestination?.id != R.id.passwordOcrFragment) {
                    val navOptions = androidx.navigation.NavOptions.Builder()
                        .setLaunchSingleTop(true)
                        .build()
                    try {
                        navController.navigate(R.id.passwordOcrFragment, null, navOptions)
                    } catch (e: Exception) {
                        logger.error("handleIncomingIntent: navigation to OCR failed", e)
                    }
                }
                setIntent(Intent())
            }
            return
        }

        val uri: Uri? = when (action) {
            Intent.ACTION_VIEW -> intent.data

            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

            else -> null
        }

        if (uri == null) return

        logger.info("PGP import intent — action=$action uri=$uri")

        if (navController.currentDestination?.id != R.id.nav_openpgp) {
            val navOptions = androidx.navigation.NavOptions.Builder()
                .setLaunchSingleTop(true)
                .build()
            navController.navigate(R.id.nav_openpgp, null, navOptions)
        }

        ViewModelProvider(this)[OpenPgpViewModel::class.java].onImportIntent(uri)
        
        setIntent(Intent())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_about -> {
                val binding = DialogAboutBinding.inflate(LayoutInflater.from(this))
                MaterialAlertDialogBuilder(this)
                    .setView(binding.root)
                    .create().apply {
                        setOnShowListener {
                            binding.version.text =  getString(R.string.version, BuildConfig.VERSION_NAME)
                            binding.aboutDescription.setOnClickListener {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(
                                    "BTC address",
                                    "1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh"
                                )
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(
                                    this@MainActivity,
                                    "Copied to clipboard: 1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }.show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()

    override fun onResume() {
        super.onResume()
        if (viewModel.handleYubiKey.value == true && hasNfc) {
            try {
                yubikit.startNfcDiscovery(nfcConfiguration, this) { device ->
                    if (viewModel.yubiKey.value is UsbYubiKeyDevice) {
                        logger.info("Ignoring NFC device connected because USB device is already connected")
                        return@startNfcDiscovery
                    }
                    logger.info("NFC device connected {}", device)
                    viewModel.yubiKey.apply {
                        runOnUiThread {
                            value = device
                            postValue(null)
                        }
                    }
                }
            } catch (e: NfcNotAvailable) {
                logger.error("NFC is not available", e)
            }
        }
    }

    override fun onPause() {
        yubikit.stopNfcDiscovery(this)
        super.onPause()
    }

    override fun onDestroy() {
        viewModel.yubiKey.value = null
        yubikit.stopUsbDiscovery()
        super.onDestroy()
    }
}
