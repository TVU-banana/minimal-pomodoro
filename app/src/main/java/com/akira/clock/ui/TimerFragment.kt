package com.akira.clock.ui

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.alarm.TimerCoordinator
import com.akira.clock.data.BackgroundAssetRepository
import com.akira.clock.data.ClockRepository
import com.akira.clock.data.TimerStyle
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
import com.akira.clock.ui.render.TimerRendererController
import com.akira.clock.ui.render.TimerUiState
import kotlinx.coroutines.launch

/**
 * Android View adapter for the timer screen.
 *
 * Countdown calculations, renderer construction, metadata lookup, and background persistence are
 * delegated to focused collaborators. This Fragment owns only lifecycle wiring, user input, and
 * presentation updates.
 */
class TimerFragment : Fragment(R.layout.fragment_timer) {
    private lateinit var backgroundAssets: BackgroundAssetRepository
    private lateinit var coordinator: TimerCoordinator
    private lateinit var metadataLoader: TimerDisplayMetadataLoader
    private lateinit var rendererController: TimerRendererController
    private lateinit var setup: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var stageLabel: TextView
    private lateinit var taskTitle: TextView
    private lateinit var error: TextView
    private lateinit var pauseResume: Button
    private lateinit var startButton: Button

    private var snapshot: TimerSnapshot? = null
    private var taskName = ""
    private var categoryName = ""
    private var backgroundAssetId: String? = null
    private var backgroundFile: java.io.File? = null
    private var timerStyle = TimerStyle.DIGITAL

    private val isLowRamDevice: Boolean
        get() = (requireContext().applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice

    private val ticker = object : Runnable {
        override fun run() {
            renderSnapshot()
            view?.postDelayed(this, UI_UPDATE_INTERVAL_MS)
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showError(getString(R.string.notification_permission_required))
    }

    private val backgroundPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) importBackground(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as ClockApplication
        coordinator = TimerCoordinator.get(requireContext())
        backgroundAssets = BackgroundAssetRepository(requireContext(), app.database)
        metadataLoader = TimerDisplayMetadataLoader(app.database, requireContext().filesDir)
        rendererController = TimerRendererController(
            context = requireContext(),
            host = view.findViewById<FrameLayout>(R.id.timer_renderer_host),
            reduceMotion = shouldReduceMotion(),
        )
        bindViews(view)
        showRenderer(TimerStyle.DIGITAL)
        bindActions(view)
        observeTimer()
        viewLifecycleOwner.lifecycleScope.launch { coordinator.restore() }
    }

    override fun onStart() {
        super.onStart()
        rendererController.start()
        view?.post(ticker)
        applyKeepScreenOn()
    }

    override fun onStop() {
        view?.removeCallbacks(ticker)
        rendererController.stop()
        requireActivity().window.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        super.onStop()
    }

    private fun bindViews(view: View) {
        setup = view.findViewById(R.id.timer_setup)
        controls = view.findViewById(R.id.timer_controls)
        stageLabel = view.findViewById(R.id.timer_stage)
        taskTitle = view.findViewById(R.id.timer_task_title)
        error = view.findViewById(R.id.timer_error)
        pauseResume = view.findViewById(R.id.timer_pause_resume)
        startButton = view.findViewById(R.id.timer_start)
    }

    private fun bindActions(view: View) {
        startButton.setOnClickListener { startTimer(view) }
        view.findViewById<Button>(R.id.choose_background).setOnClickListener {
            backgroundPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        pauseResume.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    if (snapshot?.stage == TimerStage.Paused) {
                        coordinator.resume()
                    } else {
                        coordinator.pause()
                    }
                }.onFailure { showError(getString(R.string.timer_error_change_state)) }
            }
        }
        view.findViewById<Button>(R.id.timer_abort).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { coordinator.abort() }
                    .onFailure { showError(getString(R.string.timer_error_stop)) }
            }
        }
    }

    private fun observeTimer() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coordinator.state.collect { value ->
                    snapshot = value
                    updateMetadata(value)
                    renderSnapshot()
                }
            }
        }
    }

    private fun startTimer(root: View) {
        val total = root.findViewById<EditText>(R.id.total_minutes_input)
            .text.toString().toIntOrNull()
        val warmup = root.findViewById<EditText>(R.id.warmup_minutes_input)
            .text.toString().toIntOrNull()
        val name = root.findViewById<EditText>(R.id.task_name_input).text.toString().trim()
        if (total == null || warmup == null || total !in 1..MAX_TOTAL_MINUTES ||
            warmup !in 0 until total
        ) {
            showError(getString(R.string.timer_invalid_duration))
            return
        }
        val alarmManager = requireContext().getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            showError(getString(R.string.timer_exact_alarm_unavailable))
            return
        }

        requestNotificationPermissionIfNeeded()
        error.isVisible = false
        startButton.isEnabled = false
        timerStyle = selectedStyle(root)
        showRenderer(timerStyle)

        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.ensureDefaults(
                    nowUtcMs = System.currentTimeMillis(),
                    defaultCategoryName = getString(R.string.timer_default_category),
                    defaultPresetName = getString(R.string.timer_default_task),
                )
                val preset = app.repository.createPreset(
                    name = name,
                    categoryId = ClockRepository.DEFAULT_CATEGORY_ID,
                    totalMinutes = total,
                    warmupMinutes = warmup,
                    timerStyle = timerStyle,
                    backgroundAssetId = backgroundAssetId,
                    nowUtcMs = System.currentTimeMillis(),
                    unnamedTaskName = getString(R.string.timer_unnamed_task),
                )
                taskName = preset.name
                coordinator.start(preset.id)
            }.onFailure {
                startButton.isEnabled = true
                showError(getString(R.string.timer_error_start))
            }
        }
    }

    private fun renderSnapshot() {
        val current = snapshot
        if (current == null) {
            bindIdle()
            return
        }
        val uiState = TimerUiStateFactory.create(
            snapshot = current,
            taskName = taskName,
            categoryName = categoryName,
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
        )
        rendererController.bind(uiState)
        taskTitle.text = taskName
        stageLabel.text = stageText(current.stage)
        val terminal = current.stage == TimerStage.Completed || current.stage == TimerStage.Aborted
        setup.isVisible = terminal
        controls.isVisible = !terminal
        if (terminal) startButton.isEnabled = true
        pauseResume.text = getString(
            if (current.stage == TimerStage.Paused) R.string.timer_resume else R.string.timer_pause,
        )
    }

    private fun bindIdle() {
        taskTitle.setText(R.string.timer_default_task)
        stageLabel.setText(R.string.timer_stage_idle)
        setup.isVisible = true
        controls.isVisible = false
        startButton.isEnabled = true
        rendererController.bind(
            TimerUiState(
                stage = TimerStage.Idle,
                taskName = getString(R.string.timer_default_task),
                categoryName = "",
                remainingMs = DEFAULT_TOTAL_DURATION_MS,
                phaseDurationMs = DEFAULT_TOTAL_DURATION_MS,
                totalProgress = 0f,
                phaseProgress = 0f,
                paused = false,
            ),
        )
    }

    private suspend fun updateMetadata(current: TimerSnapshot?) {
        if (current == null) return
        val metadata = metadataLoader.load(current.sessionId) ?: return
        taskName = metadata.taskName.ifBlank { taskName }
        categoryName = metadata.categoryName
        if (backgroundAssetId != metadata.backgroundAssetId || timerStyle != metadata.style) {
            backgroundAssetId = metadata.backgroundAssetId
            backgroundFile = metadata.backgroundFile
            timerStyle = metadata.style
            showRenderer(timerStyle)
        }
    }

    private fun importBackground(uri: android.net.Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val maximumEdge = if (isLowRamDevice) {
                    LOW_RAM_BACKGROUND_EDGE
                } else {
                    BACKGROUND_EDGE
                }
                backgroundAssets.import(uri, maximumEdge)
            }.onSuccess { asset ->
                backgroundAssetId = asset.id
                backgroundFile = asset.file
                timerStyle = selectedStyle(requireView())
                showRenderer(timerStyle)
                Toast.makeText(
                    requireContext(),
                    R.string.background_ready,
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure {
                showError(getString(R.string.timer_error_background))
            }
        }
    }

    private fun selectedStyle(root: View): TimerStyle = TimerStyle.entries[
        root.findViewById<Spinner>(R.id.timer_style_input).selectedItemPosition
    ]

    private fun showRenderer(style: TimerStyle) {
        rendererController.show(style, backgroundFile)
        renderSnapshot()
    }

    private fun showError(message: String) {
        error.text = message
        error.isVisible = true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun shouldReduceMotion(): Boolean = isLowRamDevice ||
        requireContext().getSharedPreferences(SettingsFragment.PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SettingsFragment.KEY_REDUCE_MOTION, false)

    private fun applyKeepScreenOn() {
        val enabled = requireContext()
            .getSharedPreferences(SettingsFragment.PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SettingsFragment.KEY_KEEP_SCREEN_ON, false)
        if (enabled) {
            requireActivity().window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun stageText(stage: TimerStage): String = getString(
        when (stage) {
            TimerStage.Idle -> R.string.timer_stage_idle
            TimerStage.Warmup -> R.string.timer_stage_warmup
            TimerStage.Focus -> R.string.timer_stage_focus
            TimerStage.Paused -> R.string.timer_stage_paused
            TimerStage.Completed -> R.string.timer_stage_completed
            TimerStage.Aborted -> R.string.timer_stage_aborted
        },
    )

    private companion object {
        const val BACKGROUND_EDGE = 1_080
        const val DEFAULT_TOTAL_DURATION_MS = 60 * 60_000L
        const val LOW_RAM_BACKGROUND_EDGE = 720
        const val MAX_TOTAL_MINUTES = 1_440
        const val UI_UPDATE_INTERVAL_MS = 1_000L
    }
}
