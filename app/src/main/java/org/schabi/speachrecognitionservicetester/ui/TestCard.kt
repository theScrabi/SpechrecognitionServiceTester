package org.schabi.speachrecognitionservicetester.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.schabi.speachrecognitionservicetester.model.TestCase
import org.schabi.speachrecognitionservicetester.model.TestResult
import org.schabi.speachrecognitionservicetester.model.TestStatus
import org.schabi.speachrecognitionservicetester.model.androidVersionName

/** Card colours per status: white = not tested, green = passed, red = failed, gray = not testable. */
data class StatusColors(val container: Color, val content: Color, val accent: Color)

fun statusColors(status: TestStatus): StatusColors = when (status) {
    TestStatus.NotTested, TestStatus.Running -> StatusColors(Color.White, Color(0xFF1C1B1F), Color(0xFF79747E))
    TestStatus.Passed -> StatusColors(Color(0xFFC8E6C9), Color(0xFF0D3B12), Color(0xFF2E7D32))
    TestStatus.Failed -> StatusColors(Color(0xFFFFCDD2), Color(0xFF5F0B0B), Color(0xFFC62828))
    TestStatus.Unsupported -> StatusColors(Color(0xFFE0E0E0), Color(0xFF616161), Color(0xFF9E9E9E))
}

/** "Not testable" explanation for a test whose API level the phone lacks. */
fun unsupportedText(test: TestCase) =
    "Requires Android ${androidVersionName(test.minSdk)} (API ${test.minSdk}) - this phone runs " +
            "API ${Build.VERSION.SDK_INT}. Not testable on this phone."

@Composable
fun TestCard(test: TestCase, result: TestResult, onClick: () -> Unit) {
    val colors = statusColors(result.status)
    val unsupported = result.status == TestStatus.Unsupported
    Card(
        onClick = onClick,
        enabled = !unsupported,
        colors = CardDefaults.cardColors(
            containerColor = colors.container,
            contentColor = colors.content,
            disabledContainerColor = colors.container,
            disabledContentColor = colors.content,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (unsupported) 0.dp else 2.dp),
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (unsupported) 0.7f else 1f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(result.status, colors)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(test.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    ApiChip(test.minSdk, colors)
                }
                Text(
                    test.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unsupported) {
                    Text(unsupportedText(test), style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                }
                result.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: TestStatus, colors: StatusColors) {
    val modifier = Modifier.size(36.dp)
    when (status) {
        TestStatus.Passed -> Icon(
            Icons.Filled.Check, contentDescription = "Passed", tint = Color.White,
            modifier = modifier.background(colors.accent, CircleShape).padding(6.dp),
        )
        TestStatus.Failed -> Icon(
            Icons.Filled.Close, contentDescription = "Failed", tint = Color.White,
            modifier = modifier.background(colors.accent, CircleShape).padding(6.dp),
        )
        TestStatus.Running -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        TestStatus.Unsupported -> Icon(
            Icons.Filled.Lock, contentDescription = "Not testable", tint = colors.accent,
            modifier = modifier.padding(6.dp),
        )
        TestStatus.NotTested -> Box(modifier.border(2.dp, colors.accent, CircleShape))
    }
}

@Composable
private fun ApiChip(minSdk: Int, colors: StatusColors) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.accent),
    ) {
        Text(
            if (minSdk <= 8) "all APIs" else "API $minSdk+",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
