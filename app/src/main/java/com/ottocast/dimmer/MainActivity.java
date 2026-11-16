package com.ottocast.dimmer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

/**
 * Translucent entry activity: requests overlay permission if missing,
 * otherwise starts DimmerOverlayService and immediately finishes without showing a screen.
 */
public class MainActivity extends Activity {

    private boolean awaitingOverlayPermission = false;
    private AlertDialog permissionDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Settings.canDrawOverlays(this)) {
            launchServiceAndFinish();
        } else {
            showOverlayPermissionDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Settings.canDrawOverlays(this)) {
            dismissDialogSafely();
            launchServiceAndFinish();
        } else if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false;
            showOverlayPermissionDialog();
        }
    }

    @Override
    protected void onDestroy() {
        dismissDialogSafely();
        super.onDestroy();
    }

    private void launchServiceAndFinish() {
        Intent serviceIntent = new Intent(this, DimmerOverlayService.class);
        serviceIntent.setAction(DimmerOverlayService.ACTION_SHOW_OR_START);
        startForegroundService(serviceIntent);
        finish();
        overridePendingTransition(0, 0);
    }

    private void showOverlayPermissionDialog() {
        if (permissionDialog != null && permissionDialog.isShowing()) {
            return;
        }
        permissionDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("다른 앱 위에 표시 권한 필요")
                .setMessage("야간 화면 밝기 조절, 블루라이트 차단, 상단 시계 표시를 위해 '다른 앱 위에 표시' 권한을 허용해 주세요.")
                .setPositiveButton("권한 설정", (dialog, which) -> openOverlayPermissionSettings())
                .setNegativeButton("취소", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void openOverlayPermissionSettings() {
        awaitingOverlayPermission = true;
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (ActivityNotFoundException ignored2) {
                Intent details = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(details);
            }
        }
    }

    private void dismissDialogSafely() {
        if (permissionDialog != null && permissionDialog.isShowing()) {
            permissionDialog.dismiss();
        }
        permissionDialog = null;
    }
}
