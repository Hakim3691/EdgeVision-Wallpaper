package com.edgevision.wallpaper.legal

import android.content.Context
import androidx.annotation.StringRes
import com.edgevision.wallpaper.R

/**
 * The legal documents shipped with the app.
 *
 * The text lives as plain assets under `assets/legal` so it can be reviewed and
 * diffed in the repository, and is displayed verbatim by [LegalActivity]. Keeping
 * a single copy avoids the app and the store listing drifting apart.
 */
enum class LegalDocument(
    val assetName: String,
    @StringRes val titleRes: Int,
) {
    AI_DISCLOSURE("ai_disclosure.txt", R.string.legal_doc_ai),
    SAFETY_NOTICE("safety_notice.txt", R.string.legal_doc_safety),
    PERFORMANCE_NOTICE("performance_notice.txt", R.string.legal_doc_performance),
    PRIVACY_POLICY("privacy_policy.txt", R.string.legal_doc_privacy),
    TERMS_AND_CONDITIONS("terms_and_conditions.txt", R.string.legal_doc_terms),
    ;

    fun read(context: Context): String =
        context.assets.open("$ASSET_DIRECTORY/$assetName")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    companion object {
        const val ASSET_DIRECTORY = "legal"
    }
}
