package com.akira.clock.ui

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
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
import com.akira.clock.data.ThemeMode
import com.akira.clock.timer.TimerMode
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
import com.akira.clock.ui.render.TimerRendererController
import com.akira.clock.ui.render.TimerUiState
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private lateinit var addTimeBlockButton: ImageButton
    private lateinit var todayProgress: TodayProgressView
    private lateinit var themeToggle: ThemeToggleButtonView

    private var displayedBlocks = emptyList<ClockRepository.TimeBlock>()
    private var displayedTags = emptyList<CategoryEntity>()
    private var displayedProgress = emptyMap<String, TodayProgress>()
    private var blocksRendered = false
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

    override fun onResume() {
        super.onResume()
        rendererController.start()
        view?.post(ticker)
    }

    override fun onPause() {
        view?.removeCallbacks(ticker)
        rendererController.stop()
        applyImmersive(active = false)
        applyKeepScreenOn(counting = false)
        super.onPause()
    }

    private fun bindViews(view: View) {
        setup = view.findViewById(R.id.timer_setup)
        controls = view.findViewById(R.id.timer_controls)
        rendererHost = view.findViewById(R.id.timer_renderer_host)
        tagRows = view.findViewById(R.id.tag_rows)
        error = view.findViewById(R.id.timer_error)
        pauseResume = view.findViewById(R.id.timer_pause_resume)
        addTimeBlockButton = view.findViewById(R.id.add_time_block)
        todayProgress = view.findViewById(R.id.today_progress)
        themeToggle = view.findViewById(R.id.timer_theme_toggle)
        view.findViewById<BackdropBlurToolbar>(R.id.timer_toolbar)
            .bindBackdrop(view.findViewById(R.id.timer_scroll))
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
        addTimeBlockButton.setOnClickListener { showPresetEditor() }
        view.findViewById<View>(R.id.manage_tags).setOnClickListener {
            val tag = TagManagerDialogFragment.DIALOG_TAG
            if (childFragmentManager.findFragmentByTag(tag) == null) {
                TagManagerDialogFragment().show(childFragmentManager, tag)
            }
        }
        todayProgress.setOnClickListener { (activity as? MainActivity)?.showToday() }
        val app = requireActivity().application as ClockApplication
        themeToggle.bind(
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES,
        ) { dark ->
            val mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT
            app.settings.themeMode = mode
            app.applyThemeMode(mode)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                TodayProgressLoader(app.database).observe().collect { todayProgress.bind(it) }
            }
        }
    }

    private fun observeTimer() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(
                    app.repository.observeTimeBlocks(),
                    TodayProgressLoader(app.database).observeByTag(),
                    app.repository.observeTags(),
                ) { blocks, progressByTag, tags -> Triple(blocks, progressByTag, tags) }
                    .collect { (blocks, progressByTag, tags) ->
                        displayedTags = tags
                        renderTimeBlocks(blocks, progressByTag)
                    }
            }
        }
    }

    private fun renderTimeBlocks(
        blocks: List<ClockRepository.TimeBlock>,
        progressByTag: Map<String, TodayProgress>,
    ) {
        if (blocksRendered && blocks == displayedBlocks && progressByTag == displayedProgress) {
            return
        }
        displayedBlocks = blocks
        displayedProgress = progressByTag
        blocksRendered = true
        tagRows.removeAllViews()
        view?.findViewById<View>(R.id.timer_blocks_empty)?.isVisible = blocks.isEmpty()
        blocks.forEach { block ->
            tagRows.addView(createTimeBlockCard(block, progressByTag[block.primaryCategory.id]))
        }
    }

    private fun createTimeBlockCard(
        block: ClockRepository.TimeBlock,
        dailyProgress: TodayProgress?,
    ): View {
        val preset = block.preset
        val card = layoutInflater.inflate(R.layout.item_time_block, tagRows, false)
        card.findViewById<TextView>(R.id.time_block_name).text = preset.name
        card.findViewById<TextView>(R.id.time_block_metadata).text =
            if (preset.timerMode == TimerMode.STOPWATCH.name) {
                getString(R.string.timer_stopwatch_metadata)
            } else {
                getString(
                    R.string.time_block_metadata,
                    formatDuration(requireContext(), preset.totalDurationMs),
                    getString(R.string.timer_warmup_compact, preset.warmupDurationMs / 60_000L),
                )
            }
        val progressRing = card.findViewById<DailyGoalRingView>(R.id.time_block_goal_ring)
        val progressPercent = card.findViewById<TextView>(R.id.time_block_goal_percent)
        var goalAccessibilityDescription: CharSequence? = null
        if (dailyProgress != null && dailyProgress.goalMs > 0L) {
            val percent = (dailyProgress.fraction * 100).roundToInt()
            progressRing.isVisible = true
            progressPercent.isVisible = true
            progressRing.setProgress(dailyProgress.fraction)
            progressPercent.text = getString(R.string.time_block_goal_percent, percent)
            goalAccessibilityDescription = getString(
                R.string.time_block_goal_accessibility,
                preset.name,
                block.primaryCategory.name,
                percent,
                dailyProgress.creditedMs / 60_000L,
                dailyProgress.goalMs / 60_000L,
            )
        } else {
            progressRing.isVisible = false
            progressPercent.isVisible = false
        }
        card.findViewById<LinearLayout>(R.id.time_block_tags).apply {
            block.tags.forEachIndexed { index, tag ->
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (48 * resources.displayMetrics.density).toInt(),
                ).apply {
                    if (index > 0) marginStart = (6 * resources.displayMetrics.density).toInt()
                }
                addView(createTagChip(tag), params)
            }
        }
        card.findViewById<View>(R.id.time_block_tag_strip).isVisible = block.tags.isNotEmpty()
        card.contentDescription = goalAccessibilityDescription
            ?: getString(R.string.timer_edit_named, preset.name)
        card.setOnClickListener { showPresetEditor(preset.id) }
        card.findViewById<ImageButton>(R.id.time_block_start).apply {
            contentDescription = getString(R.string.timer_start_named, preset.name)
            setOnClickListener {
                startTimerWithPreset(block.primaryCategory, preset, it)
            }
        }
        return card
    }

    private fun createTagChip(tag: CategoryEntity): View {
        val density = resources.displayMetrics.density
        val color = tag.colorArgb.toInt()
        val foreground = requireContext().getColor(R.color.clock_on_surface_muted)
        val surface = requireContext().getColor(R.color.clock_background)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        // Tint the surface instead of dimming the entire view, preserving readable label contrast.
        val tint = ColorUtils.blendARGB(surface, color, if (night) 0.10f else 0.06f)
        fun pill(fill: Int) = InsetDrawable(
            GradientDrawable().apply {
                cornerRadius = 999 * density
                setColor(fill)
            },
            (2 * density).toInt(), (13 * density).toInt(),
            (2 * density).toInt(), (13 * density).toInt(),
        )
        return TextView(requireContext()).apply {
            text = tag.name
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (48 * density).toInt(),
            )
            setTextColor(foreground)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(foreground, 38)),
                pill(tint),
                pill(Color.WHITE),
            )
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            isFocusable = true
            contentDescription = getString(R.string.tag_edit_named, tag.name)
            setOnClickListener {
                TagEditorDialogFragment.newInstance(
                    tag.id, tag.name, tag.colorArgb, tag.dailyGoalMs,
                ).show(childFragmentManager, TAG_EDITOR_TAG)
            }
        }
    }

    private fun startTimerWithPreset(
        category: CategoryEntity,
        preset: TaskPresetEntity,
        trigger: View,
    ) {
        val alarmManager = requireContext().getSystemService(AlarmManager::class.java)
        val needsExactAlarm = preset.timerMode == TimerMode.COUNTDOWN.name &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (needsExactAlarm && !alarmManager.canScheduleExactAlarms()) {
            showError(getString(R.string.timer_exact_alarm_unavailable))
            return
        }

        requestNotificationPermissionIfNeeded()
        error.isVisible = false

        taskName = preset.name
        categoryName = category.name
        trigger.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                runCatching { coordinator.start(preset.id) }
                    .onFailure { showError(getString(R.string.timer_error_start)) }
            } finally {
                trigger.isEnabled = true
            }
        }
    }

    override fun onTagCreated(
        name: String,
        colorArgb: Long,
        dailyGoalMs: Long,
    ) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.createTag(name, colorArgb, dailyGoalMs)
            }.onFailure { showError(getString(R.string.tag_create_failed)) }
        }
    }

    override fun onTagUpdated(
        id: String,
        name: String,
        colorArgb: Long,
        dailyGoalMs: Long,
    ) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.updateTag(id, name, colorArgb, dailyGoalMs)
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

    override fun onPresetCreated(
        name: String,
        totalDurationMs: Long,
        warmupDurationMs: Long,
        stopwatch: Boolean,
        tagIds: List<String>,
        newTagName: String?,
    ) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val resolvedTagIds = resolveTagIds(app, tagIds, newTagName)
                app.repository.createPreset(
                    name = name,
                    categoryId = resolvedTagIds.first(),
                    tagIds = resolvedTagIds,
                    totalMinutes = (totalDurationMs / 60_000L).toInt(),
                    warmupMinutes = (warmupDurationMs / 60_000L).toInt(),
                    stopwatch = stopwatch,
                    nowUtcMs = System.currentTimeMillis(),
                    unnamedTaskName = getString(R.string.timer_unnamed_task),
                )
            }.onFailure { showError(getString(R.string.preset_create_failed)) }
        }
    }

    override fun onPresetUpdated(
        id: String,
        name: String,
        totalDurationMs: Long,
        warmupDurationMs: Long,
        stopwatch: Boolean,
        tagIds: List<String>,
        newTagName: String?,
    ) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val resolvedTagIds = resolveTagIds(app, tagIds, newTagName)
                app.repository.updatePreset(
                    id = id,
                    name = name,
                    totalMinutes = (totalDurationMs / 60_000L).toInt(),
                    warmupMinutes = (warmupDurationMs / 60_000L).toInt(),
                    stopwatch = stopwatch,
                    tagIds = resolvedTagIds,
                )
            }.onFailure { showError(getString(R.string.preset_update_failed)) }
        }
    }

    override fun onPresetDeleted(id: String) {
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                app.repository.deletePreset(id)
            }.onFailure { showError(getString(R.string.preset_delete_failed)) }
        }
    }

    /** Uses the Room-backed screen snapshot so tapping a card never waits for new queries. */
    private fun showPresetEditor(presetId: String? = null) {
        if (childFragmentManager.isStateSaved ||
            childFragmentManager.findFragmentByTag(PRESET_EDITOR_TAG) != null
        ) return
        val tagIds = displayedTags.map { it.id }
        val tagNames = displayedTags.map { it.name }
        val editor = if (presetId == null) {
            PresetEditorDialogFragment.newInstance(tagIds, tagNames)
        } else {
            val block = displayedBlocks.firstOrNull { it.preset.id == presetId } ?: return
            val preset = block.preset
            PresetEditorDialogFragment.newInstance(
                preset.id, preset.name, preset.totalDurationMs, preset.warmupDurationMs,
                preset.timerMode == TimerMode.STOPWATCH.name,
                tagIds, tagNames, block.tags.map { it.id },
            )
        }
        editor.showNow(childFragmentManager, PRESET_EDITOR_TAG)
    }

    override fun onDestroyView() {
        blocksRendered = false
        displayedBlocks = emptyList()
        displayedTags = emptyList()
        displayedProgress = emptyMap()
        super.onDestroyView()
    }

    private suspend fun resolveTagIds(
        app: ClockApplication,
        selectedTagIds: List<String>,
        newTagName: String?,
    ): List<String> {
        val newTag = newTagName?.let {
            app.repository.createTag(
                name = it,
                colorArgb = 0xFF326FEB,
            )
        }
        return (selectedTagIds + listOfNotNull(newTag?.id)).distinct()
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
        addTimeBlockButton.isVisible = !active
        applyImmersive(active)
        applyKeepScreenOn(counting)
        val iconRes = if (current.stage == TimerStage.Paused) {
            R.drawable.ic_play_primary
        } else {
            R.drawable.ic_pause_primary
        }
        pauseResume.setImageResource(iconRes)
        pauseResume.contentDescription = getString(
            if (current.stage == TimerStage.Paused) R.string.timer_resume else R.string.timer_pause,
        )
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
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.statusBars())
            val nightMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            controller.isAppearanceLightStatusBars = !nightMode
            window.statusBarColor = ContextCompat.getColor(
                requireContext(), R.color.clock_background,
            )
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            val nightMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            controller.isAppearanceLightStatusBars = !nightMode
            window.statusBarColor = ContextCompat.getColor(
                requireContext(), R.color.clock_background,
            )
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
