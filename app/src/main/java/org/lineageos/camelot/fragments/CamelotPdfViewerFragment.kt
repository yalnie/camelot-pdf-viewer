/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.camelot.fragments

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.camelot.R
import org.lineageos.camelot.viewmodels.PdfViewModel

class CamelotPdfViewerFragment : Fragment(R.layout.fragment_camelot_pdf_viewer) {
    private val pdfViewModel by activityViewModels<PdfViewModel>()

    private var pdfView: PDFView? = null
    private var progressBar: ProgressBar? = null
    private var container: FrameLayout? = null

    private var totalPages: Int = 0
    private var systemInsets: WindowInsetsCompat = WindowInsetsCompat.CONSUMED

    var documentUri: Uri? = null
        set(value) {
            field = value
            loadDocument()
        }

    var isTextSearchActive: Boolean = false
        set(value) {
            field = value
            if (value) {
                showJumpToPageDialog()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        container = view.findViewById(R.id.pdfViewerContainer)
        pdfView = view.findViewById(R.id.pdfView)
        progressBar = view.findViewById(R.id.progressBar)

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            systemInsets = windowInsets
            updateInsets()
            windowInsets
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    pdfViewModel.toolbarHeight.collectLatest {
                        updateInsets()
                    }
                }
                launch {
                    pdfViewModel.immersiveMode.collectLatest {
                        updateInsets()
                    }
                }
            }
        }

        if (documentUri != null) {
            loadDocument()
        }
    }

    override fun onDestroyView() {
        pdfView = null
        progressBar = null
        container = null
        super.onDestroyView()
    }

    private fun loadDocument() {
        val uri = documentUri ?: return
        val currentPdfView = pdfView ?: return

        progressBar?.visibility = View.VISIBLE

        currentPdfView.fromUri(uri)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .defaultPage(0)
            .spacing(8)
            .scrollHandle(DefaultScrollHandle(requireContext()))
            .onLoad { nbPages ->
                totalPages = nbPages
                progressBar?.visibility = View.GONE
                val name = getFileName(uri) ?: getString(R.string.pdf_document)
                pdfViewModel.setPdfName(name)
            }
            .onError {
                progressBar?.visibility = View.GONE
                pdfViewModel.setPdfName(null)
            }
            .onTap {
                pdfViewModel.setImmersiveMode(!pdfViewModel.immersiveMode.value)
                true
            }
            .load()
    }

    private fun updateInsets() {
        val insets = systemInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val isImmersive = pdfViewModel.immersiveMode.value
        val topPadding = if (isImmersive) 0 else (insets.top + pdfViewModel.toolbarHeight.value)
        val bottomPadding = if (isImmersive) 0 else insets.bottom

        container?.updatePadding(
            left = insets.left,
            top = topPadding,
            right = insets.right,
            bottom = bottomPadding
        )
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                requireContext().contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) {
                                return name
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun showJumpToPageDialog() {
        if (totalPages <= 0) return

        val context = requireContext()
        val textInputLayout = TextInputLayout(context).apply {
            setPadding(48, 16, 48, 0)
        }
        val input = TextInputEditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            val currentPageNum = (pdfView?.currentPage ?: 0) + 1
            setText(currentPageNum.toString())
            setSelection(text?.length ?: 0)
        }
        textInputLayout.addView(input)

        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.find_in_page))
            .setMessage("1 – $totalPages")
            .setView(textInputLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pageNum = input.text?.toString()?.toIntOrNull()
                if (pageNum != null && pageNum in 1..totalPages) {
                    pdfView?.jumpTo(pageNum - 1, true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
