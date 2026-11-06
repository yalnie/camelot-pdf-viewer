/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.camelot

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class CamelotApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Observe dynamic colors changes
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Initialize PDFBox for in-page search
        PDFBoxResourceLoader.init(this)
    }
}
