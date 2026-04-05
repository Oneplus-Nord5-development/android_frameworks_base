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

package com.android.systemui.keyguard;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG;
import static android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.widget.ImageView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.app.AppLockUtils;
import com.android.systemui.res.R;

import lineageos.providers.LineageSettings;

/**
 * Authentication surface for LineageOS App Lock.
 */
public class AppLockActivity extends Activity {
    private static final int REQUEST_CODE_CONFIRM_CREDENTIALS = 1;

    private final OnBackInvokedCallback mBackCallback = this::finishAfterCancel;

    private UserManager mUserManager;
    private PackageManager mPackageManager;

    private boolean mAuthenticationLaunched;
    private boolean mAttemptReported;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUserManager = getSystemService(UserManager.class);
        mPackageManager = getPackageManager();

        setOverlayWithDecorCaptionEnabled(true);
        setContentView(R.layout.auth_biometric_background);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBackCallback);

        updateUiForIntent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mAuthenticationLaunched = false;
        mAttemptReported = false;
        updateUiForIntent();
        if (hasWindowFocus()) {
            showConfirmCredentialActivity();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            showConfirmCredentialActivity();
        }
    }

    @Override
    protected void onDestroy() {
        if (!mAttemptReported) {
            reportAttempt(false);
        }
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mBackCallback);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finishAfterCancel();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_CONFIRM_CREDENTIALS) {
            return;
        }

        if (resultCode == RESULT_OK) {
            reportAttempt(true);
            launchTargetIfNeeded();
            finish();
            return;
        }

        finishAfterCancel();
    }

    private void updateUiForIntent() {
        final Drawable icon = getBadgedIcon();
        final ImageView iconView = findViewById(R.id.icon);
        if (iconView != null) {
            iconView.setImageDrawable(icon);
        }
    }

    private void showConfirmCredentialActivity() {
        if (mAuthenticationLaunched || isFinishing()) {
            return;
        }

        final String protectedPackage = getProtectedPackageName();
        if (TextUtils.isEmpty(protectedPackage)) {
            finishAfterCancel();
            return;
        }

        final Intent confirmIntent = new Intent().setClassName(
                AppLockUtils.SETTINGS_PACKAGE,
                AppLockUtils.CONFIRM_DEVICE_CREDENTIAL_ACTIVITY_CLASS);
        confirmIntent.putExtra(Intent.EXTRA_USER_ID, getTargetUserId());
        confirmIntent.putExtra(AppLockUtils.EXTRA_ALLOW_ANY_USER, true);
        confirmIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, protectedPackage);
        confirmIntent.putExtra(KeyguardManager.EXTRA_TITLE, getTitleText(protectedPackage));
        confirmIntent.putExtra(KeyguardManager.EXTRA_DESCRIPTION,
                getDescriptionText(protectedPackage));
        confirmIntent.putExtra(AppLockUtils.EXTRA_BIOMETRIC_PROMPT_NEGATIVE_BUTTON_TEXT,
                getString(android.R.string.cancel));
        confirmIntent.putExtra(AppLockUtils.EXTRA_BIOMETRIC_PROMPT_AUTHENTICATORS,
                getAllowedAuthenticators());

        final Bundle options;
        if (isTaskOverlayChallenge()) {
            final ActivityOptions launchOptions = ActivityOptions.makeBasic();
            launchOptions.setLaunchTaskId(getTaskId());
            launchOptions.setTaskOverlay(true /* taskOverlay */, true /* canResume */);
            confirmIntent.putExtra(KeyguardManager.EXTRA_FORCE_TASK_OVERLAY, true);
            options = launchOptions.toBundle();
        } else {
            options = null;
        }

        mAuthenticationLaunched = true;
        startActivityForResult(confirmIntent, REQUEST_CODE_CONFIRM_CREDENTIALS, options);
    }

    private void launchTargetIfNeeded() {
        final IntentSender target = getIntent().getParcelableExtra(Intent.EXTRA_INTENT,
                IntentSender.class);
        if (target == null) {
            return;
        }

        try {
            final ActivityOptions activityOptions = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            startIntentSenderForResult(target, -1, null, 0, 0, 0, activityOptions.toBundle());
        } catch (IntentSender.SendIntentException ignored) {
            // Ignore failed replays; the pending unlock token has a short timeout.
        }
    }

    private void reportAttempt(boolean successful) {
        if (mAttemptReported) {
            return;
        }
        mAttemptReported = true;
        final String protectedPackage = getProtectedPackageName();
        final String challengeToken = getChallengeToken();
        if (TextUtils.isEmpty(protectedPackage) || TextUtils.isEmpty(challengeToken)) {
            return;
        }

        final Intent reportIntent = new Intent(AppLockUtils.ACTION_APP_LOCK_REPORT_ATTEMPT)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, protectedPackage)
                .putExtra(Intent.EXTRA_USER_ID, getTargetUserId())
                .putExtra(Intent.EXTRA_TASK_ID, getProtectedTaskId())
                .putExtra(AppLockUtils.EXTRA_APP_LOCK_CHALLENGE_TOKEN, challengeToken)
                .putExtra(AppLockUtils.EXTRA_APP_LOCK_SUCCESSFUL, successful);
        sendBroadcastAsUser(reportIntent, UserHandle.of(getTargetUserId()));
    }

    private void finishAfterCancel() {
        reportAttempt(false);
        if (!isTaskOverlayChallenge()) {
            finish();
            return;
        }

        final Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        finish();
    }

    private int getAllowedAuthenticators() {
        if (LineageSettings.Secure.getIntForUser(getContentResolver(),
                AppLockUtils.LINEAGE_SETTINGS_APP_LOCK_BIOMETRICS_ALLOWED, 0,
                getTargetUserId()) == 1) {
            final BiometricManager biometricManager = getSystemService(BiometricManager.class);
            if (biometricManager != null && biometricManager.canAuthenticate(getTargetUserId(),
                    BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
                return DEVICE_CREDENTIAL | BIOMETRIC_STRONG;
            }
        }
        return DEVICE_CREDENTIAL;
    }

    @NonNull
    private CharSequence getTitleText(@NonNull String packageName) {
        return getString(R.string.lineage_app_lock_prompt_title, getAppLabel(packageName));
    }

    @NonNull
    private CharSequence getDescriptionText(@NonNull String packageName) {
        return getString(R.string.lineage_app_lock_prompt_message, getAppLabel(packageName));
    }

    @NonNull
    private CharSequence getAppLabel(@NonNull String packageName) {
        try {
            return mPackageManager.getApplicationLabel(
                    mPackageManager.getApplicationInfoAsUser(packageName,
                            PackageManager.ApplicationInfoFlags.of(0), getTargetUserId()));
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    @Nullable
    private Drawable getBadgedIcon() {
        final String packageName = getProtectedPackageName();
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }

        try {
            final Drawable icon = mPackageManager.getApplicationIcon(
                    mPackageManager.getApplicationInfoAsUser(packageName,
                            PackageManager.ApplicationInfoFlags.of(0), getTargetUserId()));
            return mUserManager.getBadgedIconForUser(icon, UserHandle.of(getTargetUserId()));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private boolean isTaskOverlayChallenge() {
        return getProtectedTaskId() != INVALID_TASK_ID;
    }

    private int getProtectedTaskId() {
        return getIntent().getIntExtra(Intent.EXTRA_TASK_ID, INVALID_TASK_ID);
    }

    @Nullable
    private String getProtectedPackageName() {
        return getIntent().getStringExtra(Intent.EXTRA_PACKAGE_NAME);
    }

    private int getTargetUserId() {
        return getIntent().getIntExtra(Intent.EXTRA_USER_ID, UserHandle.myUserId());
    }

    @Nullable
    private String getChallengeToken() {
        return getIntent().getStringExtra(AppLockUtils.EXTRA_APP_LOCK_CHALLENGE_TOKEN);
    }
}
