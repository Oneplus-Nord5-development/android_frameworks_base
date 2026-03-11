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

package android.hardware.display;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.Manifest;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.util.Preconditions;
import com.android.server.display.feature.flags.Flags;

/**
 * Manager for controlling system display refresh rate policies.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
public final class RefreshRateManager {
    private final Context mContext;
    private final IRefreshRateManagerService mService;

    RefreshRateManager(@NonNull Context context, @NonNull IRefreshRateManagerService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Enables or disables extreme refresh rate mode.
     */
    @android.annotation.RequiresPermission(Manifest.permission.MANAGE_REFRESH_RATE)
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public void setExtremeRefreshRateEnabled(boolean enabled) {
        try {
            mService.setExtremeRefreshRateEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether extreme refresh rate mode is enabled.
     */
    @android.annotation.RequiresPermission(Manifest.permission.MANAGE_REFRESH_RATE)
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public boolean isExtremeRefreshRateEnabled() {
        try {
            return mService.isExtremeRefreshRateEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets a per-app refresh rate range.
     */
    @android.annotation.RequiresPermission(Manifest.permission.MANAGE_REFRESH_RATE)
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public void setPerAppRefreshRateRange(@NonNull String packageName, float minRefreshRate,
            float maxRefreshRate) {
        Preconditions.checkStringNotEmpty(packageName, "packageName");
        try {
            mService.setPerAppRefreshRateRange(packageName, minRefreshRate, maxRefreshRate);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears any per-app refresh rate range for the given package.
     */
    @android.annotation.RequiresPermission(Manifest.permission.MANAGE_REFRESH_RATE)
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public void clearPerAppRefreshRateRange(@NonNull String packageName) {
        Preconditions.checkStringNotEmpty(packageName, "packageName");
        try {
            mService.clearPerAppRefreshRateRange(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the per-app refresh rate range for the given package, or {@code null}.
     */
    @android.annotation.RequiresPermission(Manifest.permission.MANAGE_REFRESH_RATE)
    @Nullable
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public float[] getPerAppRefreshRateRange(@NonNull String packageName) {
        Preconditions.checkStringNotEmpty(packageName, "packageName");
        try {
            return mService.getPerAppRefreshRateRange(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of packages that have a per-app refresh rate range configured.
     */
    @android.annotation.RequiresPermission(Manifest.permission.MANAGE_REFRESH_RATE)
    @NonNull
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public String[] getConfiguredRefreshRatePackages() {
        try {
            String[] packages = mService.getConfiguredRefreshRatePackages();
            return packages != null ? packages : new String[0];
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets a temporary refresh rate range override.
     *
     * @param token A token used to clear the override.
     * @param timeoutMs How long the override should stay active, or 0 to disable auto-clear.
     */
    @android.annotation.RequiresPermission(Manifest.permission.MANAGE_REFRESH_RATE)
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public void setTemporaryRefreshRateRange(@NonNull IBinder token, float minRefreshRate,
            float maxRefreshRate, long timeoutMs) {
        Preconditions.checkNotNull(token, "token");
        try {
            mService.setTemporaryRefreshRateRange(token, minRefreshRate, maxRefreshRate, timeoutMs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears a temporary refresh rate range override.
     */
    @android.annotation.RequiresPermission(Manifest.permission.MANAGE_REFRESH_RATE)
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public void clearTemporaryRefreshRateRange(@NonNull IBinder token) {
        Preconditions.checkNotNull(token, "token");
        try {
            mService.clearTemporaryRefreshRateRange(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Nullable
    @FlaggedApi(Flags.FLAG_REFRESH_RATE_MANAGER_API)
    public static RefreshRateManager getInstance(Context context) {
        IRefreshRateManagerService service = IRefreshRateManagerService.Stub.asInterface(
                ServiceManager.getService(Context.REFRESH_RATE_SERVICE));
        if (service == null) {
            return null;
        }
        return new RefreshRateManager(context, service);
    }
}
