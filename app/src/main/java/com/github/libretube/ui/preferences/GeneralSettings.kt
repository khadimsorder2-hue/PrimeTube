package com.github.libretube.ui.preferences

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.base.BasePreferenceFragment
import com.github.libretube.ui.dialogs.RequireRestartDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class GeneralSettings : BasePreferenceFragment() {

    // PrimeTube: SAF folder picker for the "Save to storage (MP4)" downloads
    private val mp4FolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            saveMp4DownloadFolder(uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.general_settings, rootKey)

        val language = findPreference<ListPreference>("language")
        if (!LocaleHelper.isPerAppLocaleSettingSupported()) {
            language?.setOnPreferenceChangeListener { _, _ ->
                RequireRestartDialog().show(
                    childFragmentManager,
                    RequireRestartDialog::class.java.name
                )
                true
            }
            val languages = requireContext().resources.getStringArray(R.array.languageCodes)
                .map { code ->
                    val locale = LocaleHelper.getLocaleFromAndroidCode(code)

                    // each language's name is displayed in its own language,
                    // e.g. 'de': 'Deutsch', 'fr': 'Francais', ...
                    locale.toString() to locale.getDisplayName(locale)
                }.sortedBy { it.second.lowercase() }
            language?.entries =
                arrayOf(requireContext().getString(R.string.systemLanguage)) + languages.map { it.second }
            language?.entryValues = arrayOf("sys") + languages.map { it.first }
        } else {
            // on newer Android versions, the language is set through Android settings

            // set displayed current settings value (i.e. current app language)
            val currentLocale = Locale.getDefault()
            language?.entries = arrayOf(currentLocale.displayLanguage)
            language?.entryValues = arrayOf(currentLocale.isO3Country)
            language?.value = currentLocale.isO3Country

            // open Android settings for per-app language preference for the app
            language?.setOnPreferenceClickListener { _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
                            .setData(Uri.fromParts("package", BuildConfig.APPLICATION_ID, null))
                    )
                } catch (e: Exception) {
                    context?.toastFromMainThread("Failed to open per-app language settings: ${e.message}")
                }
                true
            }
        }

        val autoRotation = findPreference<ListPreference>(PreferenceKeys.ORIENTATION)
        autoRotation?.setOnPreferenceChangeListener { _, _ ->
            RequireRestartDialog().show(childFragmentManager, RequireRestartDialog::class.java.name)
            true
        }

        val resetSettings = findPreference<Preference>(PreferenceKeys.RESET_SETTINGS)
        resetSettings?.setOnPreferenceClickListener {
            showResetDialog()
            true
        }

        // PrimeTube: SAF folder where "Save to storage (MP4)" files are written
        val mp4Folder = findPreference<Preference>(PreferenceKeys.MP4_DOWNLOAD_FOLDER)
        mp4Folder?.summary = getMp4FolderSummary()
        mp4Folder?.setOnPreferenceClickListener {
            mp4FolderPicker.launch(null)
            true
        }

        // PrimeTube: open Seal so the download folder (phone or SD card) and the
        // Seal download history can be managed where downloads actually happen
        val openSeal = findPreference<Preference>("open_seal")
        openSeal?.setOnPreferenceClickListener {
            val launchIntent = requireContext().packageManager
                .getLaunchIntentForPackage(PreferenceKeys.DEFAULT_EXTERNAL_DOWNLOAD_PROVIDER)
            if (launchIntent != null) {
                startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                Toast.makeText(requireContext(), R.string.seal_not_installed, Toast.LENGTH_SHORT)
                    .show()
            }
            true
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "language" && LocaleHelper.isPerAppLocaleSettingSupported()) return

        super.onDisplayPreferenceDialog(preference)
    }

    /**
     * PrimeTube: persist the picked SAF tree URI and show the folder name in the summary.
     */
    private fun saveMp4DownloadFolder(uri: Uri) {
        runCatching {
            context?.contentResolver?.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        PreferenceHelper.putString(PreferenceKeys.MP4_DOWNLOAD_FOLDER, uri.toString())

        findPreference<Preference>(PreferenceKeys.MP4_DOWNLOAD_FOLDER)?.summary =
            getMp4FolderSummary()
    }

    private fun getMp4FolderSummary(): CharSequence {
        val saved = PreferenceHelper.getString(PreferenceKeys.MP4_DOWNLOAD_FOLDER, "")
        if (saved.isBlank()) return getText(R.string.mp4_download_folder_summary)

        val folderName = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(
                requireContext(), saved.toUri()
            )?.name
        }.getOrNull()

        return getString(R.string.mp4_folder_set, folderName ?: saved)
    }

    private fun showResetDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset)
            .setMessage(R.string.reset_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ ->
                // clear default preferences
                PreferenceHelper.clearPreferences()

                // clear login token
                PreferenceHelper.setToken("")

                ActivityCompat.recreate(requireActivity())
            }
            .show()
    }
}
