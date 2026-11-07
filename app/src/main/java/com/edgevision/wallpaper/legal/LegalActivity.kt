package com.edgevision.wallpaper.legal

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.edgevision.wallpaper.R
import com.edgevision.wallpaper.databinding.ActivityLegalBinding
import com.google.android.material.button.MaterialButton

/**
 * About & Legal: renders the bundled legal documents without network access.
 *
 * Every document is available at any time, which is what the Play Console
 * disclosure requirements expect ("accessible at any time via Settings").
 */
class LegalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLegalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLegalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val documents = mapOf(
            binding.btnDocAi to LegalDocument.AI_DISCLOSURE,
            binding.btnDocSafety to LegalDocument.SAFETY_NOTICE,
            binding.btnDocPerformance to LegalDocument.PERFORMANCE_NOTICE,
            binding.btnDocPrivacy to LegalDocument.PRIVACY_POLICY,
            binding.btnDocTerms to LegalDocument.TERMS_AND_CONDITIONS,
        )
        documents.forEach { (button, document) ->
            button.setOnClickListener { show(document) }
        }

        val initial = LegalDocument.entries.firstOrNull { it.name == savedInstanceState?.getString(STATE_DOCUMENT) }
            ?: LegalDocument.AI_DISCLOSURE
        show(initial)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DOCUMENT, selected?.name)
    }

    private var selected: LegalDocument? = null

    private fun show(document: LegalDocument) {
        selected = document
        binding.tvLegalDocument.text = runCatching { document.read(this) }
            .getOrElse { getString(R.string.legal_document_unavailable) }
        highlight(document)
    }

    private fun highlight(document: LegalDocument) {
        val buttons = mapOf(
            LegalDocument.AI_DISCLOSURE to binding.btnDocAi,
            LegalDocument.SAFETY_NOTICE to binding.btnDocSafety,
            LegalDocument.PERFORMANCE_NOTICE to binding.btnDocPerformance,
            LegalDocument.PRIVACY_POLICY to binding.btnDocPrivacy,
            LegalDocument.TERMS_AND_CONDITIONS to binding.btnDocTerms,
        )
        buttons.forEach { (key, button: MaterialButton) ->
            button.isChecked = key == document
        }
    }

    private companion object {
        const val STATE_DOCUMENT = "legal_document"
    }
}
