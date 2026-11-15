package com.andsi.airlyrics.app

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.app.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {
    internal val mainViewModel: MainViewModel by viewModels {
        MainViewModel.factory(applicationContext)
    }
    internal lateinit var graph: MainGraph
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = MainGraph(this, mainViewModel)
        graph.onCreate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::graph.isInitialized) graph.uiHost.onNightModeChanged()
    }

    override fun onNightModeChanged(mode: Int) {
        super.onNightModeChanged(mode)
        if (::graph.isInitialized) graph.uiHost.onNightModeChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::graph.isInitialized) graph.uiHost.interactionUi.capture()
        super.onSaveInstanceState(outState)
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
