package org.schabi.speachrecognitionservicetester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.schabi.speachrecognitionservicetester.ui.TestListScreen
import org.schabi.speachrecognitionservicetester.ui.theme.SpeachRecognitionServiceTesterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpeachRecognitionServiceTesterTheme {
                TestListScreen()
            }
        }
    }
}
