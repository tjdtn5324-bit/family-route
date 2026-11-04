package ai.familyroute.demo

import ai.familyroute.demo.map.MockupMapRenderer
import ai.familyroute.demo.ui.AppRoot
import ai.familyroute.demo.ui.TripViewModel
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: TripViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.ui.collectAsStateWithLifecycle()
            AppRoot(state, MockupMapRenderer, vm::onAction)
        }
    }
}
