package com.hereliesaz.cleanunderwear

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CleanUnderwearApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Once per process, here rather than in an Activity: an Activity-scoped handler was
        // chained again on every rotation and held each dead Activity.
        com.hereliesaz.cleanunderwear.util.CrashReport.install(this)
        // AzTextBox saves every submitted value in a file as autocomplete history. Here that is
        // contacts' names, numbers and emails, in files Delete everything does not reach: a limit
        // of 0 stops it recording, and the files earlier versions wrote are removed.
        com.hereliesaz.aznavrail.AzTextBoxDefaults.setSuggestionLimit(0)
        com.hereliesaz.cleanunderwear.util.TextBoxHistory.purge(filesDir)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
