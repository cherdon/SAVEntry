package com.izho.saveentry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.izho.saveentry.data.Location
import com.izho.saveentry.data.VisitWithLocation
import com.izho.saveentry.data.getAppDatabase
import com.izho.saveentry.viewmodel.CheckInOrOutViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.*


fun Date.toDisplayString() : String {
    val pattern = "dd MMM YYYY, h:mm a"
    val simpleDateFormat = SimpleDateFormat(pattern)
    return simpleDateFormat.format(this)
}


class CheckInOrOutActivity : AppCompatActivity() {
    private lateinit var viewModel: CheckInOrOutViewModel
    private lateinit var action: String

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_in_or_out)


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            // Important: have to do the following in order to show without unlocking
            this.window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        action = intent.extras?.getString("action") ?: "checkIn"

        val url = intent.extras?.getString("url")
        val visitId = intent.extras?.getLong("visitId")
        var visitWithLocation:VisitWithLocation? = null

        if (visitId != null) {
            GlobalScope.launch {
                visitWithLocation = getAppDatabase(this@CheckInOrOutActivity, resetDb = false).dao.getVisitWithLocationById(visitId)
            }
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar_check_in_or_out).apply {
            title = if (action == "checkIn") "Checking In..." else "Checking Out..."
        }
        setSupportActionBar(toolbar)

        supportActionBar?.let {
            it.setDisplayHomeAsUpEnabled(true)
            it.setDisplayShowHomeEnabled(true)
        }

        val factory = CheckInOrOutViewModel.Factory(application, url!!, action, visitId)
        viewModel = ViewModelProvider(this, factory).get(CheckInOrOutViewModel::class.java)

        webView = findViewById(R.id.webview_browser)
        if(isInternetAvailable() || (visitWithLocation != null && !(visitWithLocation!!.visit.isOfflineCheckIn))) {
            webView.apply {
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setAppCacheEnabled(true)

                webViewClient = viewModel.webViewClient
                webChromeClient = viewModel.webChromeClient

                loadUrl(url)
            }
        } else {
            var newLayoutParamms = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
            newLayoutParamms.topToBottom = (webView.layoutParams as ConstraintLayout.LayoutParams).topToBottom
            webView.destroy()
            val container = findViewById<ConstraintLayout>(R.id.constraint_layout_check_in_or_out)
            val offlineCheckInOrOut:View = this.layoutInflater.inflate(R.layout.offline_checkin, container, false)
            container.addView(offlineCheckInOrOut, newLayoutParamms)
            if(action == "checkIn") {
                toolbar.title = "Offline check in..."
                offlineCheckInOrOut.findViewById<ImageView>(R.id.imageView).setImageDrawable(getDrawable(R.drawable.checkin_screenshot))
                Toast.makeText(this, "No internet, using offline check in", Toast.LENGTH_SHORT).show()
                offlineCheckInOrOut.findViewById<TextView>(R.id.location_name).text = intent.extras?.getString("venueName") ?: ""
                offlineCheckInOrOut.viewTreeObserver.addOnGlobalLayoutListener(object:ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        viewModel.checkInToLocation(offlineCheckInOrOut, Location(
                            intent.extras?.getString("locationId") ?: "",
                            intent.extras?.getString("organization") ?: "",
                            intent.extras?.getString("venueName") ?: "",
                            intent.extras?.getString("url") ?: ""
                        ), true)
                        offlineCheckInOrOut.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                })
            } else {
                toolbar.title = "Offline check out..."
                offlineCheckInOrOut.findViewById<ImageView>(R.id.imageView).setImageDrawable(getDrawable(R.drawable.checkout_screenshot))
                Toast.makeText(this, "No internet, using offline check out", Toast.LENGTH_SHORT).show()
                viewModel.checkOutOfLocation(offlineCheckInOrOut)
                GlobalScope.launch {
                    if (visitId != null) {
                        offlineCheckInOrOut.findViewById<TextView>(R.id.location_name).text = getAppDatabase(this@CheckInOrOutActivity, resetDb = false).dao.getVisitWithLocationById(visitId).location.venueName
                    }
                }
            }

            offlineCheckInOrOut.findViewById<TextView>(R.id.time).text = Date().toDisplayString()

        }

        val baseLayout = findViewById<View>(R.id.constraint_layout_check_in_or_out)
        viewModel.errorMessage.observe(this, Observer { msg ->
            msg?.let {
                Snackbar.make(baseLayout, msg, Snackbar.LENGTH_SHORT).show()
                viewModel.clearErrorMessage()
            }
        })

        viewModel.createdVisit.observe(this, Observer { data ->
            data?.let {
                when(action) {
                    "checkIn" -> {
                        // Create intent for checking out
                        val intent = Intent(this, CheckInOrOutActivity::class.java)
                        intent.putExtra("action", "checkOut")
                        intent.putExtra("visitId", data.visit.visitId)
                        intent.putExtra("url", data.location.url)

                        val pendingIntent = TaskStackBuilder.create(this).run {
                            addNextIntentWithParentStack(intent)
                            getPendingIntent(data.visit.notificationId, PendingIntent.FLAG_UPDATE_CURRENT)
                        }

                        val location = data.location.venueName
                        val id = getString(R.string.channel_id)
                        val builder = NotificationCompat.Builder(this, id)
                            .setColorized(true)
                            .setColor(getColor(R.color.colorPrimarySurface))
                            .setSmallIcon(R.drawable.ic_store)
                            .setContentTitle("Checked in to $location")
                            .setContentText("Tap this notification to checkout_screenshot from here.")
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setOngoing(true)
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)

                        with(NotificationManagerCompat.from(this)) {
                            notify(data.visit.notificationId, builder.build())
                        }

                        Snackbar.make(baseLayout, "Check-in Completed", Snackbar.LENGTH_SHORT).show()
                    }

                    "checkOut" -> {
                        with(NotificationManagerCompat.from(this)) {
                            cancel(data.visit.notificationId)
                        }
                        Snackbar.make(baseLayout, "Checkout Completed", Snackbar.LENGTH_SHORT).show()
                    }
                }

                viewModel.completeEventConfirm()
            }
        })

        viewModel._action.observe(this, Observer { newAction ->
            action = newAction
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_check_in_or_out, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.button_check_in_or_out_confirm -> finish()
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onStop() {
        super.onStop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            // Important: have to do the following in order to show without unlocking
            this.window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Each time the user put the app the background, they should re-initiate the flow
        // to prevent ending up in some weird state.
        finish()

    }

    fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = cm.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }
    
    companion object {
        private const val TAG = "BrowserActivity"
    }
}
