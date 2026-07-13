package com.andsi.airlyrics.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var graph: MainGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        graph = MainGraph(this)
        graph.beforeSuperOnCreate()
        super.onCreate(savedInstanceState)
        graph.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        graph.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::graph.isInitialized) {
            graph.onSaveInstanceState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::graph.isInitialized) {
            graph.onDestroy()
        }
        super.onDestroy()
    }
}
