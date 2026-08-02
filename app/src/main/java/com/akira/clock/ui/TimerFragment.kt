package com.akira.clock.ui

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.alarm.TimerCoordinator
import com.akira.clock.data.CategoryEntity
import com.akira.clock.data.ClockRepository
import com.akira.clock.data.TaskPresetEntity
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
import com.akira.clock.ui.render.TimerRendererController
import com.akira.clock.ui.render.TimerUiState

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * Android View adapter for the timer screen.
 *
 * Tags are the primary label: each tag is a card carrying its own default total and warmup
 * durations. While counting (warmup or focus) the screen is forced to stay awake and the countdown
 * takes over the full screen.
 */
class TimerFragment : Fragment(R.layout.fragment_timer), TagEditorDialogFragment.Listener,
    PresetEditorDialogFragment.Listener, AbortReasonDialogFragment.Listener {
    private lateinit var coordinator: TimerCoordinator
    private lateinit var metadataLoader: TimerDisplayMetadataLoader
    private lateinit var rendererController: TimerRendererController
    private lateinit var setup: View
    private lateinit var controls: LinearLayout
    private lateinit var rendererHost: FrameLayout
    private lateinit var tagRows: LinearLayout
    private lateinit var error: TextView
    private lateinit var pauseResume: ImageButton
    private lateinit var addTagButton: ImageButton

    private var snapshot: TimerSnapshot? = null
    private var taskName = ""
    private var categoryName = ""

    private val ticker = object : Runnable {
        override fun run() {
            renderSnapshot()
            view?.postDelayed(this, UI_UPDATE_INTERVAL_MS)
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as ClockApplication
        coordinator = TimerCoordinator.get(requireContext())
        metadataLoader = TimerDisplayMetadataLoader(app.database)
        rendererController = TimerRendererController(
            context = requireContext(),
            host = view.findViewById(R.id.timer_renderer_host),
        )
        bindViews(view)
        showRenderer()
        bindActions(view)
        observeTimer()
        observeTags(app)
        viewLifecycleOwner.lifecycleScope.launch { coordinator.restore() }
    }

    override fun onStart() {
        super.onStart()
        rendererController.start()
        view?.post(ticker)
    }

    override fun onStop() {
        view?.removeCallbacks(ticker)
        rendererController.stop()
        applyImmersive(active = false)
        applyKeepScreenOn(counting = false)
        super.onStop()
    }

    private fun bindViews(view: View) {
        setup = view.findViewById(R.id.timer_setup)
        controls = view.findViewById(R.id.timer_controls)
        rendererHost = view.findViewById(R.id.timer_renderer_host)
        tagRows = view.findViewById(R.id.tag_rows)
        error = view.findViewById(R.id.timer_error)
        pauseResume = view.findViewById(R.id.timer_pause_resume)
        addTagButton = view.findViewById(R.id.add_tag)
    }

    private fun bindActions(view: View) {
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
        view.findViewById<ImageButton>(R.id.timer_abort).setOnClickListener {
            AbortReasonDialogFragment().show(childFragmentManager, ABORT_DIALOG_TAG)
        }
        addTagButton.setOnClickListener { showTagEditor() }
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

    private fun observeTags(app: ClockApplication) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.repository.observeTagGroups().collect { groups -> renderTagGroups(app, groups) }
            }
        }
    }

    private fun renderTagGroups(app: ClockApplication, groups: List<ClockRepository.TagGroup>) {
        tagRows.removeAllViews()
        if (groups.isEmpty()) return
        val density = resources.displayMetrics.density
        groups.forEach { group ->
            tagRows.addView(createCategoryHeader(app, group.category, density))
            group.presets.forEach { preset ->
                tagRows.addView(createPresetCard(app, group.category, preset, density))
            }
        }
    }

    private fun createCategoryHeader(
        app: ClockApplication,
        category: CategoryEntity,
        density: Float,
    ): View {
        val context = requireContext()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = (12 * density).toInt()
            setPadding(h, h, h, (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnLongClickListener {
                TagEditorDialogFragment.newInstance(
                    category.id,
                    category.name,
                    category.colorArgb,
                    category.totalDurationMs,
                    category.warmupDurationMs,
                    category.dailyGoalMs,
                ).show(childFragmentManager, TAG_EDITOR_TAG)
                true
            }
        }

        val colorDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (10 * density).toInt(),
                (10 * density).toInt(),
            ).apply { marginEnd = (8 * density).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(category.colorArgb.toInt())
            }
        }

        val name = TextView(context).apply {
            text = category.name
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.clock_on_surface_muted))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }

        val addPresetBtn = ImageButton(context).apply {
            val size = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = null
            setImageResource(R.drawable.ic_add)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setColorFilter(ContextCompat.getColor(context, R.color.clock_on_surface_muted))
            setOnClickListener {
                pendingPresetCategoryId = category.id
                PresetEditorDialogFragment().show(childFragmentManager, PRESET_EDITOR_TAG)
            }
        }

        header.addView(colorDot)
        header.addView(name)
        header.addView(addPresetBtn)
        return header
    }

    private fun createPresetCard(
        app: ClockApplication,
        category: CategoryEntity,
        preset: TaskPresetEntity,
        density: Float,
    ): View {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_tag_row)
            val horizontal = (16 * density).toInt()
            val vertical = (12 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (6 * density).toInt() }
            setOnLongClickListener {
                PresetEditorDialogFragment.newInstance(
                    preset.id,
                    preset.name,
                    preset.totalDurationMs,
                    preset.warmupDurationMs,
                ).show(childFragmentManager, PRESET_EDITOR_TAG)
                true
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }

        val name = TextView(context).apply {
            text = preset.name
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.clock_on_surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * density).toInt() }
        }

        val duration = TextView(context).apply {
            text = formatDuration(context, preset.totalDurationMs)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.clock_on_surface_muted))
            layoutParams = LinearLayout.LayoutParams(
                (52 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (8 * density).toInt() }
        }

        val progressContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(0, (6 * density).toInt()).apply {
                weight = 1f
                marginEnd = (8 * density).toInt()
            }
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.progress_bar_fill)
        }

        val progressText = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.clock_on_surface_muted))
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val todayEnd = todayStart + 24 * 60 * 60_000L
            val progressMs = app.database.sessionDao().dailyProgressMs(category.id, todayStart, todayEnd)
            val goalMinutes = (category.dailyGoalMs / 60_000L).toInt()
            val progressMinutes = (progressMs / 60_000L).toInt()
            val progressPercent = if (category.dailyGoalMs > 0) (progressMs * 100 / category.dailyGoalMs).toInt() else 0

            progressBar.max = 100
            progressBar.progress = progressPercent.coerceAtMost(100)
            progressText.text = "$progressMinutes/$goalMinutes"
        }

        progressContainer.addView(progressBar)
        progressContainer.addView(progressText)

        bottomRow.addView(duration)
        bottomRow.addView(progressContainer)

        content.addView(name)
        content.addView(bottomRow)

        val startButtonSize = (40 * density).toInt()
        val startButton = ImageButton(context).apply {
            setBackgroundResource(R.drawable.bg_tag_start)
            setImageResource(R.drawable.ic_play_white)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(startButtonSize, startButtonSize).apply {
                marginStart = (12 * density).toInt()
            }
            setOnClickListener {
                it.isEnabled = false
                startTimerWithPreset(category, preset)
            }
        }

        card.addView(content)
        card.addView(startButton)

        return card
    }

    private fun startTimerWithPreset(category: CategoryEntity, preset: TaskPresetEntity) {
        val alarmManager = requireContext().getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            showError(getString(R.string.timer_exact_alarm_unavailable))
            return
        }

        requestNotificationPermissionIfNeeded()
        error.isVisible = false

        taskName = preset.name
        categoryName = category.name
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                coordinator.start(preset.id)
            }.onFailure {
                showError(getString(R.string.timer_error_start))
            }
        }
    }

    override fun onTagCreated(
        name: String,
        colorArgb: Long,
        totalDurationMs: Long,
        warmupDurationMs: Long,
        dailyGoalMs: Long,
    ) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.createTag(name, colorArgb, totalDurationMs, warmupDurationMs, dailyGoalMs)
            }.onFailure { showError(getString(R.string.tag_create_failed)) }
        }
    }

    override fun onTagUpdated(
        id: String,
        name: String,
        colorArgb: Long,
        totalDurationMs: Long,
        warmupDurationMs: Long,
        dailyGoalMs: Long,
    ) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.updateTag(id, name, colorArgb, totalDurationMs, warmupDurationMs, dailyGoalMs)
            }.onFailure { showError(getString(R.string.tag_update_failed)) }
        }
    }

    override fun onTagDeleted(id: String) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.deleteTag(id)
            }.onFailure { showError(getString(R.string.tag_delete_failed)) }
        }
    }

    private var pendingPresetCategoryId: String? = null

    override fun onPresetCreated(name: String, totalDurationMs: Long, warmupDurationMs: Long) {
        val categoryId = pendingPresetCategoryId ?: return
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.createPreset(
                    name = name,
                    categoryId = categoryId,
                    totalMinutes = (totalDurationMs / 60_000L).toInt(),
                    warmupMinutes = (warmupDurationMs / 60_000L).toInt(),
                    nowUtcMs = System.currentTimeMillis(),
                    unnamedTaskName = getString(R.string.timer_unnamed_task),
                )
            }.onFailure { showError("无法创建时间块") }
        }
    }

    override fun onPresetUpdated(id: String, name: String, totalDurationMs: Long, warmupDurationMs: Long) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.updatePreset(
                    id = id,
                    name = name,
                    totalMinutes = (totalDurationMs / 60_000L).toInt(),
                    warmupMinutes = (warmupDurationMs / 60_000L).toInt(),
                )
            }.onFailure { showError("无法更新时间块") }
        }
    }

    override fun onPresetDeleted(id: String) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.deletePreset(id)
            }.onFailure { showError("无法删除时间块") }
        }
    }

    private fun showTagEditor() {
        TagEditorDialogFragment().show(childFragmentManager, TAG_EDITOR_TAG)
    }

    override fun onAbortConfirmed(reason: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { coordinator.abort(reason = reason) }
                .onFailure { showError(getString(R.string.timer_error_stop)) }
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
        val terminal = current.stage == TimerStage.Completed || current.stage == TimerStage.Aborted
        val active = !terminal
        val counting = current.stage == TimerStage.Warmup || current.stage == TimerStage.Focus
        setup.isVisible = !active
        controls.isVisible = active
        rendererHost.isVisible = active
        addTagButton.isVisible = !active
        applyImmersive(active)
        applyKeepScreenOn(counting)
        val iconRes = if (current.stage == TimerStage.Paused) R.drawable.ic_play_white else R.drawable.ic_pause_white
        pauseResume.setImageResource(iconRes)
    }

    private fun bindIdle() {
        setup.isVisible = true
        controls.isVisible = false
        rendererHost.isVisible = false
        applyImmersive(active = false)
        applyKeepScreenOn(counting = false)
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
    }

    private fun showRenderer() {
        rendererController.show()
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

    private fun applyKeepScreenOn(counting: Boolean) {
        val window = requireActivity().window
        if (counting) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyImmersive(active: Boolean) {
        val window = requireActivity().window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (active) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.isAppearanceLightStatusBars = false
            window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.clock_background)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            val nightMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            controller.isAppearanceLightStatusBars = !nightMode
            window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.clock_background)
        }
    }

    private companion object {
        const val DEFAULT_TOTAL_DURATION_MS = 60 * 60_000L
        const val MAX_TOTAL_MINUTES = 1_440
        const val UI_UPDATE_INTERVAL_MS = 1_000L
        const val TAG_EDITOR_TAG = "tagEditor"
        const val PRESET_EDITOR_TAG = "presetEditor"
        const val ABORT_DIALOG_TAG = "abortReason"
    }
}