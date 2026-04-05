/*
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.server.applock;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.AppLockUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

import lineageos.providers.LineageSettings;

/**
 * Runtime backend for LineageOS App Lock.
 */
public final class AppLockManagerService extends SystemService {
    private static final String TAG = "AppLockManagerService";
    private static final long PENDING_UNLOCK_TIMEOUT_MS = 15_000L;

    private static final ComponentName APP_LOCK_ACTIVITY = new ComponentName(
            AppLockUtils.APP_LOCK_ACTIVITY_PACKAGE,
            AppLockUtils.APP_LOCK_ACTIVITY_CLASS);

    private final Object mLock = new Object();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final SparseArray<ArraySet<String>> mLockedPackagesByUser = new SparseArray<>();
    private final SparseArray<ArrayMap<Integer, String>> mUnlockedTasksByUser = new SparseArray<>();
    private final SparseArray<ArrayMap<String, Long>> mPendingUnlocksByUser = new SparseArray<>();
    private final SparseArray<ArrayMap<Integer, String>> mActiveTaskChallengesByUser =
            new SparseArray<>();
    private final SparseArray<ArrayMap<Integer, String>> mActiveTaskChallengeTokensByUser =
            new SparseArray<>();
    private final SparseArray<ArrayMap<String, String>> mActiveStandaloneChallengesByUser =
            new SparseArray<>();

    private ContentResolver mResolver;
    private ActivityTaskManagerInternal mAtmInternal;
    private LockPatternUtils mLockPatternUtils;
    private volatile boolean mServiceReady;
    private volatile boolean mBootCompleted;
    private volatile boolean mDisabled;

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, @Nullable android.net.Uri uri,
                int userId) {
            runSafely("handling App Lock settings change", () -> refreshUserState(userId));
        }
    };

    private final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                runSafely("handling screen off for App Lock",
                        AppLockManagerService.this::clearUnlockedStateForAllUsers);
            }
        }
    };

    private final BroadcastReceiver mAttemptReportReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AppLockUtils.ACTION_APP_LOCK_REPORT_ATTEMPT.equals(intent.getAction())) {
                return;
            }
            runSafely("handling App Lock auth result", () -> handleReportedAttempt(
                    intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME),
                    intent.getIntExtra(Intent.EXTRA_USER_ID, UserHandle.USER_NULL),
                    intent.getIntExtra(Intent.EXTRA_TASK_ID, INVALID_TASK_ID),
                    intent.getBooleanExtra(AppLockUtils.EXTRA_APP_LOCK_SUCCESSFUL, false),
                    intent.getStringExtra(AppLockUtils.EXTRA_APP_LOCK_CHALLENGE_TOKEN)));
        }
    };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            mHandler.post(() -> runSafely("handling App Lock task move to front",
                    () -> handleTaskMovedToFront(taskInfo)));
        }

        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            if (!focused) {
                mHandler.post(() -> runSafely("handling App Lock task focus change",
                        () -> clearUnlockedStateForTask(taskId)));
            }
        }

        @Override
        public void onTaskRemoved(int taskId) {
            mHandler.post(() -> runSafely("handling App Lock task removal",
                    () -> handleTaskRemoved(taskId)));
        }
    };

    private final AppLockManagerInternal mLocalService = new AppLockManagerInternal() {
        @Override
        public boolean shouldShowAppLockForPackage(@NonNull String packageName, int userId) {
            try {
                return shouldProtectPackage(packageName, userId);
            } catch (Throwable t) {
                disableFeature("checking whether a package should be App Locked", t);
                return false;
            }
        }

        @Override
        public Intent createConfirmAppLockIntentIfNeeded(@NonNull String packageName, int userId,
                @NonNull IntentSender intentSender) {
            try {
                if (!shouldProtectPackage(packageName, userId)) {
                    return null;
                }
                return createStandaloneChallengeIntent(packageName, userId, intentSender);
            } catch (Throwable t) {
                disableFeature("creating an App Lock confirmation intent", t);
                return null;
            }
        }

        @Override
        public boolean startConfirmAppLockForTask(@NonNull TaskInfo taskInfo) {
            try {
                return scheduleTaskChallengeIfNeeded(taskInfo);
            } catch (Throwable t) {
                disableFeature("starting an App Lock task challenge", t);
                return false;
            }
        }

        @Override
        public void onActivityLaunched(@NonNull TaskInfo taskInfo, @NonNull ActivityInfo activityInfo) {
            try {
                synchronized (mLock) {
                    maybeConsumePendingUnlockLocked(activityInfo.packageName, taskInfo.userId,
                            taskInfo.taskId);
                }
            } catch (Throwable t) {
                disableFeature("tracking a launched App Lock activity", t);
            }
        }
    };

    public AppLockManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishLocalService(AppLockManagerInternal.class, mLocalService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (mDisabled) {
            return;
        }
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            runSafely("initializing App Lock", this::initializeAtmDependencies);
            return;
        }
        if (phase == PHASE_BOOT_COMPLETED) {
            mBootCompleted = true;
        }
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        refreshUserState(user.getUserIdentifier());
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        refreshUserState(user.getUserIdentifier());
    }

    @Override
    public void onUserStopped(@NonNull TargetUser user) {
        final int userId = user.getUserIdentifier();
        synchronized (mLock) {
            mLockedPackagesByUser.remove(userId);
            mUnlockedTasksByUser.remove(userId);
            mPendingUnlocksByUser.remove(userId);
            mActiveTaskChallengesByUser.remove(userId);
            mActiveTaskChallengeTokensByUser.remove(userId);
            mActiveStandaloneChallengesByUser.remove(userId);
        }
    }

    private void refreshUserState(int userId) {
        if (userId == UserHandle.USER_ALL || mResolver == null) {
            return;
        }
        final ArraySet<String> lockedPackages = AppLockUtils.decodePackages(
                LineageSettings.Secure.getStringForUser(mResolver,
                        AppLockUtils.LINEAGE_SETTINGS_APP_LOCK_PACKAGES, userId));
        lockedPackages.remove(AppLockUtils.APP_LOCK_ACTIVITY_PACKAGE);
        final String homePackage = resolveHomePackage(userId);
        if (!TextUtils.isEmpty(homePackage)) {
            lockedPackages.remove(homePackage);
        }
        synchronized (mLock) {
            mLockedPackagesByUser.put(userId, lockedPackages);
            prunePendingUnlocksLocked(userId, SystemClock.elapsedRealtime());
        }
    }

    private boolean shouldProtectPackage(@Nullable String packageName, int userId) {
        if (!mServiceReady || !mBootCompleted || mDisabled) {
            return false;
        }
        if (packageName == null || APP_LOCK_ACTIVITY.getPackageName().equals(packageName)) {
            return false;
        }
        if (mLockPatternUtils == null || !mLockPatternUtils.isSecure(userId)) {
            return false;
        }
        final String homePackage = resolveHomePackage(userId);
        synchronized (mLock) {
            final ArraySet<String> lockedPackages = mLockedPackagesByUser.get(userId);
            if (lockedPackages == null || !lockedPackages.contains(packageName)) {
                return false;
            }
            if (TextUtils.equals(packageName, homePackage)) {
                return false;
            }

            final long now = SystemClock.elapsedRealtime();
            if (hasPendingUnlockLocked(packageName, userId, now) || hasUnlockedTaskLocked(
                    packageName, userId)) {
                return false;
            }
            return true;
        }
    }

    private boolean hasUnlockedTaskLocked(@NonNull String packageName, int userId) {
        final ArrayMap<Integer, String> unlockedTasks = mUnlockedTasksByUser.get(userId);
        if (unlockedTasks == null) {
            return false;
        }
        for (int i = 0; i < unlockedTasks.size(); i++) {
            if (packageName.equals(unlockedTasks.valueAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingUnlockLocked(@NonNull String packageName, int userId, long now) {
        prunePendingUnlocksLocked(userId, now);
        final ArrayMap<String, Long> pendingUnlocks = mPendingUnlocksByUser.get(userId);
        return pendingUnlocks != null && pendingUnlocks.containsKey(packageName);
    }

    private void prunePendingUnlocksLocked(int userId, long now) {
        final ArrayMap<String, Long> pendingUnlocks = mPendingUnlocksByUser.get(userId);
        if (pendingUnlocks == null) {
            return;
        }
        for (int i = pendingUnlocks.size() - 1; i >= 0; i--) {
            if (pendingUnlocks.valueAt(i) < now) {
                pendingUnlocks.removeAt(i);
            }
        }
        if (pendingUnlocks.isEmpty()) {
            mPendingUnlocksByUser.remove(userId);
        }
    }

    private void maybeConsumePendingUnlockLocked(@Nullable String packageName, int userId, int taskId) {
        if (packageName == null || taskId == INVALID_TASK_ID) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (!hasPendingUnlockLocked(packageName, userId, now)) {
            return;
        }
        ArrayMap<String, Long> pendingUnlocks = mPendingUnlocksByUser.get(userId);
        if (pendingUnlocks != null) {
            pendingUnlocks.remove(packageName);
            if (pendingUnlocks.isEmpty()) {
                mPendingUnlocksByUser.remove(userId);
            }
        }
        ArrayMap<Integer, String> unlockedTasks = mUnlockedTasksByUser.get(userId);
        if (unlockedTasks == null) {
            unlockedTasks = new ArrayMap<>();
            mUnlockedTasksByUser.put(userId, unlockedTasks);
        }
        unlockedTasks.put(taskId, packageName);
    }

    private boolean scheduleTaskChallengeIfNeeded(@NonNull TaskInfo taskInfo) {
        final String packageName = getLockedPackageForTask(taskInfo);
        if (packageName == null) {
            return false;
        }

        synchronized (mLock) {
            final long now = SystemClock.elapsedRealtime();
            if (hasPendingUnlockLocked(packageName, taskInfo.userId, now)) {
                maybeConsumePendingUnlockLocked(packageName, taskInfo.userId, taskInfo.taskId);
                return false;
            }

            final ArrayMap<Integer, String> unlockedTasks = mUnlockedTasksByUser.get(taskInfo.userId);
            if (unlockedTasks != null && packageName.equals(unlockedTasks.get(taskInfo.taskId))) {
                return false;
            }

            ArrayMap<Integer, String> activeChallenges = mActiveTaskChallengesByUser.get(
                    taskInfo.userId);
            if (activeChallenges == null) {
                activeChallenges = new ArrayMap<>();
                mActiveTaskChallengesByUser.put(taskInfo.userId, activeChallenges);
            }

            final String currentActivePackage = activeChallenges.get(taskInfo.taskId);
            if (packageName.equals(currentActivePackage)) {
                return true;
            }
            activeChallenges.put(taskInfo.taskId, packageName);
        }

        final int taskId = taskInfo.taskId;
        final int userId = taskInfo.userId;
        mHandler.post(() -> launchTaskChallenge(taskId, userId, packageName));
        return true;
    }

    private void handleTaskMovedToFront(@NonNull ActivityManager.RunningTaskInfo taskInfo) {
        final String packageName = getLockedPackageForTask(taskInfo);
        if (packageName == null) {
            return;
        }
        if (isChallengeActivity(taskInfo.topActivity)) {
            return;
        }

        synchronized (mLock) {
            final String activeChallengePackage = getActiveChallengePackageLocked(taskInfo.taskId);
            if (packageName.equals(activeChallengePackage)) {
                clearActiveChallengeLocked(taskInfo.taskId);
            }

            final long now = SystemClock.elapsedRealtime();
            if (hasPendingUnlockLocked(packageName, taskInfo.userId, now)) {
                maybeConsumePendingUnlockLocked(packageName, taskInfo.userId, taskInfo.taskId);
                return;
            }

            final ArrayMap<Integer, String> unlockedTasks = mUnlockedTasksByUser.get(taskInfo.userId);
            if (unlockedTasks != null && packageName.equals(unlockedTasks.get(taskInfo.taskId))) {
                return;
            }
        }

        scheduleTaskChallengeIfNeeded(taskInfo);
    }

    private void handleTaskRemoved(int taskId) {
        synchronized (mLock) {
            clearActiveChallengeLocked(taskId);
        }
        clearUnlockedStateForTask(taskId);
    }

    private void clearUnlockedStateForTask(int taskId) {
        synchronized (mLock) {
            for (int i = mUnlockedTasksByUser.size() - 1; i >= 0; i--) {
                final ArrayMap<Integer, String> unlockedTasks = mUnlockedTasksByUser.valueAt(i);
                unlockedTasks.remove(taskId);
                if (unlockedTasks.isEmpty()) {
                    mUnlockedTasksByUser.removeAt(i);
                }
            }
        }
    }

    private void clearUnlockedStateForAllUsers() {
        synchronized (mLock) {
            mUnlockedTasksByUser.clear();
            mPendingUnlocksByUser.clear();
        }
    }

    @Nullable
    private String getActiveChallengePackageLocked(int taskId) {
        for (int i = 0; i < mActiveTaskChallengesByUser.size(); i++) {
            final ArrayMap<Integer, String> activeChallenges = mActiveTaskChallengesByUser.valueAt(i);
            final String packageName = activeChallenges.get(taskId);
            if (packageName != null) {
                return packageName;
            }
        }
        return null;
    }

    private void clearActiveChallengeLocked(int taskId) {
        for (int i = mActiveTaskChallengesByUser.size() - 1; i >= 0; i--) {
            final ArrayMap<Integer, String> activeChallenges = mActiveTaskChallengesByUser.valueAt(i);
            activeChallenges.remove(taskId);
            if (activeChallenges.isEmpty()) {
                mActiveTaskChallengesByUser.removeAt(i);
            }
        }
        for (int i = mActiveTaskChallengeTokensByUser.size() - 1; i >= 0; i--) {
            final ArrayMap<Integer, String> activeTokens =
                    mActiveTaskChallengeTokensByUser.valueAt(i);
            activeTokens.remove(taskId);
            if (activeTokens.isEmpty()) {
                mActiveTaskChallengeTokensByUser.removeAt(i);
            }
        }
    }

    private void launchTaskChallenge(int taskId, int userId, @NonNull String packageName) {
        final String challengeToken = createTaskChallengeToken(taskId, userId, packageName);
        if (TextUtils.isEmpty(challengeToken)) {
            return;
        }
        final Intent intent = createTaskChallengeIntent(packageName, userId, taskId,
                challengeToken);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskId(taskId);
        options.setTaskOverlay(true, true);
        options.setPendingIntentBackgroundActivityStartMode(
                MODE_BACKGROUND_ACTIVITY_START_ALLOWED);

        try {
            getContext().startActivityAsUser(intent, options.toBundle(),
                    UserHandle.of(userId));
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to start App Lock task challenge for " + packageName, e);
            synchronized (mLock) {
                clearActiveChallengeLocked(taskId);
            }
        }
    }

    @NonNull
    private Intent createStandaloneChallengeIntent(@NonNull String packageName, int userId,
            @NonNull IntentSender intentSender) {
        final String challengeToken = createStandaloneChallengeToken(packageName, userId);
        return new Intent()
                .setComponent(APP_LOCK_ACTIVITY)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(Intent.EXTRA_INTENT, intentSender)
                .putExtra(AppLockUtils.EXTRA_APP_LOCK_CHALLENGE_TOKEN, challengeToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }

    @NonNull
    private Intent createTaskChallengeIntent(@NonNull String packageName, int userId, int taskId,
            @NonNull String challengeToken) {
        return new Intent()
                .setComponent(APP_LOCK_ACTIVITY)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(Intent.EXTRA_TASK_ID, taskId)
                .putExtra(AppLockUtils.EXTRA_APP_LOCK_CHALLENGE_TOKEN, challengeToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }

    @NonNull
    private String createTaskChallengeToken(int taskId, int userId, @NonNull String packageName) {
        synchronized (mLock) {
            final ArrayMap<Integer, String> activeChallenges = mActiveTaskChallengesByUser.get(userId);
            if (activeChallenges == null || !packageName.equals(activeChallenges.get(taskId))) {
                return "";
            }

            ArrayMap<Integer, String> activeTokens = mActiveTaskChallengeTokensByUser.get(userId);
            if (activeTokens == null) {
                activeTokens = new ArrayMap<>();
                mActiveTaskChallengeTokensByUser.put(userId, activeTokens);
            }

            final String challengeToken = java.util.UUID.randomUUID().toString();
            activeTokens.put(taskId, challengeToken);
            return challengeToken;
        }
    }

    @NonNull
    private String createStandaloneChallengeToken(@NonNull String packageName, int userId) {
        synchronized (mLock) {
            ArrayMap<String, String> activeChallenges = mActiveStandaloneChallengesByUser.get(userId);
            if (activeChallenges == null) {
                activeChallenges = new ArrayMap<>();
                mActiveStandaloneChallengesByUser.put(userId, activeChallenges);
            }

            final String challengeToken = java.util.UUID.randomUUID().toString();
            activeChallenges.put(packageName, challengeToken);
            return challengeToken;
        }
    }

    @Nullable
    private String getLockedPackageForTask(@NonNull TaskInfo taskInfo) {
        final String topPackage = getPackageName(taskInfo.topActivity);
        if (shouldProtectPackage(topPackage, taskInfo.userId)) {
            return topPackage;
        }
        final String basePackage = getPackageName(taskInfo.baseActivity);
        if (shouldProtectPackage(basePackage, taskInfo.userId)) {
            return basePackage;
        }
        return null;
    }

    private boolean isChallengeActivity(@Nullable ComponentName componentName) {
        if (componentName == null) {
            return false;
        }
        return APP_LOCK_ACTIVITY.equals(componentName)
                || (AppLockUtils.SETTINGS_PACKAGE.equals(componentName.getPackageName())
                && AppLockUtils.CONFIRM_DEVICE_CREDENTIAL_ACTIVITY_CLASS.equals(
                        componentName.getClassName()));
    }

    @Nullable
    private static String getPackageName(@Nullable ComponentName componentName) {
        return componentName != null ? componentName.getPackageName() : null;
    }

    private void handleReportedAttempt(@Nullable String packageName, int userId, int taskId,
            boolean successful, @Nullable String challengeToken) {
        if (packageName == null || userId == UserHandle.USER_NULL
                || TextUtils.isEmpty(challengeToken)) {
            return;
        }

        synchronized (mLock) {
            if (taskId != INVALID_TASK_ID) {
                final ArrayMap<Integer, String> activeChallenges = mActiveTaskChallengesByUser.get(
                        userId);
                final ArrayMap<Integer, String> activeTokens = mActiveTaskChallengeTokensByUser.get(
                        userId);
                if (activeChallenges == null || activeTokens == null
                        || !packageName.equals(activeChallenges.get(taskId))
                        || !TextUtils.equals(challengeToken, activeTokens.get(taskId))) {
                    return;
                }

                clearActiveChallengeLocked(taskId);
                if (successful) {
                    ArrayMap<Integer, String> unlockedTasks = mUnlockedTasksByUser.get(userId);
                    if (unlockedTasks == null) {
                        unlockedTasks = new ArrayMap<>();
                        mUnlockedTasksByUser.put(userId, unlockedTasks);
                    }
                    unlockedTasks.put(taskId, packageName);
                }
                return;
            }

            final ArrayMap<String, String> activeChallenges =
                    mActiveStandaloneChallengesByUser.get(userId);
            if (activeChallenges == null
                    || !TextUtils.equals(challengeToken, activeChallenges.get(packageName))) {
                return;
            }

            activeChallenges.remove(packageName);
            if (activeChallenges.isEmpty()) {
                mActiveStandaloneChallengesByUser.remove(userId);
            }

            if (successful) {
                ArrayMap<String, Long> pendingUnlocks = mPendingUnlocksByUser.get(userId);
                if (pendingUnlocks == null) {
                    pendingUnlocks = new ArrayMap<>();
                    mPendingUnlocksByUser.put(userId, pendingUnlocks);
                }
                pendingUnlocks.put(packageName,
                        SystemClock.elapsedRealtime() + PENDING_UNLOCK_TIMEOUT_MS);
            } else {
                final ArrayMap<String, Long> pendingUnlocks = mPendingUnlocksByUser.get(userId);
                if (pendingUnlocks != null) {
                    pendingUnlocks.remove(packageName);
                    if (pendingUnlocks.isEmpty()) {
                        mPendingUnlocksByUser.remove(userId);
                    }
                }
            }
        }
    }

    private void initializeAtmDependencies() {
        mResolver = getContext().getContentResolver();
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mLockPatternUtils = new LockPatternUtils(getContext());
        if (mAtmInternal != null) {
            mAtmInternal.registerTaskStackListener(mTaskStackListener);
        }

        mResolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                        AppLockUtils.LINEAGE_SETTINGS_APP_LOCK_PACKAGES),
                false, mSettingsObserver, UserHandle.USER_ALL);
        mResolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                        AppLockUtils.LINEAGE_SETTINGS_APP_LOCK_BIOMETRICS_ALLOWED),
                false, mSettingsObserver, UserHandle.USER_ALL);

        final IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        getContext().registerReceiver(mScreenStateReceiver, screenStateFilter,
                Context.RECEIVER_NOT_EXPORTED);
        final IntentFilter attemptReportFilter =
                new IntentFilter(AppLockUtils.ACTION_APP_LOCK_REPORT_ATTEMPT);
        getContext().registerReceiverAsUser(mAttemptReportReceiver, UserHandle.ALL,
                attemptReportFilter, android.Manifest.permission.STATUS_BAR_SERVICE, mHandler,
                Context.RECEIVER_EXPORTED);

        final UserManager userManager = getContext().getSystemService(UserManager.class);
        if (userManager != null) {
            for (UserInfo userInfo : userManager.getAliveUsers()) {
                refreshUserState(userInfo.id);
            }
        }
        mServiceReady = true;
    }

    @Nullable
    private String resolveHomePackage(int userId) {
        final PackageManager packageManager = getContext().getPackageManager();
        if (packageManager == null) {
            return null;
        }

        final Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final ResolveInfo resolveInfo = packageManager.resolveActivityAsUser(homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY, userId);
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            return null;
        }
        return resolveInfo.activityInfo.packageName;
    }

    private void runSafely(@NonNull String reason, @NonNull Runnable action) {
        if (mDisabled) {
            return;
        }
        try {
            action.run();
        } catch (Throwable t) {
            disableFeature(reason, t);
        }
    }

    private void disableFeature(@NonNull String reason, @NonNull Throwable t) {
        if (mDisabled) {
            return;
        }
        mDisabled = true;
        mServiceReady = false;
        mBootCompleted = false;
        synchronized (mLock) {
            mLockedPackagesByUser.clear();
            mUnlockedTasksByUser.clear();
            mPendingUnlocksByUser.clear();
            mActiveTaskChallengesByUser.clear();
            mActiveTaskChallengeTokensByUser.clear();
            mActiveStandaloneChallengesByUser.clear();
        }
        Slog.wtf(TAG, "Disabling App Lock after failure while " + reason, t);
    }
}
