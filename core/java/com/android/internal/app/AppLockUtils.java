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

package com.android.internal.app;

import android.annotation.NonNull;
import android.text.TextUtils;
import android.util.ArraySet;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.Collection;

/**
 * Shared helpers and keys for LineageOS App Lock.
 *
 * @hide
 */
public final class AppLockUtils {
    public static final String ACTION_APP_LOCK_REPORT_ATTEMPT =
            "com.android.internal.app.action.APP_LOCK_REPORT_ATTEMPT";

    public static final String LINEAGE_SETTINGS_APP_LOCK_PACKAGES = "lineage_app_lock_packages";
    public static final String LINEAGE_SETTINGS_APP_LOCK_BIOMETRICS_ALLOWED =
            "lineage_app_lock_biometrics_allowed";
    public static final String LINEAGE_SETTINGS_APP_LOCK_CUSTOM_PASSWORD =
            "lineage_app_lock_custom_password";
    public static final String LINEAGE_SETTINGS_APP_LOCK_CUSTOM_SALT =
            "lineage_app_lock_custom_salt";
    public static final String LINEAGE_SETTINGS_APP_LOCK_CUSTOM_IS_PIN =
            "lineage_app_lock_custom_is_pin";

    public static final String APP_LOCK_ACTIVITY_PACKAGE = "com.android.systemui";
    public static final String APP_LOCK_ACTIVITY_CLASS =
            "com.android.systemui.keyguard.AppLockActivity";

    public static final String SETTINGS_PACKAGE = "com.android.settings";
    public static final String CONFIRM_DEVICE_CREDENTIAL_ACTIVITY_CLASS =
            "com.android.settings.password.ConfirmDeviceCredentialActivity$InternalActivity";

    public static final String EXTRA_BIOMETRIC_PROMPT_AUTHENTICATORS =
            "biometric_prompt_authenticators";
    public static final String EXTRA_BIOMETRIC_PROMPT_NEGATIVE_BUTTON_TEXT =
            "biometric_prompt_negative_button_text";
    public static final String EXTRA_BIOMETRIC_PROMPT_HIDE_BACKGROUND =
            "biometric_prompt_hide_background";
    public static final String EXTRA_DATA = "extra_data";
    public static final String EXTRA_ALLOW_ANY_USER = "allow_any_user";
    public static final String EXTRA_APP_LOCK_CHALLENGE_TOKEN =
            "lineage_app_lock_challenge_token";
    public static final String EXTRA_APP_LOCK_SUCCESSFUL = "lineage_app_lock_successful";

    private AppLockUtils() {
    }

    @NonNull
    public static String encodePackages(@NonNull Collection<String> packages) {
        if (packages.isEmpty()) {
            return "";
        }
        final JSONArray array = new JSONArray();
        for (String packageName : packages) {
            if (!TextUtils.isEmpty(packageName)) {
                array.put(packageName);
            }
        }
        return array.toString();
    }

    @NonNull
    public static ArraySet<String> decodePackages(String serializedPackages) {
        final ArraySet<String> packages = new ArraySet<>();
        if (TextUtils.isEmpty(serializedPackages)) {
            return packages;
        }

        try {
            final JSONArray array = new JSONArray(serializedPackages);
            for (int i = 0; i < array.length(); i++) {
                final String packageName = array.optString(i);
                if (!TextUtils.isEmpty(packageName)) {
                    packages.add(packageName);
                }
            }
        } catch (JSONException ignored) {
            // Ignore malformed values and return an empty set.
        }
        return packages;
    }
}
