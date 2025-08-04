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
        if (!hasStoragePermission()) {
            requestStoragePermission();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestNotificationPermission();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlayPermission()) {
            requestOverlayPermission();
        } else {
            // All permissions granted, update button state
            checkContinueButtonState();
        }
    }

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

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                storagePermissionLauncher.launch(intent);
            } catch (Exception e) {
                Log.e("FolderSelectionActivity", "Failed to launch storage settings", e);
                Toast.makeText(this, "Falha ao abrir configurações de armazenamento.", Toast.LENGTH_SHORT).show();
            }
        } else {
            permissionHelper.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, granted -> {
                checkAndRequestPermissions(); // Continue the chain
            });
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionHelper.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, granted -> {
                checkAndRequestPermissions(); // Continue the chain
            });
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayPermissionLauncher.launch(intent);
        }
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
