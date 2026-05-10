package com.clipboardspeech

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.clipboardspeech.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity(), HomeFragment.NavigationCallback {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { loadFragment(HomeFragment()); true }
                R.id.nav_history -> { loadFragment(HistoryFragment()); true }
                R.id.nav_ai -> { loadFragment(AiConfigFragment()); true }
                R.id.nav_process_note -> { loadFragment(ProcessNoteConfigFragment()); true }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    override fun navigateToHistory() {
        binding.bottomNav.selectedItemId = R.id.nav_history
    }
}
