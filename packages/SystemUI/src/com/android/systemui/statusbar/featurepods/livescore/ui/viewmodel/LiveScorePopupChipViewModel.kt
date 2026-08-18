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

package com.android.systemui.statusbar.featurepods.livescore.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.featurepods.livescore.shared.model.LiveScoreChipModel
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES_SOURCE
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandFeatureInt
import com.android.systemui.statusbar.featurepods.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChipIcon
import com.android.systemui.statusbar.featurepods.popups.ui.model.ColorsModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** ViewModel backing live-score notifications surfaced inside the dynamic island. */
class LiveScorePopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : StatusBarPopupChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("LiveScorePopupChipViewModel.hydrator")

    override val chip: PopupChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = PopupChipModel.Hidden(PopupChipId.LiveScore),
            source =
                combine(
                    activeNotificationsInteractor.promotedOngoingNotifications,
                    activeNotificationsInteractor.allRepresentativeNotifications,
                    observeDynamicIslandFeatureInt(context, LIVE_SCORES_SOURCE),
                ) { promotedNotifications: List<ActiveNotificationModel>, allNotifications: Map<String, ActiveNotificationModel>, sourceMode: Int ->
                    val orderedNotifications =
                        promotedNotifications.mapNotNull { notif -> allNotifications[notif.key] }
                    val candidate =
                        orderedNotifications.firstOrNull { it.isLiveScoreCandidate(sourceMode) }
                            ?: allNotifications.values.firstOrNull { it.isLiveScoreCandidate(sourceMode) }
                    toPopupChipModel(
                        candidate?.toLiveScoreModel(
                            context = context,
                            activityStarter = activityStarter,
                            activityIntentHelper = activityIntentHelper,
                            lockscreenUserManager = lockscreenUserManager,
                            keyguardStateController = keyguardStateController,
                        )
                    )
                }
                .combine(observeDynamicIslandFeatureEnabled(context, LIVE_SCORES)) { model: PopupChipModel, enabled: Boolean ->
                    if (enabled) model else PopupChipModel.Hidden(PopupChipId.LiveScore)
                },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(model: LiveScoreChipModel?): PopupChipModel {
        if (model == null) {
            return PopupChipModel.Hidden(PopupChipId.LiveScore)
        }

        return PopupChipModel.Shown(
            chipId = PopupChipId.LiveScore,
            icons =
                listOfNotNull(
                    model.icon?.let {
                        ChipIcon(
                            icon = it,
                            onClick = model.onOpen,
                        )
                    },
                    model.secondaryIcon?.let {
                        ChipIcon(
                            icon = it,
                            onClick = model.onOpen,
                        )
                    }
                ),
            chipText = buildCollapsedText(model),
            colors = ColorsModel.DynamicIsland,
            contentDescription = listOfNotNull(model.title, model.score, model.subtitle).joinToString(" "),
            popupContent = PopupContentModel.LiveScore(model),
        )
    }

    private fun buildCollapsedText(model: LiveScoreChipModel): String {
        val rawScore = model.score.stripEmojiSlop().takeUnless { it.isBlank() }
        val rawTitle = (model.title?.takeUnless { it.isBlank() } ?: model.appName).stripEmojiSlop()
        return if (rawScore != null) {
            val teams = rawTitle.split(" vs ", " - ", " @ ", " v ")
            val shortTitle = if (teams.size >= 2) {
                "${teams[0].take(3).trim()} v ${teams[1].take(3).trim()}".uppercase()
            } else {
                rawTitle.take(10)
            }
            if (rawScore.equals("LIVE", true)) {
                if (shortTitle.isNotBlank()) shortTitle else "LIVE"
            } else {
                "$rawScore • $shortTitle"
            }
        } else {
            rawTitle.take(12)
        }
    }

    private fun String.stripEmojiSlop(): String {
        return replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}\\p{Extended_Pictographic}]"), "").replace(Regex("\\s+"), " ").trim()
    }

    @AssistedFactory
    interface Factory {
        fun create(): LiveScorePopupChipViewModel
    }
}

private fun ActiveNotificationModel.isLiveScoreCandidate(sourceMode: Int = 0): Boolean {
    val content = promotedContent?.privateVersion ?: return false
    if (callType != com.android.systemui.statusbar.notification.shared.CallType.None) {
        return false
    }
    val pkg = packageName.lowercase()
    val isGoogle = pkg == "com.google.android.googlequicksearchbox" ||
            pkg.contains("googleassistant") ||
            pkg.contains("googlequicksearchbox") ||
            pkg.startsWith("com.google.android.apps.") ||
            pkg.startsWith("com.google.android.")
    val isFotmob = pkg.contains("fotmob")
    val isSofascore = pkg.contains("sofascore")
    val isFlashscore = pkg.contains("flashscore") || pkg.contains("livesport")
    val isCricbuzz = pkg.contains("cricbuzz")
    val isEspn = pkg.contains("espn")

    when (sourceMode) {
        com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES_SOURCE_GOOGLE -> {
            if (!isGoogle) return false
        }
        com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES_SOURCE_FOTMOB -> {
            if (!isFotmob) return false
        }
        com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES_SOURCE_SOFASCORE -> {
            if (!isSofascore) return false
        }
        com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES_SOURCE_FLASHSCORE -> {
            if (!isFlashscore) return false
        }
        com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES_SOURCE_CRICBUZZ -> {
            if (!isCricbuzz) return false
        }
        com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES_SOURCE_ESPN -> {
            if (!isEspn) return false
        }
        com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES_SOURCE_NON_GOOGLE -> {
            if (isGoogle) return false
        }
    }

    val isSportsFetcher = pkg == "org.lineageos.sportsfetcher"
    val isKnownSportsApp = isGoogle || isFotmob || isSofascore || isFlashscore || isCricbuzz || isEspn || isSportsFetcher
    if (!content.shortCriticalText.isNullOrBlank()) {
        return true
    }
    if (isKnownSportsApp) {
        return !content.title.isNullOrBlank() || !content.text.isNullOrBlank()
    }
    return false
}

private fun ActiveNotificationModel.toLiveScoreModel(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): LiveScoreChipModel {
    val content = promotedContent?.privateVersion
    val contentDescription = ContentDescription.Loaded(appName)
    val scoreRegex = Regex("""\b(\d+\s*[-–:]\s*\d+|\d+/\d+)\b""")
    val rawText = content?.text?.toString()
    val rawTitle = content?.title?.toString() ?: appName
    val score = content?.shortCriticalText
        ?: rawText?.let { scoreRegex.find(it)?.value }
        ?: rawTitle.let { scoreRegex.find(it)?.value }
        ?: "LIVE"
    val smallIcon = statusBarIcon?.loadDrawable(context)?.let { Icon.Loaded(it, contentDescription) }
    val secondaryIcon = content?.skeletonLargeIcon?.drawable?.let { Icon.Loaded(it, contentDescription) }
    return LiveScoreChipModel(
        key = key,
        icon = smallIcon,
        secondaryIcon = secondaryIcon,
        appName = appName,
        title = rawTitle,
        score = score,
        subtitle = rawText,
        onOpen =
            contentIntent.toActivityLaunchAction(
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = lockscreenUserManager,
                keyguardStateController = keyguardStateController,
            ),
    )
}
