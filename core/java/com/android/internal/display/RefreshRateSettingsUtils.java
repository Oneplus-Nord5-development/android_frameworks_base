/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.display;

import static android.hardware.display.DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Constants and utility methods for refresh rate settings.
 */
public class RefreshRateSettingsUtils {

    private static final String TAG = "RefreshRateSettingsUtils";

    public static final float DEFAULT_REFRESH_RATE = 60f;

    /**
     * Find the highest refresh rate among all the modes of the default display.
     *
     * This method will acquire DisplayManager.mLock, so calling it while holding other locks
     * should be done with care.
     * @param context The context
     * @return The highest refresh rate
     */
    public static float findHighestRefreshRateForDefaultDisplay(Context context) {
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        final Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            Log.w(TAG, "No valid default display device");
            return DEFAULT_REFRESH_RATE;
        }

        float maxRefreshRate = DEFAULT_REFRESH_RATE;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getRefreshRate() > maxRefreshRate) {
                maxRefreshRate = mode.getRefreshRate();
            }
        }
        return maxRefreshRate;
    }

    /**
     * Find the highest refresh rate among all the modes of all the displays.
     *
     * This method will acquire DisplayManager.mLock, so calling it while holding other locks
     * should be done with care.
     * @param context The context
     * @return The highest refresh rate
     */
    public static float findHighestRefreshRateAmongAllDisplays(Context context) {
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        final Display[] displays = dm.getDisplays(DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED);
        if (displays.length == 0) {
            Log.w(TAG, "No valid display devices");
            return DEFAULT_REFRESH_RATE;
        }

        float maxRefreshRate = DEFAULT_REFRESH_RATE;
        for (Display display : displays) {
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getRefreshRate() > maxRefreshRate) {
                    maxRefreshRate = mode.getRefreshRate();
                }
            }
        }
        return maxRefreshRate;
    }

    /**
     * Find the highest refresh rate among all the modes of all the built-in/physical displays.
     *
     * This method will acquire DisplayManager.mLock, so calling it while holding other locks
     * should be done with care.
     * @param context The context
     * @return The highest refresh rate
     */
    public static float findHighestRefreshRateAmongAllBuiltInDisplays(Context context) {
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        final Display[] displays = dm.getDisplays(DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED);
        if (displays.length == 0) {
            Log.w(TAG, "No valid display devices");
            return DEFAULT_REFRESH_RATE;
        }

        float maxRefreshRate = DEFAULT_REFRESH_RATE;
        for (Display display : displays) {
            if (display.getType() != Display.TYPE_INTERNAL) continue;
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getRefreshRate() > maxRefreshRate) {
                    maxRefreshRate = mode.getRefreshRate();
                }
            }
        }
        return maxRefreshRate;
    }

    /**
     * Parses per-app refresh rate settings from storage.
     *
     * Format: {@code package=min:max,package2=min:max}
     */
    public static Map<String, SurfaceControl.RefreshRateRange> parsePerAppRefreshRateConfig(
            String value) {
        Map<String, SurfaceControl.RefreshRateRange> result = new ArrayMap<>();
        if (TextUtils.isEmpty(value)) {
            return result;
        }
        String[] entries = value.split(",");
        for (String entry : entries) {
            if (TextUtils.isEmpty(entry)) {
                continue;
            }
            String[] parts = entry.split("=");
            if (parts.length != 2 || TextUtils.isEmpty(parts[0])) {
                continue;
            }
            String packageName = parts[0].trim();
            String[] rangeParts = parts[1].split(":");
            if (rangeParts.length != 2) {
                continue;
            }
            try {
                float min = Float.parseFloat(rangeParts[0].trim());
                float max = Float.parseFloat(rangeParts[1].trim());
                if (min <= 0f && max <= 0f) {
                    continue;
                }
                result.put(packageName, new SurfaceControl.RefreshRateRange(min, max));
            } catch (NumberFormatException e) {
                // Skip invalid entries.
            }
        }
        return result;
    }

    /**
     * Builds per-app refresh rate settings for storage.
     */
    public static String buildPerAppRefreshRateConfig(
            Map<String, SurfaceControl.RefreshRateRange> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<>(values.keySet());
        Collections.sort(keys);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String packageName = keys.get(i);
            SurfaceControl.RefreshRateRange range = values.get(packageName);
            if (range == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(packageName)
                    .append("=")
                    .append(Float.toString(range.min))
                    .append(":")
                    .append(Float.toString(range.max));
        }
        return builder.toString();
    }
}
