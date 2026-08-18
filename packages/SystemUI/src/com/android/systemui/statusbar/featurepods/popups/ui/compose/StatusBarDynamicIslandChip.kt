/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.view.DisplayCutout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.featurepods.livescore.shared.model.LiveScoreChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.screenrecord.shared.model.ScreenRecordPopupModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Single centered status bar capsule styled like a compact dynamic island. */
@Composable
fun StatusBarDynamicIslandChip(
    viewModel: PopupChipModel.Shown,
    pageCount: Int,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onChipBoundsChanged: (Rect) -> Unit = {},
) {
    val isMediaChip = viewModel.popupContent is PopupContentModel.Media
    val chipShape = RoundedCornerShape(50)
    val colors = viewModel.colors
    val chipBackgroundColor =
        colors.chipBackground(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipContentColor =
        colors.chipContent(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipOutline =
        colors.chipOutline(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val view = LocalView.current
    val boundsModifier =
        Modifier.onGloballyPositioned { coordinates ->
            onChipBoundsChanged(coordinates.boundsInScreen(view))
        }
    if (viewModel.popupContent is PopupContentModel.LiveScore && viewModel.icons.isNotEmpty()) {
        LiveScoreIslandChip(
            viewModel = viewModel,
            liveScoreModel = (viewModel.popupContent as PopupContentModel.LiveScore).model,
            cutoutSpec = cutoutSpec,
            onTap = onTap,
            chipOutline = chipOutline,
            modifier = modifier.then(boundsModifier),
        )
        return
    }

    if (viewModel.popupContent.isUtilityStatusContent() && viewModel.icons.isNotEmpty()) {
        UtilityStatusIslandChip(
            viewModel = viewModel,
            onTap = onTap,
            cutoutSpec = cutoutSpec,
            chipBackgroundColor = chipBackgroundColor,
            chipContentColor = chipContentColor,
            chipOutline = chipOutline,
            modifier = modifier.then(boundsModifier),
        )
        return
    }

    val compactWidth = compactIslandWidthFor(viewModel.popupContent)
    val hasInlineTimer = viewModel.popupContent is PopupContentModel.Stopwatch
    val trailingDecorationWidth =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media ->
                if (popupContent.model.isPlaying) {
                    14.dp
                } else if (pageCount > 1) {
                    11.dp
                } else {
                    0.dp
                }
            is PopupContentModel.ScreenRecord -> 11.dp
            else -> {
                if (pageCount > 1) 11.dp else 0.dp
            }
        }
    val leadingDecorationWidth =
        when {
            viewModel.icons.isEmpty() -> 0.dp
            else -> 18.dp + (8.dp * (viewModel.icons.size - 1))
        }
    val maxTextWidth =
        (CompactIslandMaxWidth - 24.dp - leadingDecorationWidth - trailingDecorationWidth)
            .coerceAtLeast(56.dp)

    Row(
        modifier =
            modifier
                .then(boundsModifier)
                .openSquishAnimation(viewModel.isPopupShown)
                .defaultMinSize(minHeight = 26.dp)
                .widthIn(
                    min = compactWidth ?: 0.dp,
                    max = compactWidth ?: CompactIslandMaxWidth,
                )
                .clip(chipShape)
                .background(Color.Black)
                .border(width = 1.dp, color = Color(0xFF26262B), shape = chipShape)
                .clickable(onClick = onTap)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement =
            if (isMediaChip) Arrangement.SpaceBetween else Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        viewModel.icons.forEachIndexed { index, chipIcon ->
            val isArtworkLike =
                index == 0 &&
                    (viewModel.popupContent is PopupContentModel.Media ||
                        viewModel.popupContent is PopupContentModel.LiveScore)
            Icon(
                icon = chipIcon.icon,
                modifier = Modifier
                    .size(if (isArtworkLike) 16.dp else 14.dp)
                    .then(
                        if (isArtworkLike) {
                            Modifier.clip(CircleShape)
                        } else {
                            Modifier
                        }
                    ),
                tint = if (isArtworkLike) Color.Unspecified else chipContentColor,
            )
        }

        viewModel.chipText
            ?.takeIf {
                !isMediaChip &&
                    viewModel.popupContent !is PopupContentModel.ScreenRecord &&
                    !hasInlineTimer &&
                    it.isNotBlank()
            }
            ?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = chipContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = maxTextWidth),
                )
            }

        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media ->
                if (popupContent.model.isPlaying) {
                    AudioReactiveBars(
                        isPlaying = true,
                        color = chipContentColor,
                    )
                } else if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            showSwipeHint = pageCount > 1,
                        )
                    is ScreenRecordPopupModel.Recording ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            showSwipeHint = pageCount > 1,
                        )
                }
            is PopupContentModel.Stopwatch ->
                StatusContent(
                    text =
                        popupContent.model.elapsedTimeText
                            ?: rememberElapsedDurationText(
                                popupContent.model.baseElapsedRealtimeMs
                            ),
                    color = chipContentColor,
                    showSwipeHint = pageCount > 1,
                )
            else -> {
                if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            }
        }
    }
}

@Composable
private fun LiveScoreIslandChip(
    viewModel: PopupChipModel.Shown,
    liveScoreModel: LiveScoreChipModel,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    chipOutline: Color,
    modifier: Modifier = Modifier,
) {
    val chipShape = RoundedCornerShape(50)
    val rawScore = liveScoreModel.score
        .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}\\p{Extended_Pictographic}]"), "")
        .trim()

    val rawTitle = (liveScoreModel.title?.takeUnless { it.isBlank() } ?: liveScoreModel.appName)
        .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}\\p{Extended_Pictographic}]"), "")
        .trim()
    val titleTeams = rawTitle
        .split(Regex("""\s*(?:vs|v|-|@)\s*""", RegexOption.IGNORE_CASE))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val homeAbbr = if (titleTeams.isNotEmpty()) titleTeams[0].take(3).uppercase() else ""
    val awayAbbr = if (titleTeams.size >= 2) titleTeams[1].take(3).uppercase() else ""

    val isCricket = rawScore.contains("/") || rawScore.contains("ov", true)

    val leftText: String
    val rightText: String

    if (isCricket) {
        // Extract batting score (e.g. 292/4)
        val scoreRegex = Regex("""(\d+/\d+|\d+/\d+\s*d)""")
        val matchScore = scoreRegex.find(rawScore)
        val battingScore = matchScore?.value ?: rawScore.split(Regex("""\s+""")).firstOrNull() ?: rawScore

        // Extract overs (e.g. 63.3 ov)
        val oversRegex = Regex("""\(?(\d+(?:\.\d+)?\s*ov)\)?""", RegexOption.IGNORE_CASE)
        val matchOvers = oversRegex.find(rawScore)
        val overs = matchOvers?.groupValues?.getOrNull(1) ?: ""

        leftText = battingScore
        rightText = if (overs.isNotBlank()) overs else (if (awayAbbr.isNotBlank()) awayAbbr else "LIVE")
    } else {
        val scoreParts = rawScore.split(Regex("""\s*[-–:]\s*""")).filter { it.isNotBlank() }
        when {
            scoreParts.size == 2 -> {
                leftText = scoreParts[0]
                rightText = scoreParts[1]
            }
            rawScore.isNotBlank() && !rawScore.equals("LIVE", true) && !rawScore.equals("vs", true) -> {
                leftText = if (homeAbbr.isNotBlank()) "$homeAbbr $rawScore" else rawScore
                rightText = if (awayAbbr.isNotBlank()) awayAbbr else "LIVE"
            }
            else -> {
                leftText = if (homeAbbr.isNotBlank()) homeAbbr else "LIVE"
                rightText = if (awayAbbr.isNotBlank()) awayAbbr else ""
            }
        }
    }

    val centerCutoutGap = cutoutSpec.embeddedGapWidth.coerceAtLeast(30.dp)

    Row(
        modifier =
            modifier
                .openSquishAnimation(viewModel.isPopupShown)
                .defaultMinSize(minHeight = 28.dp)
                .clip(chipShape)
                .background(Color.Black)
                .border(width = 1.dp, color = Color(0xFF26262B), shape = chipShape)
                .clickable(onClick = onTap)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Group: Home Crest + Left Score/Team
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            viewModel.icons.firstOrNull()?.icon?.let { iconModel ->
                Icon(
                    icon = iconModel,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    tint = Color.Unspecified,
                )
            }

            Text(
                text = leftText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                ),
                color = Color.White,
            )
        }

        // Center Notch / Camera Cutout Gap (keeps scores completely clear of camera hole)
        Spacer(modifier = Modifier.width(centerCutoutGap))

        // Right Group: Right Score/Team + Away Crest
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rightText.isNotBlank()) {
                Text(
                    text = rightText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    ),
                    color = Color.White,
                )
            }

            if (viewModel.icons.size >= 2) {
                Icon(
                    icon = viewModel.icons[1].icon,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape),
                    tint = Color.Unspecified,
                )
            } else {
                viewModel.icons.firstOrNull()?.icon?.let { iconModel ->
                    Icon(
                        icon = iconModel,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape),
                        tint = Color.Unspecified,
                    )
                }
            }
        }
    }
}

@Composable
private fun UtilityStatusIslandChip(
    viewModel: PopupChipModel.Shown,
    onTap: () -> Unit,
    cutoutSpec: DynamicIslandCutoutSpec,
    chipBackgroundColor: Color,
    chipContentColor: Color,
    chipOutline: Color,
    modifier: Modifier = Modifier,
) {
    val rightSegmentWidth =
        when (viewModel.popupContent) {
            is PopupContentModel.Flashlight -> 52.dp
            is PopupContentModel.Alarm -> 72.dp
            else -> 80.dp
        }
    val connectedIslandWidth =
        (CompactUtilityConnectedIslandChromeWidth +
                cutoutSpec.embeddedGapWidth +
                rightSegmentWidth)
            .coerceIn(
                CompactUtilityConnectedIslandMinWidth,
                CompactUtilityConnectedIslandMaxWidth,
            )
    val utilityText =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting -> "${model.secondsUntilStarted}s"
                    is ScreenRecordPopupModel.Recording ->
                        rememberElapsedDurationText(model.startElapsedRealtimeMs)
                }
            is PopupContentModel.Stopwatch ->
                popupContent.model.elapsedTimeText
                    ?: rememberElapsedDurationText(popupContent.model.baseElapsedRealtimeMs)
            is PopupContentModel.Alarm -> viewModel.chipText.orEmpty()
            is PopupContentModel.Flashlight -> viewModel.chipText.orEmpty()
            else -> ""
        }

    Row(
        modifier =
            modifier
                .openSquishAnimation(viewModel.isPopupShown)
                .defaultMinSize(minHeight = 26.dp)
                .width(connectedIslandWidth)
                .clip(RoundedCornerShape(50))
                .background(Color.Black)
                .border(width = 1.dp, color = Color(0xFF26262B), shape = RoundedCornerShape(50))
                .clickable(onClick = onTap),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(8.dp))
        viewModel.icons.firstOrNull()?.icon?.let { iconModel ->
            Icon(
                icon = iconModel,
                modifier = Modifier.size(14.dp),
                tint = chipContentColor,
            )
        }
        Spacer(modifier = Modifier.width(cutoutSpec.embeddedGapWidth))
        Box(
            modifier =
                Modifier.width(rightSegmentWidth)
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = utilityText,
                style = MaterialTheme.typography.labelMedium,
                color = chipContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun StatusContent(
    text: String,
    color: Color,
    showSwipeHint: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
        if (showSwipeHint) {
            SwipeHint(color = color.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun SwipeHint(color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                modifier = Modifier.size(width = 2.5.dp, height = 2.5.dp)
                    .background(color = color, shape = CircleShape)
            )
        }
    }
}

private val CompactIslandMaxWidth = 138.dp
private val CompactMediaIslandWidth = 92.dp
private val CompactTimerIslandWidth = 96.dp
private val CompactRecordingIslandWidth = 76.dp
private val CompactAlarmIslandWidth = 80.dp
private val CompactUtilityIslandWidth = 64.dp
private val CompactUtilityConnectedIslandChromeWidth = 32.dp
private val CompactUtilityConnectedIslandMinWidth = 108.dp
private val CompactUtilityConnectedIslandMaxWidth = 144.dp
private val DynamicIslandEmbeddedGapFallbackWidth = 32.dp
private val DynamicIslandEmbeddedGapMinWidth = 28.dp
private val DynamicIslandEmbeddedGapMaxWidth = 72.dp
private val DynamicIslandEmbeddedGapSidePadding = 8.dp

data class DynamicIslandCutoutSpec(
    val embeddedGapWidth: Dp,
    val horizontalOffset: Dp,
)

@Composable
fun rememberDynamicIslandCutoutSpec(): DynamicIslandCutoutSpec {
    val density = LocalDensity.current
    val view = LocalView.current
    val displayCutout = view.rootWindowInsets?.displayCutout ?: view.display?.cutout
    val topCutout = displayCutout?.topBoundingRectOrNull()
    val rootWidthPx =
        when {
            view.rootView.width > 0 -> view.rootView.width
            view.width > 0 -> view.width
            else -> view.resources.configuration.windowConfiguration.maxBounds.width()
        }

    return with(density) {
        if (topCutout == null || rootWidthPx <= 0) {
            DynamicIslandCutoutSpec(
                embeddedGapWidth = DynamicIslandEmbeddedGapFallbackWidth,
                horizontalOffset = 0.dp,
            )
        } else {
            val embeddedGapWidthDp =
                (topCutout.width().toDp() + (DynamicIslandEmbeddedGapSidePadding * 2))
                    .coerceIn(
                        DynamicIslandEmbeddedGapMinWidth,
                        DynamicIslandEmbeddedGapMaxWidth,
                    )
            val horizontalOffsetDp = (topCutout.exactCenterX() - (rootWidthPx / 2f)).toDp()
            DynamicIslandCutoutSpec(
                embeddedGapWidth = embeddedGapWidthDp,
                horizontalOffset = horizontalOffsetDp,
            )
        }
    }
}

private fun DisplayCutout.topBoundingRectOrNull() =
    getBoundingRectTop().takeUnless { it.isEmpty }

private fun PopupContentModel.isUtilityStatusContent(): Boolean {
    return this is PopupContentModel.ScreenRecord ||
        this is PopupContentModel.Stopwatch ||
        this is PopupContentModel.Alarm ||
        this is PopupContentModel.Flashlight
}

private fun compactIslandWidthFor(content: PopupContentModel): Dp? {
    return when (content) {
        is PopupContentModel.Media -> CompactMediaIslandWidth
        is PopupContentModel.ScreenRecord ->
            when (content.model) {
                is ScreenRecordPopupModel.Starting -> CompactTimerIslandWidth
                is ScreenRecordPopupModel.Recording -> CompactRecordingIslandWidth
            }
        is PopupContentModel.Stopwatch -> CompactTimerIslandWidth
        is PopupContentModel.Alarm -> CompactAlarmIslandWidth
        is PopupContentModel.Flashlight -> CompactUtilityIslandWidth
        else -> null
    }
}

@Composable
private fun Modifier.openSquishAnimation(isOpen: Boolean): Modifier {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val scaleY = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentIsOpen by rememberUpdatedState(isOpen)
    LaunchedEffect(Unit) {
        snapshotFlow { currentIsOpen }
            .drop(1)
            .collectLatest { open ->
                if (!open) return@collectLatest
                scaleX.snapTo(1f)
                scaleY.snapTo(1f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                keyframes {
                                    durationMillis = 360
                                    0.9f at 0
                                    1.05f at 160 using FastOutSlowInEasing
                                    0.98f at 280
                                    1f at 360
                                },
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                keyframes {
                                    durationMillis = 360
                                    1.12f at 0
                                    0.94f at 160 using FastOutSlowInEasing
                                    1.02f at 280
                                    1f at 360
                                },
                        )
                    }
                }
            }
    }
    return this.graphicsLayer {
        this.scaleX = scaleX.value
        this.scaleY = scaleY.value
    }
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}
