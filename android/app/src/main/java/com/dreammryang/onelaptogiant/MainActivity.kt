package com.dreammryang.onelaptogiant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dreammryang.onelaptogiant.ui.AppNav
import com.dreammryang.onelaptogiant.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        setContent {
            AppTheme {
                AppNav(container)
            }
        }
    }
}
