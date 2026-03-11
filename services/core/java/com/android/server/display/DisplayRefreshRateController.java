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

package com.android.server.display;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.SurfaceControl;
import android.app.WindowConfiguration;

import com.android.internal.display.RefreshRateSettingsUtils;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;
import com.android.server.wm.WindowManagerInternal;

import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.IRefreshRateManagerService;

import java.util.Map;

/**
 * System service that manages refresh rate policies and per-app configuration.
 */
public final class DisplayRefreshRateController extends SystemService {
    private static final String TAG = "DisplayRefreshRateController";

    private final Handler mHandler;
    private final ContentResolver mContentResolver;

    private DisplayManagerInternal mDisplayManagerInternal;
    private WindowManagerInternal mWindowManagerInternal;

    private final Object mLock = new Object();
    private final Map<String, SurfaceControl.RefreshRateRange> mPerAppRanges = new ArrayMap<>();

    @Nullable
    private String mAppliedPackage;
    @Nullable
    private SurfaceControl.RefreshRateRange mAppliedRange;

    private boolean mExtremeRefreshRateEnabled;

    @Nullable
    private IBinder mTempOverrideToken;
    @Nullable
    private IBinder.DeathRecipient mTempOverrideDeathRecipient;
    @Nullable
    private Runnable mTempOverrideTimeout;

    public DisplayRefreshRateController(Context context) {
        super(context);
        mHandler = new Handler(context.getMainLooper());
        mContentResolver = context.getContentResolver();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.REFRESH_RATE_SERVICE, mBinderService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mDisplayManagerInternal = getLocalService(DisplayManagerInternal.class);
            mWindowManagerInternal = getLocalService(WindowManagerInternal.class);
            registerSettingsObserver();
            registerTaskStackListener();
            refreshSettings();
        }
    }

    @Override
    public void onUserSwitching(@NonNull TargetUser from, @NonNull TargetUser to) {
        refreshSettings();
    }

    private void registerSettingsObserver() {
        ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                refreshSettings();
            }
        };
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.EXTREME_REFRESH_RATE),
                false, observer, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.PER_APP_REFRESH_RATE_CONFIG),
                false, observer, UserHandle.USER_ALL);
    }

    private void registerTaskStackListener() {
        try {
            ActivityTaskManager.getService().registerTaskStackListener(
                    new android.app.TaskStackListener() {
                        @Override
                        public void onTaskStackChanged() {
                            updateForegroundApp();
                        }
                    });
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to register task stack listener", e);
        }
    }

    private void refreshSettings() {
        mHandler.post(() -> {
            if (mDisplayManagerInternal == null || mWindowManagerInternal == null) {
                return;
            }
            int userId = ActivityManager.getCurrentUser();
            boolean extreme = Settings.System.getIntForUser(mContentResolver,
                    Settings.System.EXTREME_REFRESH_RATE, 0, userId) == 1;
            String perAppConfig = Settings.System.getStringForUser(mContentResolver,
                    Settings.System.PER_APP_REFRESH_RATE_CONFIG, userId);
            Map<String, SurfaceControl.RefreshRateRange> parsed =
                    RefreshRateSettingsUtils.parsePerAppRefreshRateConfig(perAppConfig);

            synchronized (mLock) {
                mPerAppRanges.clear();
                mPerAppRanges.putAll(parsed);
            }

            if (mExtremeRefreshRateEnabled != extreme) {
                mExtremeRefreshRateEnabled = extreme;
                mDisplayManagerInternal.setExtremeRefreshRateEnabled(extreme);
            }

            updateForegroundApp();
        });
    }

    private void updateForegroundApp() {
        mHandler.post(() -> {
            RootTaskInfo info;
            try {
                info = ActivityTaskManager.getService().getFocusedRootTaskInfo();
            } catch (RemoteException e) {
                return;
            }
            if (info == null || info.topActivity == null || !info.visible) {
                applyForegroundPackage(null, null);
                return;
            }

            String packageName = info.topActivity.getPackageName();
            boolean isFullscreen = info.configuration.windowConfiguration.getWindowingMode()
                    == WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
            if (!isFullscreen) {
                applyForegroundPackage(packageName, null);
                return;
            }

            SurfaceControl.RefreshRateRange range;
            synchronized (mLock) {
                range = mPerAppRanges.get(packageName);
            }
            applyForegroundPackage(packageName, range);
        });
    }

    private void applyForegroundPackage(@Nullable String packageName,
            @Nullable SurfaceControl.RefreshRateRange range) {
        synchronized (mLock) {
            if (mAppliedPackage != null) {
                boolean shouldClear = packageName == null || !mAppliedPackage.equals(packageName)
                        || range == null;
                boolean rangeChanged = range != null && mAppliedRange != null
                        && (!floatEquals(range.min, mAppliedRange.min)
                        || !floatEquals(range.max, mAppliedRange.max));
                if (shouldClear || rangeChanged) {
                    mWindowManagerInternal.removeRefreshRateRangeForPackage(mAppliedPackage);
                    mAppliedPackage = null;
                    mAppliedRange = null;
                }
            }

            if (packageName == null || range == null) {
                return;
            }

            if (mAppliedPackage == null) {
                float min = Math.max(0f, range.min);
                float max = range.max > 0f ? range.max : Float.POSITIVE_INFINITY;
                if (max > 0f && min > max) {
                    min = max;
                }
                mWindowManagerInternal.addRefreshRateRangeForPackage(packageName, min, max);
                mAppliedPackage = packageName;
                mAppliedRange = new SurfaceControl.RefreshRateRange(range.min, range.max);
            }
        }
    }

    private static boolean floatEquals(float a, float b) {
        return Math.abs(a - b) < SurfaceControl.RefreshRateRange.FLOAT_TOLERANCE;
    }

    private final IRefreshRateManagerService.Stub mBinderService =
            new IRefreshRateManagerService.Stub() {
                @Override
                public void setExtremeRefreshRateEnabled(boolean enabled) {
                    enforceManageRefreshRatePermission();
                    int userId = ActivityManager.getCurrentUser();
                    Settings.System.putIntForUser(mContentResolver,
                            Settings.System.EXTREME_REFRESH_RATE, enabled ? 1 : 0, userId);
                    mHandler.post(() -> mDisplayManagerInternal.setExtremeRefreshRateEnabled(
                            enabled));
                }

                @Override
                public boolean isExtremeRefreshRateEnabled() {
                    enforceManageRefreshRatePermission();
                    int userId = ActivityManager.getCurrentUser();
                    return Settings.System.getIntForUser(mContentResolver,
                            Settings.System.EXTREME_REFRESH_RATE, 0, userId) == 1;
                }

                @Override
                public void setPerAppRefreshRateRange(String packageName, float minRefreshRate,
                        float maxRefreshRate) {
                    enforceManageRefreshRatePermission();
                    Preconditions.checkStringNotEmpty(packageName, "packageName");
                    updatePerAppRange(packageName, minRefreshRate, maxRefreshRate);
                }

                @Override
                public void clearPerAppRefreshRateRange(String packageName) {
                    enforceManageRefreshRatePermission();
                    Preconditions.checkStringNotEmpty(packageName, "packageName");
                    updatePerAppRange(packageName, 0f, 0f);
                }

                @Override
                public float[] getPerAppRefreshRateRange(String packageName) {
                    enforceManageRefreshRatePermission();
                    Preconditions.checkStringNotEmpty(packageName, "packageName");
                    synchronized (mLock) {
                        SurfaceControl.RefreshRateRange range = mPerAppRanges.get(packageName);
                        if (range == null) {
                            return null;
                        }
                        return new float[] { range.min, range.max };
                    }
                }

                @Override
                public String[] getConfiguredRefreshRatePackages() {
                    enforceManageRefreshRatePermission();
                    synchronized (mLock) {
                        return mPerAppRanges.keySet().toArray(new String[0]);
                    }
                }

                @Override
                public void setTemporaryRefreshRateRange(IBinder token, float minRefreshRate,
                        float maxRefreshRate, long timeoutMs) {
                    enforceManageRefreshRatePermission();
                    Preconditions.checkNotNull(token, "token");
                    mHandler.post(() -> setTemporaryOverride(token, minRefreshRate, maxRefreshRate,
                            timeoutMs));
                }

                @Override
                public void clearTemporaryRefreshRateRange(IBinder token) {
                    enforceManageRefreshRatePermission();
                    Preconditions.checkNotNull(token, "token");
                    mHandler.post(() -> clearTemporaryOverride(token));
                }
            };

    private void setTemporaryOverride(@NonNull IBinder token, float minRefreshRate,
            float maxRefreshRate, long timeoutMs) {
        if (mTempOverrideToken != null && mTempOverrideToken != token) {
            clearTemporaryOverride(mTempOverrideToken);
        }

        if (mTempOverrideToken == null) {
            mTempOverrideToken = token;
            if (mTempOverrideDeathRecipient == null) {
                mTempOverrideDeathRecipient =
                        () -> mHandler.post(() -> clearTemporaryOverride(token));
            }
            try {
                token.linkToDeath(mTempOverrideDeathRecipient, 0);
            } catch (RemoteException e) {
                clearTemporaryOverride(token);
                return;
            }
        }

        mDisplayManagerInternal.setTemporaryRefreshRateRange(minRefreshRate, maxRefreshRate);

        if (mTempOverrideTimeout != null) {
            mHandler.removeCallbacks(mTempOverrideTimeout);
        }
        if (timeoutMs > 0) {
            mTempOverrideTimeout = () -> clearTemporaryOverride(token);
            mHandler.postDelayed(mTempOverrideTimeout, timeoutMs);
        }
    }

    private void clearTemporaryOverride(@NonNull IBinder token) {
        if (mTempOverrideToken != token) {
            return;
        }
        if (mTempOverrideTimeout != null) {
            mHandler.removeCallbacks(mTempOverrideTimeout);
            mTempOverrideTimeout = null;
        }
        if (mTempOverrideDeathRecipient != null) {
            token.unlinkToDeath(mTempOverrideDeathRecipient, 0);
            mTempOverrideDeathRecipient = null;
        }
        mTempOverrideToken = null;
        mDisplayManagerInternal.clearTemporaryRefreshRateRange();
    }

    private void updatePerAppRange(@NonNull String packageName, float minRefreshRate,
            float maxRefreshRate) {
        int userId = ActivityManager.getCurrentUser();
        float min = Math.max(0f, minRefreshRate);
        float max = Math.max(0f, maxRefreshRate);
        String current = Settings.System.getStringForUser(mContentResolver,
                Settings.System.PER_APP_REFRESH_RATE_CONFIG, userId);
        Map<String, SurfaceControl.RefreshRateRange> updated =
                RefreshRateSettingsUtils.parsePerAppRefreshRateConfig(current);
        if (min <= 0f && max <= 0f) {
            updated.remove(packageName);
        } else {
            updated.put(packageName, new SurfaceControl.RefreshRateRange(min, max));
        }
        String updatedValue = RefreshRateSettingsUtils.buildPerAppRefreshRateConfig(updated);
        if (!TextUtils.equals(current, updatedValue)) {
            Settings.System.putStringForUser(mContentResolver,
                    Settings.System.PER_APP_REFRESH_RATE_CONFIG, updatedValue, userId);
        }
        refreshSettings();
    }

    private void enforceManageRefreshRatePermission() {
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_REFRESH_RATE,
                "Requires MANAGE_REFRESH_RATE permission");
    }
}
