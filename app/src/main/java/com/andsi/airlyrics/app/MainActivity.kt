package com.andsi.airlyrics.app

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.app.viewmodel.MainViewModel
import com.andsi.airlyrics.i18n.LanguageSettingsStore

class MainActivity : AppCompatActivity() {
    internal val mainViewModel: MainViewModel by viewModels {
        MainViewModel.factory(applicationContext)
    }
    internal lateinit var graph: MainGraph
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        LanguageSettingsStore.applyAppLocale(this)
        super.onCreate(savedInstanceState)
        graph = MainGraph(this, mainViewModel)
        graph.onCreate()
    }

    override fun onStart() {
        super.onStart()
        graph.onStart()
    }

    override fun onResume() {
        super.onResume()
        graph.onResume()
    }

    override fun onStop() {
        if (::graph.isInitialized) {
            graph.onStop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::graph.isInitialized) {
            graph.onDestroy()
        }
        super.onDestroy()
    }
}
