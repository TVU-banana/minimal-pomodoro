package com.akira.clock.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.export.ClockBackupService
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { runDataOperation { service().exportArchive(it) } } }
    private val createCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { runDataOperation { service().exportCsv(it) } } }
    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { runDataOperation(success = R.string.import_success) { service().importArchive(it) } } }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val preferences = requireContext().getSharedPreferences(PREFERENCES, 0)
        view.findViewById<SwitchCompat>(R.id.reduce_motion).apply {
            isChecked = preferences.getBoolean(KEY_REDUCE_MOTION, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit { putBoolean(KEY_REDUCE_MOTION, checked) }
            }
        }
        view.findViewById<SwitchCompat>(R.id.keep_screen_on).apply {
            isChecked = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit { putBoolean(KEY_KEEP_SCREEN_ON, checked) }
            }
        }
        view.findViewById<Button>(R.id.export_backup).setOnClickListener {
            createBackup.launch("clock-${java.time.LocalDate.now()}.clockbackup")
        }
        view.findViewById<Button>(R.id.export_csv).setOnClickListener {
            createCsv.launch("clock-sessions-${java.time.LocalDate.now()}.csv")
        }
        view.findViewById<Button>(R.id.import_backup).setOnClickListener {
            openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
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

    companion object {
        const val PREFERENCES = "clock_settings"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
