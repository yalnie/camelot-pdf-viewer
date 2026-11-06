/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.camelot.fragments

import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.camelot.R
import org.lineageos.camelot.viewmodels.PdfViewModel

class CamelotPdfViewerFragment : Fragment(R.layout.fragment_camelot_pdf_viewer) {
    private val pdfViewModel by activityViewModels<PdfViewModel>()

    private var pdfView: PDFView? = null
    private var progressBar: ProgressBar? = null
    private var container: FrameLayout? = null
    private var pageIndicator: TextView? = null

    // Search bar UI
    private var searchBarContainer: LinearLayout? = null
    private var searchEditText: EditText? = null
    private var matchCountTextView: TextView? = null
    private var prevMatchButton: ImageButton? = null
    private var nextMatchButton: ImageButton? = null
    private var closeSearchButton: ImageButton? = null

    // Search state
    private var pdDocument: PDDocument? = null
    private val textStripper = PDFTextStripper()
    private var searchJob: Job? = null
    private var matchingPages = listOf<Int>()
    private var currentMatchIndex = -1

    private var totalPages: Int = 0
    private var systemInsets: WindowInsetsCompat = WindowInsetsCompat.CONSUMED

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hidePageIndicatorRunnable = Runnable {
        pageIndicator?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            pageIndicator?.visibility = View.GONE
        }?.start()
    }

    var documentUri: Uri? = null
        set(value) {
            field = value
            loadDocument()
        }

    var isTextSearchActive: Boolean = false
        set(value) {
            field = value
            if (value) {
                showSearchBar()
            } else {
                hideSearchBar()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        container = view.findViewById(R.id.pdfViewerContainer)
        pdfView = view.findViewById(R.id.pdfView)
        progressBar = view.findViewById(R.id.progressBar)
        pageIndicator = view.findViewById(R.id.pageIndicator)

        searchBarContainer = view.findViewById(R.id.searchBarContainer)
        searchEditText = view.findViewById(R.id.searchEditText)
        matchCountTextView = view.findViewById(R.id.matchCountTextView)
        prevMatchButton = view.findViewById(R.id.prevMatchButton)
        nextMatchButton = view.findViewById(R.id.nextMatchButton)
        closeSearchButton = view.findViewById(R.id.closeSearchButton)

        setupSearchUi()

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
                        if (it && isTextSearchActive) {
                            isTextSearchActive = false
                        }
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
        mainHandler.removeCallbacksAndMessages(null)
        searchJob?.cancel()
        searchJob = null
        try {
            pdDocument?.close()
        } catch (_: Exception) {}
        pdDocument = null

        pdfView = null
        progressBar = null
        container = null
        pageIndicator = null
        searchBarContainer = null
        searchEditText = null
        matchCountTextView = null
        prevMatchButton = null
        nextMatchButton = null
        closeSearchButton = null

        super.onDestroyView()
    }

    private fun loadDocument() {
        val uri = documentUri ?: return
        val currentPdfView = pdfView ?: return

        progressBar?.visibility = View.VISIBLE

        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        currentPdfView.fromUri(uri)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .defaultPage(0)
            .spacing(10)
            .enableAntialiasing(true)
            .pageFling(true)
            .pageSnap(false)
            .pageFitPolicy(FitPolicy.WIDTH)
            .fitEachPage(false)
            .nightMode(isNightMode)
            .onLoad { nbPages ->
                totalPages = nbPages
                progressBar?.visibility = View.GONE
                val name = getFileName(uri) ?: getString(R.string.pdf_document)
                pdfViewModel.setPdfName(name)
                showPageIndicator(1, nbPages)
                initPdfBox(uri)
            }
            .onPageChange { page, pageCount ->
                showPageIndicator(page + 1, pageCount)
            }
            .onPageScroll { page, _ ->
                showPageIndicator(page + 1, totalPages)
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

    private fun showPageIndicator(currentPage: Int, total: Int) {
        val indicator = pageIndicator ?: return
        if (total <= 1) return

        mainHandler.removeCallbacks(hidePageIndicatorRunnable)
        indicator.text = getString(R.string.page_number_format, currentPage, total)
        if (indicator.visibility != View.VISIBLE) {
            indicator.visibility = View.VISIBLE
            indicator.alpha = 0f
            indicator.animate().alpha(1f).setDuration(150).start()
        } else {
            indicator.animate().alpha(1f).setDuration(100).start()
        }
        mainHandler.postDelayed(hidePageIndicatorRunnable, 1800)
    }

    private fun setupSearchUi() {
        closeSearchButton?.setOnClickListener {
            isTextSearchActive = false
        }

        prevMatchButton?.setOnClickListener {
            if (matchingPages.isNotEmpty()) {
                currentMatchIndex = if (currentMatchIndex > 0) {
                    currentMatchIndex - 1
                } else {
                    matchingPages.size - 1
                }
                jumpToMatch(currentMatchIndex)
            }
        }

        nextMatchButton?.setOnClickListener {
            if (matchingPages.isNotEmpty()) {
                currentMatchIndex = (currentMatchIndex + 1) % matchingPages.size
                jumpToMatch(currentMatchIndex)
            }
        }

        searchEditText?.doAfterTextChanged { editable ->
            val query = editable?.toString()?.trim().orEmpty()
            performSearch(query)
        }

        searchEditText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(searchEditText)
                true
            } else {
                false
            }
        }
    }

    private fun showSearchBar() {
        val bar = searchBarContainer ?: return
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.translationY = -40f
        bar.animate().alpha(1f).translationY(0f).setDuration(200).start()

        searchEditText?.requestFocus()
        showKeyboard(searchEditText)
    }

    private fun hideSearchBar() {
        val bar = searchBarContainer ?: return
        hideKeyboard(searchEditText)
        bar.animate().alpha(0f).translationY(-40f).setDuration(150).withEndAction {
            bar.visibility = View.GONE
            searchEditText?.text?.clear()
            matchCountTextView?.text = ""
            matchingPages = emptyList()
            currentMatchIndex = -1
        }.start()
    }

    private fun initPdfBox(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    pdDocument?.close()
                    pdDocument = PDDocument.load(stream)
                }
            } catch (_: Exception) {}
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            matchingPages = emptyList()
            currentMatchIndex = -1
            matchCountTextView?.text = ""
            return
        }

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(250) // debounce typing
            val results = withContext(Dispatchers.IO) {
                val doc = pdDocument ?: return@withContext emptyList()
                val matches = mutableListOf<Int>()
                val pageCount = doc.numberOfPages
                for (i in 1..pageCount) {
                    ensureActive()
                    textStripper.startPage = i
                    textStripper.endPage = i
                    val pageText = try {
                        textStripper.getText(doc)
                    } catch (_: Exception) {
                        ""
                    }
                    if (pageText.contains(query, ignoreCase = true)) {
                        matches.add(i - 1)
                    }
                }
                matches
            }

            matchingPages = results
            if (results.isNotEmpty()) {
                currentMatchIndex = 0
                jumpToMatch(0)
            } else {
                currentMatchIndex = -1
                matchCountTextView?.text = getString(R.string.no_matches)
            }
        }
    }

    private fun jumpToMatch(index: Int) {
        if (index in matchingPages.indices) {
            val page = matchingPages[index]
            pdfView?.jumpTo(page, true)
            matchCountTextView?.text = getString(
                R.string.match_count_format,
                index + 1,
                matchingPages.size
            )
        }
    }

    private fun showKeyboard(view: View?) {
        view?.post {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(view: View?) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view?.windowToken, 0)
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

        searchBarContainer?.updateLayoutParams<FrameLayout.LayoutParams> {
            topMargin = 8
            marginStart = insets.left + 16
            marginEnd = insets.right + 16
        }

        pageIndicator?.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = 24
            marginEnd = insets.right + 20
        }
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
}
