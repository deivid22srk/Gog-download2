package com.termux.activities;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.R;
import com.termux.database.DatabaseHelper;
import com.termux.utils.ImageLoader;
import com.termux.utils.PreferencesManager;
import com.termux.utils.SAFDownloadManager;

import java.io.File;

public class SettingsActivity extends BaseActivity {
    
    private TextView appVersionText;
    private TextView safPathText;
    private Button changeSafFolderButton;
    private Button clearCacheButton;
    private SwitchMaterial dynamicColorSwitch;
    private SwitchMaterial materialYouSwitch;
    private SwitchMaterial use1DMSwitch;
    private ChipGroup platformChipGroup;
    private Chip windowsChip;
    private Chip linuxChip;
    private Chip macChip;
    
    private PreferencesManager preferencesManager;
    private DatabaseHelper databaseHelper;
    
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private String selectedPath;
    private boolean isProgrammaticChange = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        android.util.Log.d("SettingsActivity", "=== SETTINGS ACTIVITY CREATED ===");
        
        initializeViews();
        initializeManagers();
        setupToolbar();
        setupActivityLaunchers();
        setupClickListeners();
        loadCurrentSettings();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.d("SettingsActivity", "=== SETTINGS ACTIVITY RESUMED - RELOADING DATA ===");
        // Recarregar dados quando a activity voltar ao foco
        loadCurrentSettings();
    }
    
    private void initializeViews() {
        appVersionText = findViewById(R.id.appVersionText);
        safPathText = findViewById(R.id.safPathText);
        changeSafFolderButton = findViewById(R.id.changeSafFolderButton);
        clearCacheButton = findViewById(R.id.clearCacheButton);
        dynamicColorSwitch = findViewById(R.id.dynamicColorSwitch);
        materialYouSwitch = findViewById(R.id.materialYouSwitch);
        use1DMSwitch = findViewById(R.id.use1DMSwitch);
        platformChipGroup = findViewById(R.id.platformChipGroup);
        windowsChip = findViewById(R.id.windowsChip);
        linuxChip = findViewById(R.id.linuxChip);
        macChip = findViewById(R.id.macChip);
    }
    
    private void initializeManagers() {
        preferencesManager = new PreferencesManager(this);
        databaseHelper = new DatabaseHelper(this);
    }
    
    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }
    
    private void setupActivityLaunchers() {
        // Launcher para seleção de pasta
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            handleSelectedFolder(uri);
                        }
                    }
                });
    }
    
    private void setupClickListeners() {
        changeSafFolderButton.setOnClickListener(v -> openFolderPicker());
        clearCacheButton.setOnClickListener(v -> showClearCacheConfirmation());

        dynamicColorSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isProgrammaticChange) return;
            preferencesManager.setDynamicTheming(isChecked);
            recreate();
        });

        materialYouSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isProgrammaticChange) return;
            preferencesManager.setMaterialYou(isChecked);
            recreate();
        });

        use1DMSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isProgrammaticChange) return;
            preferencesManager.setUse1DM(isChecked);
        });

        windowsChip.setOnCheckedChangeListener((buttonView, isChecked) -> savePlatformPreferences());
        linuxChip.setOnCheckedChangeListener((buttonView, isChecked) -> savePlatformPreferences());
        macChip.setOnCheckedChangeListener((buttonView, isChecked) -> savePlatformPreferences());
    }
    
    private void loadCurrentSettings() {
        android.util.Log.d("SettingsActivity", "=== LOADING CURRENT SETTINGS ===");
        
        // Carregar pasta de download atual com SAF
        SAFDownloadManager safManager = new SAFDownloadManager(this);
        String displayPath = safManager.getDisplayPath();
        
        android.util.Log.d("SettingsActivity", "Download path: '" + displayPath + "'");
        safPathText.setText(displayPath);
        
        // Para o seletor de pasta, manter referência para qualquer path configurado
        String uriPath = preferencesManager.getDownloadUri();
        selectedPath = uriPath;
        
        // Carregar versão do app
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            android.util.Log.d("SettingsActivity", "App version: '" + versionName + "'");
            appVersionText.setText(versionName);
        } catch (Exception e) {
            android.util.Log.e("SettingsActivity", "Failed to get app version", e);
            appVersionText.setText("Desconhecida");
        }

        // Carregar configurações de aparência
        isProgrammaticChange = true;
        dynamicColorSwitch.setChecked(preferencesManager.isDynamicThemingEnabled());
        materialYouSwitch.setChecked(preferencesManager.isMaterialYouEnabled());
        use1DMSwitch.setChecked(preferencesManager.is1DMEnabled());
        isProgrammaticChange = false;

        // Carregar configurações de plataforma
        java.util.Set<String> selectedPlatforms = preferencesManager.getSelectedPlatforms();
        windowsChip.setChecked(selectedPlatforms.contains("windows"));
        linuxChip.setChecked(selectedPlatforms.contains("linux"));
        macChip.setChecked(selectedPlatforms.contains("mac"));
        
        android.util.Log.d("SettingsActivity", "=== SETTINGS LOADING COMPLETE ===");
    }

    private void savePlatformPreferences() {
        java.util.Set<String> selectedPlatforms = new java.util.HashSet<>();
        if (windowsChip.isChecked()) {
            selectedPlatforms.add("windows");
        }
        if (linuxChip.isChecked()) {
            selectedPlatforms.add("linux");
        }
        if (macChip.isChecked()) {
            selectedPlatforms.add("mac");
        }
        preferencesManager.setSelectedPlatforms(selectedPlatforms);
    }
    
    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            Uri initialUri = Uri.fromFile(downloadsDir);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }

        try {
            folderPickerLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Error opening folder picker", Toast.LENGTH_LONG).show();
        }
    }

    private void handleSelectedFolder(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            selectedPath = uri.toString();
            preferencesManager.setDownloadUri(selectedPath);
            updatePathDisplay();

        } catch (Exception e) {
            Toast.makeText(this, "Error setting folder: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updatePathDisplay() {
        SAFDownloadManager safManager = new SAFDownloadManager(this);
        safPathText.setText(safManager.getDisplayPath());
    }
    
    private void showClearCacheConfirmation() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Limpar Cache")
                .setMessage("Isso irá remover todos os dados salvos de jogos e imagens. Os arquivos baixados não serão afetados.")
                .setPositiveButton("Limpar", (dialog, which) -> clearCache())
                .setNegativeButton("Cancelar", null)
                .show();
    }
    
    private void clearCache() {
        try {
            // Limpar cache de imagens
            ImageLoader.getInstance(this).clearCache();
            
            // Limpar banco de dados
            databaseHelper.clearAllGames();
            
            Toast.makeText(this, "Cache limpo com sucesso", Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao limpar cache: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (databaseHelper != null) {
            databaseHelper.close();
        }
    }
}