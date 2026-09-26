package com.owen282000.lifedashboard.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Says what this app is; Health Connect links here from its permission screens. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            TextView(this).apply {
                setText(R.string.fixture_explanation)
                setPadding(padding, padding, padding, padding)
            }
        )
    }
}
