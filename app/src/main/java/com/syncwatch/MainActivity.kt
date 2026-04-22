package com.syncwatch

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.syncwatch.databinding.ActivityMainBinding
import com.syncwatch.ui.home.HomeFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Only set the home fragment on a fresh start (not a config change)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }
    }

    /** Called by WatchFragment when the user leaves a room. */
    fun navigateHome() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, HomeFragment())
            .commit()
    }
}
