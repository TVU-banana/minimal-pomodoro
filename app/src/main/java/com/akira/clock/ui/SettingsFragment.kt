package com.akira.clock.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.ThemeMode
import com.akira.clock.export.ClockBackupService
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Owns Storage Access Framework data operations. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { runDataOperation { service().exportArchive(it) } } }

    private val createDurableArchive = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let { destination ->
            runDataOperation(success = R.string.durable_archive_success) {
                runCatching {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(
                        destination,
                        flags,
                    )
                }
                service().exportArchive(destination)
                val app = requireActivity().application as ClockApplication
                app.settings.durableArchiveUri = destination.toString()
            }
        }
    }

    private val createCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { runDataOperation { service().exportCsv(it) } } }

    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            runDataOperation(success = R.string.import_success) { service().importArchive(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupThemeSelector(view)

        val app = requireActivity().application as ClockApplication
        view.findViewById<Button>(R.id.connect_band).apply {
            visibility = if (app.bandConnection.available) View.VISIBLE else View.GONE
            setOnClickListener {
                isEnabled = false
                // The application scope keeps SDK registration alive across tab replacement.
                app.bandCommandScope.launch {
                    val connected = runCatching { app.bandConnection.connect() }.isSuccess
                    isEnabled = true
                    val message = if (connected) {
                        R.string.band_listener_ready
                    } else {
                        R.string.band_connect_failed
                    }
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        view.findViewById<Button>(R.id.export_backup).setOnClickListener {
            createBackup.launch("clock-${LocalDate.now()}.clockbackup")
        }
        view.findViewById<Button>(R.id.save_durable_archive).setOnClickListener {
            createDurableArchive.launch("clock-archive-${LocalDate.now()}.clockbackup")
        }
        view.findViewById<Button>(R.id.delete_durable_archive).setOnClickListener {
            confirmDurableArchiveDeletion()
        }
        view.findViewById<Button>(R.id.export_csv).setOnClickListener {
            createCsv.launch("clock-sessions-${LocalDate.now()}.csv")
        }
        view.findViewById<Button>(R.id.import_backup).setOnClickListener {
            openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        view.findViewById<Button>(R.id.read_more).setOnClickListener {
            ExtendedReadingDialogFragment().show(childFragmentManager, READING_DIALOG_TAG)
        }
    }

    private fun setupThemeSelector(view: View) {
        val app = requireActivity().application as ClockApplication
        val radioGroup = view.findViewById<RadioGroup>(R.id.theme_radio_group)
        // A cached screen may restore a checked ID from the previous theme.
        radioGroup.isSaveEnabled = false
        for (index in 0 until radioGroup.childCount) {
            radioGroup.getChildAt(index).isSaveEnabled = false
        }

        when (app.settings.themeMode) {
            ThemeMode.SYSTEM -> {
                val dark = resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
                app.settings.themeMode = mode
                radioGroup.check(if (dark) R.id.theme_dark else R.id.theme_light)
            }
            ThemeMode.LIGHT -> radioGroup.check(R.id.theme_light)
            ThemeMode.DARK -> radioGroup.check(R.id.theme_dark)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_light -> ThemeMode.LIGHT
                R.id.theme_dark -> ThemeMode.DARK
                else -> ThemeMode.LIGHT
            }
            app.settings.themeMode = mode
            app.applyThemeMode(mode)
        }
    }

    private fun service(): ClockBackupService {
        val app = requireActivity().application as ClockApplication
        return ClockBackupService(requireContext().applicationContext, app.database)
    }

    private fun runDataOperation(
        success: Int = R.string.export_success,
        operation: suspend () -> Unit,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { operation() }
                .onSuccess {
                    Toast.makeText(requireContext(), success, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    val detail = it.message ?: it.javaClass.simpleName
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.data_operation_failed, detail),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun confirmDurableArchiveDeletion() {
        val settings = (requireActivity().application as ClockApplication).settings
        val archive = settings.durableArchiveUri
        if (archive == null) {
            Toast.makeText(
                requireContext(), R.string.durable_archive_missing, Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_durable_archive_title)
            .setMessage(R.string.delete_durable_archive_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_durable_archive_confirm) { _, _ ->
                runDataOperation(success = R.string.delete_durable_archive_success) {
                    requireContext().contentResolver.delete(Uri.parse(archive), null, null)
                    settings.durableArchiveUri = null
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.applyClockDialogTheme()
        }
        dialog.show()
    }

    private companion object {
        const val READING_DIALOG_TAG = "extendedReading"
    }
}
