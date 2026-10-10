package com.vpn0wn.android.activity

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vpn0wn.android.R
import com.vpn0wn.android.databinding.LicensesActivityBinding
import com.vpn0wn.android.util.applySafeAreaInsets
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = LicensesActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        binding.root.applySafeAreaInsets()
        binding.licensesBack.setOnClickListener { finish() }
        lifecycleScope.launch {
            val credits = try {
                withContext(Dispatchers.IO) {
                    assets.open("licenses/credits.txt").bufferedReader().use { it.readText() }
                }
            } catch (_: IOException) {
                getString(R.string.licenses_load_error)
            }
            binding.licensesContent.removeAllViews()
            // Whole notices stay below Android's accessibility text parcel limit.
            val notices = credits.split(Regex("(?m)(?=^[^\\n]+\\n={4,}\\n)")).filter { it.isNotEmpty() }
            for (notice in notices) {
                val text = layoutInflater.inflate(R.layout.licenses_notice, binding.licensesContent, false) as TextView
                text.text = notice
                binding.licensesContent.addView(text)
            }
        }
    }
}
