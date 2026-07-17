package com.akira.clock.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
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
import com.akira.clock.data.ClockRepository
import com.akira.clock.data.AssetEntity
import com.akira.clock.data.TimerStyle
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
import com.akira.clock.timer.remainingMs
import com.akira.clock.ui.render.DigitalTimerView
import com.akira.clock.ui.render.BackgroundAssetProcessor
import com.akira.clock.ui.render.HourglassTimerView
import com.akira.clock.ui.render.RingTimerView
import com.akira.clock.ui.render.TimerRenderer
import com.akira.clock.ui.render.TimerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class TimerFragment : Fragment(R.layout.fragment_timer) {
    private lateinit var coordinator: TimerCoordinator
    private lateinit var rendererHost: FrameLayout
    private lateinit var setup: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var stageLabel: TextView
    private lateinit var taskTitle: TextView
    private lateinit var error: TextView
    private lateinit var pauseResume: Button
    private var renderer: TimerRenderer? = null
    private var snapshot: TimerSnapshot? = null
    private var taskName: String = "开始一次专注"
    private var categoryName: String = "专注"
    private var backgroundAssetId: String? = null
    private var backgroundFile: java.io.File? = null
    private val ticker = object : Runnable {
        override fun run() {
            renderSnapshot()
            view?.postDelayed(this, 1_000L)
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
        if (uri != null) processBackground(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        coordinator = TimerCoordinator.get(requireContext())
        rendererHost = view.findViewById(R.id.timer_renderer_host)
        setup = view.findViewById(R.id.timer_setup)
        controls = view.findViewById(R.id.timer_controls)
        stageLabel = view.findViewById(R.id.timer_stage)
        taskTitle = view.findViewById(R.id.timer_task_title)
        error = view.findViewById(R.id.timer_error)
        pauseResume = view.findViewById(R.id.timer_pause_resume)
        setRenderer(TimerStyle.DIGITAL)

        view.findViewById<Button>(R.id.timer_start).setOnClickListener { startTimer(view) }
        view.findViewById<Button>(R.id.choose_background).setOnClickListener {
            backgroundPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        pauseResume.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    if (snapshot?.stage == TimerStage.Paused) coordinator.resume() else coordinator.pause()
                }.onFailure { showError(it.message ?: "无法更改计时状态") }
            }
        }
        view.findViewById<Button>(R.id.timer_abort).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { coordinator.abort() }
                    .onFailure { showError(it.message ?: "无法结束倒计时") }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coordinator.state.collect { value ->
                    snapshot = value
                    updateMetadata(value)
                    renderSnapshot()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { coordinator.restore() }
    }

    override fun onStart() {
        super.onStart()
        renderer?.startRendering()
        view?.post(ticker)
        applyKeepScreenOn()
    }

    override fun onStop() {
        view?.removeCallbacks(ticker)
        renderer?.stopRendering()
        requireActivity().window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun startTimer(root: View) {
        val total = root.findViewById<EditText>(R.id.total_minutes_input).text.toString().toIntOrNull()
        val warmup = root.findViewById<EditText>(R.id.warmup_minutes_input).text.toString().toIntOrNull()
        val name = root.findViewById<EditText>(R.id.task_name_input).text.toString().trim()
        if (total == null || warmup == null || total !in 1..1_440 || warmup !in 0 until total) {
            showError(getString(R.string.timer_invalid_duration))
            return
        }
        val alarmManager = requireContext().getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            showError(getString(R.string.timer_exact_alarm_unavailable))
            return
        }
        requestNotificationPermissionIfNeeded()
        error.isVisible = false
        val style = TimerStyle.entries[root.findViewById<Spinner>(R.id.timer_style_input).selectedItemPosition]
        setRenderer(style)
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val preset = withContext(Dispatchers.IO) {
                    app.repository.ensureDefaults(System.currentTimeMillis())
                    app.repository.createPreset(
                        name = name,
                        categoryId = ClockRepository.DEFAULT_CATEGORY_ID,
                        totalMinutes = total,
                        warmupMinutes = warmup,
                        timerStyle = style,
                        backgroundAssetId = backgroundAssetId,
                        nowUtcMs = System.currentTimeMillis(),
                    )
                }
                taskName = preset.name
                coordinator.start(preset.id)
            }.onFailure { showError(it.message ?: "无法开始倒计时") }
        }
    }

    private fun setRenderer(style: TimerStyle) {
        renderer?.stopRendering()
        rendererHost.removeAllViews()
        backgroundFile?.takeIf { it.isFile }?.let { file ->
            rendererHost.addView(ImageView(requireContext()).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(android.net.Uri.fromFile(file))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            rendererHost.addView(View(requireContext()).apply {
                val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                setBackgroundColor(if (night) 0x99000000.toInt() else 0x99FFFFFF.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        val view = when (style) {
            TimerStyle.DIGITAL -> DigitalTimerView(requireContext())
            TimerStyle.RING -> RingTimerView(requireContext())
            TimerStyle.HOURGLASS -> HourglassTimerView(requireContext()).apply {
                reduceMotion = requireContext().getSharedPreferences(SettingsFragment.PREFERENCES, 0)
                    .getBoolean(SettingsFragment.KEY_REDUCE_MOTION, false)
            }
        }
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        rendererHost.addView(view)
        renderer = view
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) renderer?.startRendering()
        renderSnapshot()
    }

    private fun renderSnapshot() {
        val current = snapshot
        if (current == null) {
            bindIdle()
            return
        }
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val activeRemaining = current.phaseDeadlineElapsedMs?.let { remainingMs(it, nowElapsed) }
        val focusDuration = current.totalDurationMs - current.warmupDurationMs
        val isWarmupPhase = current.stage == TimerStage.Warmup ||
            (current.stage == TimerStage.Paused && current.accumulatedWarmupMs < current.warmupDurationMs)
        val phaseDuration = if (isWarmupPhase) current.warmupDurationMs else focusDuration
        val baseAccumulated = if (isWarmupPhase) current.accumulatedWarmupMs else current.accumulatedFocusMs
        val pausedRemaining = (phaseDuration - baseAccumulated).coerceAtLeast(0L)
        val remaining = activeRemaining ?: if (current.stage == TimerStage.Paused) pausedRemaining else 0L
        val elapsedInPhase = (phaseDuration - remaining).coerceIn(0L, phaseDuration)
        val currentActiveElapsed = current.phaseStartedElapsedMs?.let {
            (nowElapsed - it).coerceAtLeast(0L).coerceAtMost(phaseDuration - baseAccumulated)
        } ?: 0L
        val invested = current.accumulatedWarmupMs + current.accumulatedFocusMs + currentActiveElapsed
        val uiState = TimerUiState(
            stage = current.stage,
            taskName = taskName,
            categoryName = categoryName,
            remainingMs = remaining,
            phaseDurationMs = phaseDuration,
            totalProgress = invested.toFloat() / current.totalDurationMs,
            phaseProgress = if (phaseDuration == 0L) 1f else elapsedInPhase.toFloat() / phaseDuration,
            paused = current.stage == TimerStage.Paused,
        )
        renderer?.bind(uiState)
        taskTitle.text = taskName
        stageLabel.text = stageText(current.stage)
        val terminal = current.stage == TimerStage.Completed || current.stage == TimerStage.Aborted
        setup.isVisible = terminal
        controls.isVisible = !terminal
        pauseResume.text = if (current.stage == TimerStage.Paused) {
            getString(R.string.timer_resume)
        } else {
            getString(R.string.timer_pause)
        }
    }

    private fun bindIdle() {
        taskTitle.setText(R.string.timer_default_task)
        stageLabel.setText(R.string.timer_stage_idle)
        setup.isVisible = true
        controls.isVisible = false
        renderer?.bind(
            TimerUiState(
                stage = TimerStage.Idle,
                taskName = getString(R.string.timer_default_task),
                categoryName = "",
                remainingMs = 60 * 60_000L,
                phaseDurationMs = 60 * 60_000L,
                totalProgress = 0f,
                phaseProgress = 0f,
                paused = false,
            ),
        )
    }

    private suspend fun updateMetadata(current: TimerSnapshot?) {
        if (current == null) return
        val app = requireActivity().application as ClockApplication
        val session = withContext(Dispatchers.IO) { app.database.sessionDao().get(current.sessionId) }
        if (session != null) {
            taskName = session.taskNameSnapshot.ifBlank { taskName }
            categoryName = session.categoryNameSnapshot
            val preset = session.presetId?.let { withContext(Dispatchers.IO) { app.database.taskPresetDao().get(it) } }
            val asset = preset?.backgroundAssetId?.let { id ->
                withContext(Dispatchers.IO) { app.database.assetDao().allForExport().firstOrNull { it.id == id } }
            }
            if (asset != null && backgroundAssetId != asset.id) {
                backgroundAssetId = asset.id
                backgroundFile = java.io.File(requireContext().filesDir, asset.localPath)
                setRenderer(runCatching { TimerStyle.valueOf(preset.timerStyle) }.getOrDefault(TimerStyle.DIGITAL))
            }
        }
    }

    private fun processBackground(uri: android.net.Uri) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val processed = BackgroundAssetProcessor(requireContext().applicationContext)
                    .process(uri, maxWidth = 1_440, maxHeight = 1_440)
                val asset = withContext(Dispatchers.IO) {
                    app.database.assetDao().findBySha256(processed.sha256) ?: AssetEntity(
                        id = UUID.randomUUID().toString(),
                        sha256 = processed.sha256,
                        localPath = "backgrounds/${processed.file.name}",
                        width = processed.width,
                        height = processed.height,
                        referenceCount = 1,
                        createdAtUtcMs = System.currentTimeMillis(),
                    ).also { app.database.assetDao().upsert(it) }
                }
                backgroundAssetId = asset.id
                backgroundFile = processed.file
                val selected = TimerStyle.entries[requireView().findViewById<Spinner>(R.id.timer_style_input).selectedItemPosition]
                setRenderer(selected)
                android.widget.Toast.makeText(requireContext(), R.string.background_ready, android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure { showError(it.message ?: "无法处理背景图片") }
        }
    }

    private fun showError(message: String) {
        error.text = message
        error.isVisible = true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun applyKeepScreenOn() {
        val enabled = requireContext().getSharedPreferences(SettingsFragment.PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SettingsFragment.KEY_KEEP_SCREEN_ON, false)
        if (enabled) requireActivity().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stageText(stage: TimerStage): String = when (stage) {
        TimerStage.Idle -> getString(R.string.timer_stage_idle)
        TimerStage.Warmup -> getString(R.string.timer_stage_warmup)
        TimerStage.Focus -> getString(R.string.timer_stage_focus)
        TimerStage.Paused -> getString(R.string.timer_stage_paused)
        TimerStage.Completed -> "已完成"
        TimerStage.Aborted -> "已结束"
    }
}
