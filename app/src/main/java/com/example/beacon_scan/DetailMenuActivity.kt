package com.example.beacon_scan

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

class DetailMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_detail_menu)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<Button>(R.id.btnSsidMeasurement).setOnClickListener {
            startActivity(Intent(this, SsidMeasurementActivity::class.java))
        }

        findViewById<Button>(R.id.btnConnectionMeasurement).setOnClickListener {
            startActivity(Intent(this, ConnectionMeasurementActivity::class.java))
        }

        findViewById<Button>(R.id.btnSecurityMeasurement).setOnClickListener {
            startActivity(Intent(this, SecurityMeasurementActivity::class.java))
        }

        findViewById<Button>(R.id.btnThroughputMeasurement).setOnClickListener {
            startActivity(Intent(this, ThroughputActivity::class.java))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
