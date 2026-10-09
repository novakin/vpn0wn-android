package com.wireguard.android.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.databinding.LicensesActivityBinding
import com.wireguard.android.util.applySafeAreaInsets
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
            binding.licensesText.text = try {
                withContext(Dispatchers.IO) {
                    assets.open("licenses/credits.txt").bufferedReader().use { it.readText() }
                }
            } catch (_: IOException) {
                getString(R.string.licenses_load_error)
            }
        }
    }
}
