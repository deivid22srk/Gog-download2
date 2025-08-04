package com.termux.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.utils.PreferencesManager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.utils.PermissionHelper;

public class FolderSelectionActivity extends BaseActivity {

    private Button selectDownloadFolderButton;
    private PermissionHelper permissionHelper;
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<Intent> storagePermissionLauncher;
    private Button continueButton;
    private TextView downloadFolderPath;
    private TextView installFolderPath;

    private PreferencesManager preferencesManager;

    private Uri downloadFolderUri;

    private final ActivityResultLauncher<Intent> downloadFolderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        downloadFolderUri = uri;
                        preferencesManager.setDownloadUri(uri.toString());
                        downloadFolderPath.setText(uri.getPath());
                        checkContinueButtonState();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_selection);

        preferencesManager = new PreferencesManager(this);

        selectDownloadFolderButton = findViewById(R.id.selectDownloadFolderButton);
        continueButton = findViewById(R.id.continueButton);
        downloadFolderPath = findViewById(R.id.downloadFolderPath);

        selectDownloadFolderButton.setOnClickListener(v -> openFolderPicker(downloadFolderPickerLauncher));

        continueButton.setOnClickListener(v -> {
            Intent intent = new Intent(FolderSelectionActivity.this, LibraryActivity.class);
            startActivity(intent);
            finish();
        });

        permissionHelper = new PermissionHelper(this);
        initializeLaunchers();
        loadExistingPreferences();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Every time the user returns to the screen, check if permissions are still granted
        checkAndRequestPermissions();
    }

    private void initializeLaunchers() {
        overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // After returning from settings, continue the permission check chain
                checkAndRequestPermissions();
            });

        storagePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // After returning from settings, continue the permission check chain
                checkAndRequestPermissions();
            });
    }

    private void checkAndRequestPermissions() {
        // Sequentially check for permissions.

        // 1. Standard Permissions (can be grouped)
        java.util.List<String> standardPermsNeeded = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                standardPermsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                standardPermsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!standardPermsNeeded.isEmpty()) {
            Log.d("FolderSelectionActivity", "Requesting standard permissions...");
            permissionHelper.requestPermissions(standardPermsNeeded.toArray(new String[0]), granted -> {
                // After the user responds, re-check the whole chain.
                checkAndRequestPermissions();
            });
            return; // Stop here and wait for the callback.
        }

        // 2. Special Permission: All Files Access (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.d("FolderSelectionActivity", "Requesting All Files Access...");
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    storagePermissionLauncher.launch(intent);
                } catch (Exception e) {
                    Log.e("FolderSelectionActivity", "Failed to launch storage settings", e);
                    Toast.makeText(this, "Falha ao abrir configurações de armazenamento.", Toast.LENGTH_SHORT).show();
                }
                return; // Stop here and wait for the user to return from settings.
            }
        }

        // 3. Special Permission: Draw Over Other Apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d("FolderSelectionActivity", "Requesting Overlay permission...");
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                overlayPermissionLauncher.launch(intent);
                return; // Stop here and wait.
            }
        }

        // If we reach here, all permissions have been granted.
        Log.d("FolderSelectionActivity", "All permissions granted.");
        checkContinueButtonState();
    }

    // Helper methods to check permissions state for the continue button
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void openFolderPicker(ActivityResultLauncher<Intent> launcher) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        launcher.launch(intent);
    }

    private void loadExistingPreferences() {
        String downloadUriString = preferencesManager.getDownloadUri();
        if (downloadUriString != null) {
            downloadFolderUri = Uri.parse(downloadUriString);
            downloadFolderPath.setText(downloadFolderUri.getPath());
        }


        checkContinueButtonState();
    }

    private void checkContinueButtonState() {
        boolean allPermissionsGranted = hasStoragePermission() && hasNotificationPermission() && hasOverlayPermission();
        continueButton.setEnabled(downloadFolderUri != null && allPermissionsGranted);
    }
}
