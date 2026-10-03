package org.polycare.app.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.polycare.app.households.SampleDataGenerator
import javax.inject.Inject

@HiltViewModel
class MoreViewModel @Inject constructor(
    val sampleDataGenerator: SampleDataGenerator
) : ViewModel()