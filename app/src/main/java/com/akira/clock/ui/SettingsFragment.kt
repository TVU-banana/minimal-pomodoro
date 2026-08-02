package com.akira.clock.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.ThemeMode
import com.akira.clock.export.ClockBackupService
import kotlinx.coroutines.launch

/** Owns Storage Access Framework data operations. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { runDataOperation { service().exportArchive(it) } } }
    private val createDurableArchive = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { destination ->
        runDataOperation(success = R.string.durable_archive_success) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    destination,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            service().exportArchive(destination)
            (requireActivity().application as ClockApplication).settings.durableArchiveUri = destination.toString()
        }
    } }
    private val createCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { runDataOperation { service().exportCsv(it) } } }
    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { runDataOperation(success = R.string.import_success) { service().importArchive(it) } } }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupThemeSelector(view)

        view.findViewById<Button>(R.id.export_backup).setOnClickListener {
            createBackup.launch("clock-${java.time.LocalDate.now()}.clockbackup")
        }
        view.findViewById<Button>(R.id.save_durable_archive).setOnClickListener {
            createDurableArchive.launch("clock-archive-${java.time.LocalDate.now()}.clockbackup")
        }
        view.findViewById<Button>(R.id.delete_durable_archive).setOnClickListener {
            confirmDurableArchiveDeletion()
        }
        view.findViewById<Button>(R.id.export_csv).setOnClickListener {
            createCsv.launch("clock-sessions-${java.time.LocalDate.now()}.csv")
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

        when (app.settings.themeMode) {
            ThemeMode.SYSTEM -> radioGroup.check(R.id.theme_system)
            ThemeMode.LIGHT -> radioGroup.check(R.id.theme_light)
            ThemeMode.DARK -> radioGroup.check(R.id.theme_dark)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_system -> ThemeMode.SYSTEM
                R.id.theme_light -> ThemeMode.LIGHT
                R.id.theme_dark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
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
                .onSuccess { Toast.makeText(requireContext(), success, Toast.LENGTH_SHORT).show() }
                .onFailure {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.data_operation_failed, it.message ?: it.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun confirmDurableArchiveDeletion() {
        val settings = (requireActivity().application as ClockApplication).settings
        val archive = settings.durableArchiveUri
        if (archive == null) {
            Toast.makeText(requireContext(), R.string.settings_durable_archive_hint, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_durable_archive_title)
            .setMessage(R.string.delete_durable_archive_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_durable_archive_confirm) { _, _ ->
                runDataOperation(success = R.string.delete_durable_archive_success) {
                    requireContext().contentResolver.delete(android.net.Uri.parse(archive), null, null)
                    settings.durableArchiveUri = null
                }
            }
            .show()
    }

    private companion object {
        const val READING_DIALOG_TAG = "extendedReading"
    }
}
