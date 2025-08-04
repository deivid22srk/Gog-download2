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
import android.widget.ImageView;
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
    private Button requestStorageButton, requestNotificationButton, requestOverlayButton;
    private ImageView statusStorage, statusNotification, statusOverlay;
    private PermissionHelper permissionHelper;
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<Intent> storagePermissionLauncher;
    private Button continueButton;
    private TextView downloadFolderPath;

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
        permissionHelper = new PermissionHelper(this);

        initializeViews();
        initializeLaunchers();
        setupClickListeners();

        loadExistingPreferences();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Every time the user returns to the screen, update the UI
        updateUI();
    }

    private void initializeLaunchers() {
        overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // After returning from settings, update the UI
                updateUI();
            });

        storagePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // After returning from settings, update the UI
                updateUI();
            });
    }

    private void initializeViews() {
        selectDownloadFolderButton = findViewById(R.id.selectDownloadFolderButton);
        continueButton = findViewById(R.id.continueButton);
        downloadFolderPath = findViewById(R.id.downloadFolderPath);

        requestStorageButton = findViewById(R.id.requestStorageButton);
        requestNotificationButton = findViewById(R.id.requestNotificationButton);
        requestOverlayButton = findViewById(R.id.requestOverlayButton);

        statusStorage = findViewById(R.id.statusStorage);
        statusNotification = findViewById(R.id.statusNotification);
        statusOverlay = findViewById(R.id.statusOverlay);
    }

    private void setupClickListeners() {
        selectDownloadFolderButton.setOnClickListener(v -> openFolderPicker(downloadFolderPickerLauncher));

        continueButton.setOnClickListener(v -> {
            Intent intent = new Intent(FolderSelectionActivity.this, LibraryActivity.class);
            startActivity(intent);
            finish();
        });

        requestStorageButton.setOnClickListener(v -> requestStoragePermission());
        requestNotificationButton.setOnClickListener(v -> requestNotificationPermission());
        requestOverlayButton.setOnClickListener(v -> requestOverlayPermission());
    }

    private void updateUI() {
        // Update permission status icons and button states
        updatePermissionStatus(statusStorage, hasStoragePermission(), requestStorageButton);
        updatePermissionStatus(statusNotification, hasNotificationPermission(), requestNotificationButton);
        updatePermissionStatus(statusOverlay, hasOverlayPermission(), requestOverlayButton);

        // Check if the continue button can be enabled
        checkContinueButtonState();
    }

    private void updatePermissionStatus(ImageView statusView, boolean granted, Button button) {
        if (granted) {
            statusView.setImageResource(android.R.drawable.checkbox_on_background);
            // Using theme color for success state
            statusView.setColorFilter(com.google.android.material.R.attr.colorPrimary);
            button.setEnabled(false);
        } else {
            statusView.setImageResource(R.drawable.ic_error);
            // Using theme color for error state
            statusView.setColorFilter(com.google.android.material.R.attr.colorError);
            button.setEnabled(true);
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    storagePermissionLauncher.launch(intent);
                } catch (Exception e) {
                    Log.e("FolderSelectionActivity", "Failed to launch storage settings", e);
                    Toast.makeText(this, "Falha ao abrir configurações de armazenamento.", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionHelper.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, granted -> updateUI());
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionHelper.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, granted -> updateUI());
            }
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                overlayPermissionLauncher.launch(intent);
            }
        }
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
