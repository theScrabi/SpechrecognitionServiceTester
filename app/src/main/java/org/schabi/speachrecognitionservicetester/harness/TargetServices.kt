package org.schabi.speachrecognitionservicetester.harness

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.speech.RecognitionService
import org.xmlpull.v1.XmlPullParser

/** An installed [RecognitionService] the tests can run against. */
data class RecognizerTarget(
    val component: ComponentName,
    val label: String,
    val exported: Boolean,
    /** `android:settingsActivity` of the `android.speech` meta-data; `null` without meta-data. */
    val settingsActivity: String?,
    val hasMetaData: Boolean,
)

object TargetServices {
    val PARAKEETYPE = ComponentName(
        "org.schabi.parakeetype",
        "org.schabi.parakeetype.recognition.ParakeetypeRecognitionService",
    )

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** All installed recognition services, Parakeetype first. */
    fun query(context: Context): List<RecognizerTarget> {
        val pm = context.packageManager
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val infos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(
                intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        return infos.map { it.serviceInfo.toTarget(pm) }
            .sortedBy { if (it.component == PARAKEETYPE) 0 else 1 }
    }

    private fun ServiceInfo.toTarget(pm: PackageManager): RecognizerTarget {
        val parser = loadXmlMetaData(pm, RecognitionService.SERVICE_META_DATA)
        var settings: String? = null
        if (parser != null) {
            try {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "recognition-service") {
                        settings = parser.getAttributeValue(ANDROID_NS, "settingsActivity") ?: ""
                        break
                    }
                }
            } finally {
                parser.close()
            }
        }
        return RecognizerTarget(
            component = ComponentName(packageName, name),
            label = loadLabel(pm).toString(),
            exported = exported,
            settingsActivity = settings,
            hasMetaData = parser != null,
        )
    }
}
