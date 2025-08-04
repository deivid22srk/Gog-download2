package com.termux.activities;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.DialogInterface;
import androidx.appcompat.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.adapters.DownloadLinkAdapter;
import com.termux.adapters.GamesAdapter;
import com.termux.api.GOGAuthManager;
import com.termux.api.GOGLibraryManager;
import com.termux.database.DatabaseHelper;
import com.termux.models.DownloadLink;
import com.termux.models.Game;
import com.termux.services.DownloadService;
import com.termux.utils.DynamicColorTester;
import com.termux.utils.ImageLoader;
import com.termux.utils.PermissionHelper;
import com.termux.utils.PreferencesManager;
import com.termux.utils.SAFDownloadManager;
import com.termux.dialogs.FolderPickerDialogFragment;
import com.termux.services.InstallationService;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.net.Uri;
import android.widget.ProgressBar;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LibraryActivity extends BaseActivity implements GamesAdapter.OnGameActionListener, FolderPickerDialogFragment.FolderPickerListener {
    
    private static final int SETTINGS_REQUEST_CODE = 100;
    private String sourceFolderPath;
    private String destinationFolderPath;
    
    private RecyclerView gamesRecyclerView;
    private GamesAdapter gamesAdapter;
    private LinearLayout loadingLayout;
    private LinearLayout emptyLayout;
    private TextView userNameText;
    private TextView gameCountText;
    private Button refreshButton;
    private Button retryButton;
    private TextInputEditText searchEditText;
    private FloatingActionButton installFab;
    
    private GOGLibraryManager libraryManager;
    private PreferencesManager preferencesManager;
    private DatabaseHelper databaseHelper;
    private PermissionHelper permissionHelper;
    private SAFDownloadManager safDownloadManager;
    
    // Launcher para seleção de pasta de download
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ActivityResultLauncher<Intent> installerFolderPickerLauncher;
    private ActivityResultLauncher<Intent> sourceFilePickerLauncher;
    private Game pendingDownloadGame; // Jogo aguardando seleção de pasta
    private DownloadLink pendingDownloadLink; // Para compatibilidade com código antigo
    private List<DownloadLink> pendingSelectedLinks; // Para múltiplos downloads
    private BroadcastReceiver downloadProgressReceiver;
    private BroadcastReceiver installProgressReceiver;

    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        
        initializeManagers(); // Initialize managers first to access preferences

        // Check if folders are selected
        if (!preferencesManager.hasDownloadLocationConfigured() || preferencesManager.getInstallUri() == null) {
            Intent intent = new Intent(this, FolderSelectionActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        setupFolderPickerLauncher();
        initializeViews();
        setupToolbar();
        setupRecyclerView();
        setupClickListeners();
        checkPermissions();
        loadLibrary();
        
        // Test Dynamic Color implementation with Material 1.10
        testDynamicColorCompatibility();

        downloadProgressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long gameId = intent.getLongExtra(DownloadService.EXTRA_GAME_ID, -1);
                if (gameId == -1) return;

                String statusStr = intent.getStringExtra(DownloadService.EXTRA_DOWNLOAD_STATUS);
                if (statusStr != null) {
                    // Handle status-only updates (like PAUSED)
                    Game.DownloadStatus status = Game.DownloadStatus.valueOf(statusStr);
                    gamesAdapter.updateGameStatus(gameId, status);
                } else {
                    // Handle regular progress updates
                    gamesAdapter.updateGameProgress(
                        gameId,
                        intent.getLongExtra(DownloadService.EXTRA_BYTES_DOWNLOADED, 0),
                        intent.getLongExtra(DownloadService.EXTRA_TOTAL_BYTES, 0),
                        intent.getFloatExtra(DownloadService.EXTRA_DOWNLOAD_SPEED, 0.0f),
                        intent.getLongExtra(DownloadService.EXTRA_ETA, 0),
                        intent.getIntExtra(DownloadService.EXTRA_CURRENT_FILE_INDEX, 0),
                        intent.getIntExtra(DownloadService.EXTRA_TOTAL_FILES, 0)
                    );
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadProgressReceiver, new IntentFilter(DownloadService.ACTION_DOWNLOAD_PROGRESS));
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadProgressReceiver);
    }
    
    private void setupFolderPickerLauncher() {
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        android.net.Uri uri = result.getData().getData();
                        if (uri != null) {
                            handleSelectedFolder(uri);
                        }
                    } else {
                        // Usuário cancelou seleção
                        pendingDownloadGame = null;
                        Toast.makeText(this, "Seleção de pasta cancelada", Toast.LENGTH_SHORT).show();
                    }
                });
    }


    
    private void handleSelectedFolder(android.net.Uri uri) {
        try {
            // Dar permissão persistente para a URI
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            
            // Salvar URI nas preferências
            preferencesManager.setDownloadUri(uri.toString());
            
            Log.d("LibraryActivity", "Pasta selecionada e salva: " + uri.toString());
            Toast.makeText(this, "Pasta de download configurada!", Toast.LENGTH_SHORT).show();
            
            // Se havia um download pendente, iniciar agora
            if (pendingDownloadGame != null) {
                Game gameToDownload = pendingDownloadGame;
                
                if (pendingSelectedLinks != null && !pendingSelectedLinks.isEmpty()) {
                    // Múltiplos downloads
                    List<DownloadLink> linksToDownload = pendingSelectedLinks;
                    pendingDownloadGame = null;
                    pendingSelectedLinks = null;
                    
                    // Converter List para Set
                    HashSet<DownloadLink> downloadSet = new HashSet<>(linksToDownload);
                    startMultipleDownloads(gameToDownload, downloadSet);
                } else if (pendingDownloadLink != null) {
                    // Download único (compatibilidade)
                    DownloadLink linkToDownload = pendingDownloadLink;
                    pendingDownloadGame = null;
                    pendingDownloadLink = null;
                    startGameDownload(gameToDownload, linkToDownload);
                }
            }
            
        } catch (Exception e) {
            Log.e("LibraryActivity", "Erro ao processar pasta selecionada", e);
            Toast.makeText(this, "Erro ao configurar pasta de download", Toast.LENGTH_SHORT).show();
            pendingDownloadGame = null;
            pendingDownloadLink = null;
            pendingSelectedLinks = null;
        }
    }
    
    private void initializeViews() {
        gamesRecyclerView = findViewById(R.id.gamesRecyclerView);
        loadingLayout = findViewById(R.id.loadingLayout);
        emptyLayout = findViewById(R.id.emptyLayout);
        userNameText = findViewById(R.id.userNameText);
        gameCountText = findViewById(R.id.gameCountText);
        refreshButton = findViewById(R.id.refreshButton);
        retryButton = findViewById(R.id.retryButton);
        searchEditText = findViewById(R.id.searchEditText);
        installFab = findViewById(R.id.installFab);
    }
    
    private void initializeManagers() {
        preferencesManager = new PreferencesManager(this);
        databaseHelper = new DatabaseHelper(this);
        libraryManager = new GOGLibraryManager(this);
        permissionHelper = new PermissionHelper(this);
        safDownloadManager = new SAFDownloadManager(this);
    }
    
    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
    }
    
    private void setupRecyclerView() {
        gamesAdapter = new GamesAdapter(this);
        gamesAdapter.setOnGameActionListener(this);
        
        gamesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        gamesRecyclerView.setAdapter(gamesAdapter);
    }
    
    private void setupClickListeners() {
        refreshButton.setOnClickListener(v -> refreshLibrary());
        retryButton.setOnClickListener(v -> loadLibrary());
        installFab.setOnClickListener(v -> {
            android.content.SharedPreferences prefs = getSharedPreferences("TermuxPrefs", MODE_PRIVATE);
            boolean isFirstRun = prefs.getBoolean("isFirstFabClick", true);

            if (isFirstRun) {
                // First click: launch Termux and set flag
                prefs.edit().putBoolean("isFirstFabClick", false).apply();

                Toast.makeText(this, "Iniciando o Termux para configuração inicial. Por favor, aguarde e retorne ao app.", Toast.LENGTH_LONG).show();
                Intent intent = new Intent();
                intent.setComponent(new ComponentName("com.termux", "com.termux.app.TermuxActivity"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                    new android.os.Handler().postDelayed(() -> {
                        Intent returnIntent = new Intent(LibraryActivity.this, LibraryActivity.class);
                        returnIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(returnIntent);
                    }, 5000);
                } catch (Exception e) {
                    Toast.makeText(this, "Não foi possível abrir o Termux. Verifique se está instalado.", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Subsequent clicks: run normal installation flow
                showFolderPickerForSource();
            }
        });

        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                        showFolderPickerForSource();
                    } else {
                        Toast.makeText(this, "A permissão de sobreposição é necessária para a instalação.", Toast.LENGTH_SHORT).show();
                    }
                });
        
        // Configurar SearchEditText
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Não é necessário
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                gamesAdapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Não é necessário
            }
        });
    }

    private String getTermuxHomePath() {
        return "/data/data/com.termux/files/home";
    }

    private boolean ensureTermuxIsReady() {
        try {
            String termuxHomeDir = getTermuxHomePath();
            File termuxConfigDir = new File(termuxHomeDir, ".termux");
            if (!termuxConfigDir.exists()) {
                if (!termuxConfigDir.mkdirs()) {
                    Log.e("LibraryActivity", "Failed to create .termux directory");
                    Toast.makeText(this, "Erro: Não foi possível criar diretório de configuração do Termux.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            File propertiesFile = new File(termuxConfigDir, "termux.properties");
            if (!propertiesFile.exists()) {
                try (FileWriter writer = new FileWriter(propertiesFile)) {
                    writer.write("allow-external-apps=true\n");
                }
            } else {
                String content = readFileContent(propertiesFile);
                if (!content.contains("allow-external-apps=true")) {
                    try (FileWriter writer = new FileWriter(propertiesFile, true)) {
                        writer.write("\nallow-external-apps=true\n");
                    }
                }
            }
            return true;
        } catch (IOException e) {
            Log.e("LibraryActivity", "Failed to configure Termux", e);
            Toast.makeText(this, "Erro ao configurar Termux: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean checkTermuxInstallation() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo("com.termux", 0);
            
            // A verificação do diretório home do Termux foi removida porque
            // não é confiável em versões modernas do Android devido a restrições de armazenamento.
            
            // Verificar se o bash existe
            String bashPath = getTermuxBashPath();
            if (bashPath == null) {
                showTermuxBashNotFoundDialog();
                return false;
            }
            
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            showTermuxNotInstalledDialog();
            return false;
        }
    }

    private String getTermuxBashPath() {
        // Lista de possíveis caminhos para o bash do Termux
        String[] possiblePaths = {
            "/data/data/com.termux/files/usr/bin/bash",
            "/data/data/com.termux/files/usr/bin/sh",
            "/system/bin/sh"
        };
        
        for (String path : possiblePaths) {
            File bashFile = new File(path);
            if (bashFile.exists() && bashFile.canExecute()) {
                Log.d("LibraryActivity", "Found bash at: " + path);
                return path;
            }
        }
        
        Log.e("LibraryActivity", "No valid bash found in any of the expected paths");
        return null;
    }

    private String readFileContent(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    private void showTermuxNotInstalledDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Termux não encontrado")
            .setMessage("O Termux não está instalado. É necessário instalar o Termux para usar a funcionalidade de instalação automática.\n\nDeseja baixar o Termux?")
            .setPositiveButton("Baixar", (dialog, which) -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Erro ao abrir link do Termux", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void showTermuxNotConfiguredDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Termux não configurado")
            .setMessage("O Termux está instalado, mas não foi configurado corretamente. Abra o Termux pelo menos uma vez para inicializar o ambiente.")
            .setPositiveButton("Abrir Termux", (dialog, which) -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("com.termux");
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "Não foi possível abrir o Termux", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Erro ao abrir Termux", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void showTermuxBashNotFoundDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Bash do Termux não encontrado")
            .setMessage("O executável bash do Termux não foi encontrado. Isso pode acontecer se o Termux não foi inicializado corretamente.\n\nTente:\n1. Abrir o Termux\n2. Aguardar a inicialização\n3. Tentar novamente")
            .setPositiveButton("Abrir Termux", (dialog, which) -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("com.termux");
                    if (intent != null) {
                        startActivity(intent);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Erro ao abrir Termux", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void showTermuxTroubleshootingDialog() {
        new MaterialAlertDialogBuilder(this)
            .setTitle("🔧 Problema com o Termux")
            .setMessage("🚀 **Termux não está configurado adequadamente**\n\n" +
                    "💡 **Soluções:**\n" +
                    "1. ✅ Abra o Termux e aguarde a inicialização\n" +
                    "2. 🔄 Execute: `pkg update && pkg upgrade`\n" +
                    "3. 📦 Teste: `pkg install innoextract`\n" +
                    "4. 🔁 Reinicie o dispositivo se necessário\n\n" +
                    "⚠️ **Nota:** O Termux precisa ser inicializado pelo menos uma vez para funcionar corretamente.")
            .setPositiveButton("🚀 Abrir Termux", (dialog, which) -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("com.termux");
                    if (intent != null) {
                        startActivity(intent);
                        Toast.makeText(this, "💡 Aguarde a inicialização e tente novamente!", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Não foi possível abrir o Termux", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Erro ao abrir Termux: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            })
            .setNeutralButton("📱 Baixar Termux", (dialog, which) -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Erro ao abrir link do Termux", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void showTermuxExecutionErrorDialog(String errorMessage) {
        new MaterialAlertDialogBuilder(this)
            .setTitle("❌ Erro na Execução")
            .setMessage("🚀 **Não foi possível executar o script de instalação**\n\n" +
                    "📝 **Erro:** " + errorMessage + "\n\n" +
                    "💡 **Soluções:**\n" +
                    "1. ✅ Verifique se o Termux está atualizado\n" +
                    "2. 🔄 Reinicie o Termux\n" +
                    "3. 🛡️ Verifique permissões do app\n" +
                    "4. 📱 Reinstale o Termux se necessário")
            .setPositiveButton("🚀 Tentar Novamente", (dialog, which) -> {
                // Usuário pode tentar novamente
                Toast.makeText(this, "💡 Certifique-se de que o Termux está funcionando primeiro!", Toast.LENGTH_LONG).show();
            })
            .setNeutralButton("🚀 Abrir Termux", (dialog, which) -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("com.termux");
                    if (intent != null) {
                        startActivity(intent);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Erro ao abrir Termux", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void installGameWithInnoextract(String sourceDir, String destDir) {
        if (!ensureTermuxIsReady()) {
            Toast.makeText(this, "Não foi possível configurar o Termux. A instalação pode falhar.", Toast.LENGTH_LONG).show();
            // Continuar mesmo assim, pois o usuário pode ter configurado manualmente.
        }

        String gameInstallScript = createGameInstallScript(sourceDir, destDir);
        String termuxScriptPath = saveScriptToAppExternalDir(gameInstallScript);
        if (termuxScriptPath != null) {
            executeTermuxCommand("bash " + termuxScriptPath);
        }
    }
    
    private String createGameInstallScript(String sourceDir, String destDir) {
        return "#!/data/data/com.termux/files/usr/bin/bash\n" +
                "echo \"🎮 Iniciando instalação do jogo...\"\n" +
                "echo \"Script executado em: $(date)\"\n" +
                "echo \"\"\n" +
                "echo \"🔄 Verificando dependências (innoextract)...\"\n" +
                "if ! command -v innoextract &>/dev/null; then\n" +
                "    echo \"📦 innoextract não encontrado. Tentando instalar via pkg...\"\n" +
                "    pkg update -y && pkg install -y innoextract\n" +
                "    if ! command -v innoextract &>/dev/null; then\n" +
                "        echo \"\"\n" +
                "        echo \"❌ Falha ao instalar o innoextract. Abortando.\"\n" +
                "        echo \"💡 Tente instalar manualmente: execute 'pkg install innoextract' no Termux.\"\n" +
                "        read -p \"Pressione Enter para sair...\"\n" +
                "        exit 1\n" +
                "    fi\n" +
                "fi\n" +
                "echo \"✅ innoextract está pronto.\"\n" +
                "\n" +
                "GOG_DIR=\"" + sourceDir + "\"\n" +
                "DEST_DIR=\"" + destDir + "\"\n" +
                "\n" +
                "# Verificar pasta de origem\n" +
                "if [ ! -d \"$GOG_DIR\" ]; then\n" +
                "    echo \"❌ Erro: pasta de origem '$GOG_DIR' não encontrada.\"\n" +
                "    read -p \"Pressione Enter para sair...\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "\n" +
                "cd \"$GOG_DIR\" || { echo \"❌ Erro: não foi possível acessar '$GOG_DIR'.\"; read -p \"Pressione Enter para sair...\"; exit 1; }\n" +
                "\n" +
                "# Procurar arquivo setup\n" +
                "echo \"🔍 Procurando instalador .exe em '$GOG_DIR'...\"\n" +
                "SETUP_EXE=$(ls setup_*.exe 2>/dev/null | head -n 1)\n" +
                "if [ ! -f \"$SETUP_EXE\" ]; then\n" +
                "    echo \"❌ Erro: Nenhum instalador 'setup_*.exe' encontrado na pasta.\"\n" +
                "    echo \"📁 Arquivos na pasta:\"\n" +
                "    ls -la\n" +
                "    read -p \"Pressione Enter para sair...\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "echo \"✅ Instalador encontrado: $SETUP_EXE\"\n" +
                "\n" +
                "# Criar pasta de destino se não existir\n" +
                "mkdir -p \"$DEST_DIR\"\n" +
                "\n" +
                "echo \"📂 Extraindo $SETUP_EXE para $DEST_DIR...\"\n" +
                "innoextract --output-dir=\"$DEST_DIR\" \"$SETUP_EXE\"\n" +
                "\n" +
                "if [ $? -eq 0 ]; then\n" +
                "    echo \"🎉 Jogo instalado com sucesso!\"\n" +
                "    echo \"📍 Local: $DEST_DIR\"\n" +
                "    echo \"\"\n" +
                "    echo \"📁 Arquivos extraídos:\"\n" +
                "    ls -la \"$DEST_DIR\"\n" +
                "else\n" +
                "    echo \"❌ Ocorreu um erro durante a extração.\"\n" +
                "    echo \"💡 Verifique se o arquivo é um instalador GOG válido e se há espaço suficiente.\"\n" +
                "    read -p \"Pressione Enter para sair...\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "read -p \"Pressione Enter para fechar esta janela...\"\n";
    }
    
    private String saveScriptToAppExternalDir(String scriptContent) {
        try {
            File externalDir = getExternalFilesDir(null);
            if (externalDir == null) {
                Toast.makeText(this, "❌ Não foi possível acessar o armazenamento externo.", Toast.LENGTH_LONG).show();
                return null;
            }
            
            File scriptFile = new File(externalDir, "gog_install.sh");
            FileOutputStream fos = new FileOutputStream(scriptFile);
            fos.write(scriptContent.getBytes());
            fos.close();
            
            // Set executable for owner, group, and others.
            scriptFile.setExecutable(true, false);
            
            Log.d("LibraryActivity", "Script saved to: " + scriptFile.getAbsolutePath());
            return scriptFile.getAbsolutePath();
            
        } catch (IOException e) {
            Log.e("LibraryActivity", "Error saving script to app external dir", e);
            Toast.makeText(this, "❌ Erro ao criar script de instalação.", Toast.LENGTH_LONG).show();
            return null;
        }
    }
    
    private void executeTermuxCommand(String command) {
        if (!checkTermuxInstallation()) {
            return;
        }

        String bashPath = getTermuxBashPath();
        if (bashPath == null) {
            showTermuxTroubleshootingDialog();
            return;
        }

        Intent intent = new Intent();
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.setAction("com.termux.RUN_COMMAND");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", bashPath);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", command});
        File externalDir = getExternalFilesDir(null);
        String workDir = externalDir != null ? externalDir.getAbsolutePath() : "/sdcard";
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);

        try {
            startService(intent);
            Toast.makeText(this, "Iniciando instalação via Termux... Verifique a notificação.", Toast.LENGTH_LONG).show();
            Log.d("LibraryActivity", "Started Termux service with command: " + command);
        } catch (Exception e) {
            Log.e("LibraryActivity", "Failed to start Termux service", e);
            Toast.makeText(this, "Erro ao iniciar o serviço do Termux: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            }
        } else {
            permissionHelper.requestStoragePermissions(granted -> {
                if (!granted) {
                    showError("A permissão de armazenamento é necessária para acessar os arquivos de instalação.");
                }
            });
        }

        permissionHelper.requestNotificationPermissions(granted -> {
            if (!granted) {
                showError(getString(R.string.permission_notification));
            }
        });
    }
    
    private void loadLibrary() {
        showLoading(true);
        
        // Primeiro, tentar carregar do cache local
        List<Game> cachedGames = databaseHelper.getAllGames();
        if (!cachedGames.isEmpty()) {
            displayGames(cachedGames);
            showLoading(false);
            
            // Atualizar informações do usuário
            updateUserInfo();
            
            // Atualizar em background
            refreshLibraryInBackground();
        } else {
            // Se não há cache, carregar diretamente da API
            loadLibraryFromAPI();
        }
    }
    
    private void refreshLibrary() {
        refreshButton.setEnabled(false);
        loadLibraryFromAPI();
    }
    
    private void refreshLibraryInBackground() {
        libraryManager.loadUserLibrary(new GOGLibraryManager.LibraryCallback() {
            @Override
            public void onSuccess(List<Game> games) {
                runOnUiThread(() -> {
                    // Atualizar cache
                    databaseHelper.insertOrUpdateGames(games);
                    // Atualizar UI apenas se houve mudanças
                    displayGames(games);
                });
            }
            
            @Override
            public void onError(String error) {
                // Falha silenciosa em background
            }
        });
    }
    
    private void loadLibraryFromAPI() {
        showLoading(true);
        
        libraryManager.loadUserLibrary(new GOGLibraryManager.LibraryCallback() {
            @Override
            public void onSuccess(List<Game> games) {
                runOnUiThread(() -> {
                    showLoading(false);
                    refreshButton.setEnabled(true);
                    
                    // Salvar no cache
                    databaseHelper.insertOrUpdateGames(games);
                    
                    // Exibir jogos
                    displayGames(games);
                    
                    // Atualizar informações do usuário
                    updateUserInfo();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    showLoading(false);
                    refreshButton.setEnabled(true);
                    showError(error);
                    
                    // Se há cache, mostrar dados em cache
                    List<Game> cachedGames = databaseHelper.getAllGames();
                    if (!cachedGames.isEmpty()) {
                        displayGames(cachedGames);
                        updateUserInfo();
                    } else {
                        showEmpty();
                    }
                });
            }
        });
    }
    
    private void displayGames(List<Game> games) {
        if (games.isEmpty()) {
            showEmpty();
        } else {
            gamesAdapter.setGames(games);
            showContent();
            updateGameCount(games.size());
        }
    }
    
    private void updateUserInfo() {
        String displayName = preferencesManager.getDisplayName();
        
        Log.d("LibraryActivity", "=== UPDATE USER INFO ===");
        Log.d("LibraryActivity", "Current display name: '" + displayName + "'");
        Log.d("LibraryActivity", "User email: '" + preferencesManager.getUserEmail() + "'");
        Log.d("LibraryActivity", "User name: '" + preferencesManager.getUserName() + "'");
        Log.d("LibraryActivity", "User ID: '" + preferencesManager.getUserId() + "'");
        
        // Sempre tentar carregar dados do usuário se não temos informações
        boolean shouldReloadUserInfo = displayName == null || 
                                      displayName.isEmpty() || 
                                      displayName.equals("Usuário GOG") ||
                                      preferencesManager.getUserEmail() == null ||
                                      preferencesManager.getUserEmail().isEmpty();
        
        if (shouldReloadUserInfo) {
            Log.d("LibraryActivity", "Need to reload user info, current data is insufficient");
            reloadUserInfo();
        } else {
            Log.d("LibraryActivity", "Using existing user data");
            setUserDisplayInfo(displayName);
        }
    }
    
    /**
     * Define as informações de exibição do usuário na UI
     */
    private void setUserDisplayInfo(String displayName) {
        if (userNameText != null) {
            userNameText.setText(displayName);
            Log.d("LibraryActivity", "Set userNameText to: '" + displayName + "'");
            
            // Carregar avatar se disponível
            String avatarUrl = preferencesManager.getUserAvatar();
            loadUserAvatar(avatarUrl);
        } else {
            Log.e("LibraryActivity", "userNameText is null!");
        }
    }
    
    /**
     * Carrega o avatar do usuário
     */
    private void loadUserAvatar(String avatarUrl) {
        ImageView userAvatar = findViewById(R.id.userAvatar);
        if (userAvatar != null && avatarUrl != null && !avatarUrl.isEmpty()) {
            Log.d("LibraryActivity", "Loading user avatar: " + avatarUrl);
            ImageLoader.loadImage(this, avatarUrl, userAvatar);
        } else {
            Log.d("LibraryActivity", "No avatar to load or userAvatar view not found");
            if (userAvatar != null) {
                userAvatar.setImageResource(android.R.drawable.ic_menu_myplaces);
            }
        }
    }
    
    private void reloadUserInfo() {
        String authToken = preferencesManager.getAuthToken();
        Log.d("LibraryActivity", "=== RELOADING USER INFO ===");
        Log.d("LibraryActivity", "Auth token available: " + (authToken != null && !authToken.isEmpty()));
        
        if (authToken != null && !authToken.isEmpty()) {
            GOGAuthManager authManager = new GOGAuthManager(this);
            
            Log.d("LibraryActivity", "Calling getUserData...");
            
            // Usar o novo método getUserData para obter informações completas do usuário
            authManager.getUserData(authToken, new GOGAuthManager.UserInfoCallback() {
                @Override
                public void onSuccess(JSONObject userData) {
                    Log.d("LibraryActivity", "=== USER DATA SUCCESS ===");
                    runOnUiThread(() -> {
                        try {
                            processUserData(userData, authToken);
                            
                        } catch (Exception e) {
                            Log.e("LibraryActivity", "=== ERROR PROCESSING USER DATA ===", e);
                            setFallbackUserInfo();
                        }
                    });
                }
                
                @Override
                public void onError(String error) {
                    Log.e("LibraryActivity", "=== USER DATA ERROR: " + error + " ===");
                    runOnUiThread(() -> {
                        setFallbackUserInfo();
                    });
                }
            });
        } else {
            Log.w("LibraryActivity", "No auth token available!");
            setFallbackUserInfo();
        }
    }
    
    /**
     * Processa os dados do usuário recebidos da API
     */
    private void processUserData(JSONObject userData, String authToken) {
        // Extrair informações do userData
        String email = userData.optString("email", "");
        String username = userData.optString("username", "");
        String userId = userData.optString("userId", userData.optString("user_id", ""));
        String avatar = userData.optString("avatar", "");
        String firstName = userData.optString("first_name", "");
        String lastName = userData.optString("last_name", "");
        
        Log.d("LibraryActivity", "=== EXTRACTED DATA ===");
        Log.d("LibraryActivity", "Email: '" + email + "'");
        Log.d("LibraryActivity", "Username: '" + username + "'");
        Log.d("LibraryActivity", "First name: '" + firstName + "'");
        Log.d("LibraryActivity", "Last name: '" + lastName + "'");
        Log.d("LibraryActivity", "Avatar: '" + avatar + "'");
        
        // Criar nome de exibição
        String displayName = createDisplayName(firstName, lastName, username, email);
        
        Log.d("LibraryActivity", "=== FINAL DISPLAY NAME: '" + displayName + "' ===");
        
        // Salvar informações atualizadas
        Log.d("LibraryActivity", "Saving auth data...");
        preferencesManager.saveAuthData(authToken, preferencesManager.getRefreshToken(), 
                                       email, displayName, userId, avatar);
        
        // Atualizar UI
        setUserDisplayInfo(displayName);
        
        Log.d("LibraryActivity", "=== USER INFO UPDATE COMPLETE ===");
    }
    
    /**
     * Cria o nome de exibição baseado nos dados disponíveis
     */
    private String createDisplayName(String firstName, String lastName, String username, String email) {
        String displayName = "";
        
        // Tentar usar nome completo primeiro
        if (!firstName.isEmpty() || !lastName.isEmpty()) {
            displayName = (firstName + " " + lastName).trim();
            Log.d("LibraryActivity", "Using full name: '" + displayName + "'");
        }
        
        // Se não tem nome, usar username
        if (displayName.isEmpty() && !username.isEmpty()) {
            displayName = username;
            Log.d("LibraryActivity", "Using username: '" + displayName + "'");
        }
        
        // Se não tem username, usar parte do email
        if (displayName.isEmpty() && !email.isEmpty()) {
            displayName = email.split("@")[0];
            Log.d("LibraryActivity", "Using email prefix: '" + displayName + "'");
        }
        
        // Fallback final
        if (displayName.isEmpty()) {
            displayName = "Usuário GOG";
            Log.d("LibraryActivity", "Using fallback: '" + displayName + "'");
        }
        
        return displayName;
    }
    
    /**
     * Define informações de fallback quando não consegue carregar dados do usuário
     */
    private void setFallbackUserInfo() {
        if (userNameText != null) {
            userNameText.setText("Usuário GOG");
            Log.d("LibraryActivity", "Set fallback user info");
        }
        
        // Definir avatar padrão
        ImageView userAvatar = findViewById(R.id.userAvatar);
        if (userAvatar != null) {
            userAvatar.setImageResource(android.R.drawable.ic_menu_myplaces);
        }
    }
    
    private void updateGameCount(int count) {
        String gameCountStr = getResources().getQuantityString(
                R.plurals.game_count, count, count);
        gameCountText.setText(gameCountStr);
    }
    
    private void showLoading(boolean show) {
        if (show) {
            loadingLayout.setVisibility(View.VISIBLE);
            gamesRecyclerView.setVisibility(View.GONE);
            emptyLayout.setVisibility(View.GONE);
        } else {
            loadingLayout.setVisibility(View.GONE);
        }
    }
    
    private void showContent() {
        loadingLayout.setVisibility(View.GONE);
        gamesRecyclerView.setVisibility(View.VISIBLE);
        emptyLayout.setVisibility(View.GONE);
    }
    
    private void showEmpty() {
        loadingLayout.setVisibility(View.GONE);
        gamesRecyclerView.setVisibility(View.GONE);
        emptyLayout.setVisibility(View.VISIBLE);
    }
    
    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivityForResult(intent, SETTINGS_REQUEST_CODE);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == SETTINGS_REQUEST_CODE && resultCode == RESULT_OK) {
            // Configurações foram alteradas, pode precisar recarregar
            // Por exemplo, se a pasta de download mudou
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_library, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.action_settings) {
            openSettings();
            return true;
        } else if (id == R.id.action_logout) {
            showLogoutDialog();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
    
    private void showLogoutDialog() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Logout")
                .setMessage("Tem certeza que deseja sair?")
                .setPositiveButton("Sim", (dialog, which) -> logout())
                .setNegativeButton("Cancelar", null)
                .show();
    }
    
    private void logout() {
        // Limpar dados de autenticação
        preferencesManager.clearAuthData();
        
        // Voltar para tela de login
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
    
    // Implementações dos callbacks do adapter
    @Override
    public void onDownloadGame(Game game) {
        Log.d("LibraryActivity", "Download requested for: " + game.getTitle());

        // Show a loading dialog while we fetch the download links
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle("Fetching Download Links");
        builder.setMessage("Please wait...");
        builder.setCancelable(false);
        AlertDialog loadingDialog = builder.create();
        loadingDialog.show();

        libraryManager.loadGameDetails(game.getId(), new GOGLibraryManager.GameDetailsCallback() {
            @Override
            public void onSuccess(Game detailedGame, List<DownloadLink> downloadLinks) {
                runOnUiThread(() -> {
                    loadingDialog.dismiss();
                    if (downloadLinks.isEmpty()) {
                        showError("No download links found for this game.");
                        return;
                    }
                    showDownloadSelectionDialog(detailedGame, downloadLinks);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    loadingDialog.dismiss();
                    showError("Error fetching download links: " + error);
                });
            }
        });
    }

    private void showDownloadSelectionDialog(Game game, List<DownloadLink> downloadLinks) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_download_selection, null);
        builder.setView(dialogView);
        
        // Configurar elementos do diálogo
        TextView gameTitle = dialogView.findViewById(R.id.gameTitle);
        TextView selectionSummary = dialogView.findViewById(R.id.selectionSummary);
        TextView totalSizeText = dialogView.findViewById(R.id.totalSizeText);
        Button selectAllButton = dialogView.findViewById(R.id.selectAllButton);
        Button selectNoneButton = dialogView.findViewById(R.id.selectNoneButton);
        Button cancelButton = dialogView.findViewById(R.id.cancelButton);
        Button downloadButton = dialogView.findViewById(R.id.downloadButton);
        RecyclerView recyclerView = dialogView.findViewById(R.id.downloadLinksRecyclerView);
        
        gameTitle.setText(game.getTitle());
        
        // Configurar RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        DownloadLinkAdapter adapter = new DownloadLinkAdapter(this, downloadLinks, selectedLinks -> {
            // Atualizar informações de seleção
            int selectedCount = selectedLinks.size();
            long totalSize = 0;
            for (DownloadLink link : selectedLinks) {
                totalSize += link.getSize();
            }
            
            selectionSummary.setText(getString(R.string.files_selected, selectedCount));
            totalSizeText.setText(getString(R.string.total_size_selected, Game.formatFileSize(totalSize)));
            downloadButton.setEnabled(selectedCount > 0);
        });
        recyclerView.setAdapter(adapter);
        
        // Configurar botões de seleção
        selectAllButton.setOnClickListener(v -> adapter.selectAll());
        selectNoneButton.setOnClickListener(v -> adapter.selectNone());
        
        AlertDialog dialog = builder.create();
        
        // Configurar botões de ação
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        downloadButton.setOnClickListener(v -> {
            Set<DownloadLink> selectedLinks = adapter.getSelectedLinks();
            if (!selectedLinks.isEmpty()) {
                dialog.dismiss();
                if (!safDownloadManager.hasDownloadLocationConfigured()) {
                    Log.d("LibraryActivity", "No download folder configured, requesting selection");
                    pendingDownloadGame = game;
                    // Converter Set para List para o pending download
                    pendingSelectedLinks = new ArrayList<>(selectedLinks);
                    showFolderSelectionDialog();
                } else {
                    startMultipleDownloads(game, selectedLinks);
                }
            }
        });
        
        dialog.show();
    }
    
    private void showFolderSelectionDialog() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.download_folder))
                .setMessage("Você precisa selecionar uma pasta onde os jogos serão salvos. Deseja escolher uma pasta agora?")
                .setPositiveButton(getString(R.string.choose_folder), (dialog, which) -> {
                    openFolderPicker();
                })
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    pendingDownloadGame = null;
                    pendingDownloadLink = null;
                    pendingSelectedLinks = null;
                })
                .setCancelable(false)
                .show();
    }
    
    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | 
                       Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                       Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        
        // Definir diretório inicial se possível
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            java.io.File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri initialUri = android.net.Uri.fromFile(downloadsDir);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        
        try {
            folderPickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e("LibraryActivity", "Erro ao abrir seletor de pasta", e);
            Toast.makeText(this, "Erro ao abrir seletor de pasta", Toast.LENGTH_SHORT).show();
            pendingDownloadGame = null;
            pendingDownloadLink = null;
            pendingSelectedLinks = null;
        }
    }
    
    private void startGameDownload(Game game, DownloadLink downloadLink) {
        Log.d("LibraryActivity", "Starting download for: " + game.getTitle() + " - " + downloadLink.getName());
        
        // Start the download service
        Intent downloadIntent = DownloadService.createDownloadIntent(this, game, downloadLink);
        startForegroundService(downloadIntent);
        
        // Update game status
        game.setStatus(Game.DownloadStatus.DOWNLOADING);
        databaseHelper.updateGame(game);
        gamesAdapter.updateGame(game);
        
        Toast.makeText(this, "Download started: " + game.getTitle(), Toast.LENGTH_SHORT).show();
    }
    
    private void startMultipleDownloads(Game game, Set<DownloadLink> selectedLinks) {
        Log.d("LibraryActivity", "Starting multiple downloads for: " + game.getTitle() + " - " + selectedLinks.size() + " files");
        
        // Criar batch de download no banco de dados
        long batchId = databaseHelper.createDownloadBatch(game.getId(), selectedLinks.size());
        
        // Inserir cada download individual no banco
        for (DownloadLink link : selectedLinks) {
            databaseHelper.insertDownload(game.getId(), link.getId(), link.getFileName(), link.getUrl());
        }
        
        // Atualizar status do jogo para DOWNLOADING
        game.setStatus(Game.DownloadStatus.DOWNLOADING);
        databaseHelper.updateGame(game);
        gamesAdapter.updateGame(game);
        
        // Iniciar serviço de download com múltiplos arquivos
        Intent downloadIntent = DownloadService.createMultipleDownloadIntent(this, game, new ArrayList<>(selectedLinks));
        startForegroundService(downloadIntent);
        
        Toast.makeText(this, "Downloads iniciados: " + selectedLinks.size() + " arquivos de " + game.getTitle(), Toast.LENGTH_LONG).show();
    }
    
    @Override
    public void onPauseDownload(Game game) {
        Intent pauseIntent = DownloadService.createPauseIntent(this, game.getId());
        startService(pauseIntent);
    }

    @Override
    public void onResumeDownload(Game game) {
        Intent resumeIntent = DownloadService.createResumeIntent(this, game.getId());
        startService(resumeIntent);
    }

    @Override
    public void onCancelDownload(Game game) {
        // Cancelar download
        Intent cancelIntent = DownloadService.createCancelIntent(this, game.getId());
        startService(cancelIntent);
        
        // Atualizar status do jogo
        game.setStatus(Game.DownloadStatus.NOT_DOWNLOADED);
        game.setDownloadProgress(0);
        databaseHelper.updateGame(game);
        gamesAdapter.updateGame(game);
        
        Toast.makeText(this, "Download cancelado: " + game.getTitle(), Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onOpenGame(Game game) {
        // Mostrar informações do jogo baixado ou abrir pasta
        if (game.getLocalPath() != null && !game.getLocalPath().isEmpty()) {
            showGameDetails(game);
        }
    }
    
    @Override
    public void onGameClick(Game game) {
        showGameDetails(game);
    }
    
    private void showGameDetails(Game game) {
        // Criar dialog com detalhes do jogo
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle(game.getTitle());
        
        StringBuilder details = new StringBuilder();
        
        if (game.getDeveloper() != null) {
            details.append("Desenvolvedor: ").append(game.getDeveloper()).append("\n");
        }
        
        if (game.getPublisher() != null) {
            details.append("Publisher: ").append(game.getPublisher()).append("\n");
        }
        
        if (!game.getGenres().isEmpty()) {
            details.append("Gêneros: ").append(game.getGenresString()).append("\n");
        }
        
        if (game.getTotalSize() > 0) {
            details.append("Tamanho: ").append(game.getFormattedSize()).append("\n");
        }
        
        details.append("Status: ");
        switch (game.getStatus()) {
            case NOT_DOWNLOADED:
                details.append("Não baixado");
                break;
            case DOWNLOADING:
                details.append("Baixando... ").append(game.getDownloadProgressPercent()).append("%");
                break;
            case DOWNLOADED:
                details.append("Baixado");
                break;
            case FAILED:
                details.append("Falha no download");
                break;
        }
        
        if (game.getLocalPath() != null && !game.getLocalPath().isEmpty()) {
            details.append("\nLocal: ").append(game.getLocalPath());
        }
        
        builder.setMessage(details.toString());
        builder.setPositiveButton("OK", null);
        builder.show();
    }
    
    /**
     * Tests Dynamic Color compatibility with Material 1.10
     */
    private void testDynamicColorCompatibility() {
        // Run compatibility test in background to avoid blocking UI
        new Thread(() -> {
            try {
                DynamicColorTester.testDynamicColorImplementation(this);
                
                boolean isCompatible = DynamicColorTester.isCompatibilityOk(this);
                Log.d("LibraryActivity", "Dynamic Color Material 1.10 Compatibility: " + 
                    (isCompatible ? "✅ WORKING" : "❌ ISSUES DETECTED"));
                    
            } catch (Exception e) {
                Log.e("LibraryActivity", "Error testing Dynamic Color compatibility", e);
            }
        }).start();
    }
    
    private void showFolderPickerForSource() {
        FolderPickerDialogFragment dialog = FolderPickerDialogFragment.newInstance();
        dialog.setFolderPickerListener(path -> {
            sourceFolderPath = path;
            showFolderPickerForDestination();
        });
        dialog.show(getSupportFragmentManager(), "source_folder_picker");
    }

    private void showFolderPickerForDestination() {
        FolderPickerDialogFragment dialog = FolderPickerDialogFragment.newInstance();
        dialog.setFolderPickerListener(path -> {
            destinationFolderPath = path;
            if (sourceFolderPath != null && destinationFolderPath != null) {
                // Iniciar a instalação com os caminhos selecionados
                // Esta é uma simplificação. A lógica real pode precisar
                // de mais validações e talvez de uma lista de arquivos de origem.
                List<String> sourceFiles = new ArrayList<>();
                File sourceDir = new File(sourceFolderPath);
                File[] files = sourceDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".exe"));
                if (files != null) {
                    for (File file : files) {
                        sourceFiles.add(file.getAbsolutePath());
                    }
                }
                if (!sourceFiles.isEmpty()) {
                    // Inicia a instalação do jogo usando o Termux
                    installGameWithInnoextract(sourceFolderPath, destinationFolderPath);
                } else {
                    Toast.makeText(this, "Nenhum arquivo .exe encontrado na pasta de origem.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        dialog.show(getSupportFragmentManager(), "destination_folder_picker");
    }

    @Override
    public void onFolderSelected(String path) {
        // Este método pode ser usado para um callback mais genérico se necessário,
        // mas a lógica principal está nos listeners individuais para clareza.
    }

    private void showInstallProgressDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Instalando Jogo");
        View view = getLayoutInflater().inflate(R.layout.dialog_install_progress, null);
        builder.setView(view);
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.show();

        ProgressBar progressBar = view.findViewById(R.id.installProgressBar);
        TextView progressText = view.findViewById(R.id.installProgressText);

        installProgressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String error = intent.getStringExtra(InstallationService.EXTRA_INSTALL_ERROR);
                if (error != null) {
                    dialog.dismiss();
                    Toast.makeText(LibraryActivity.this, "Erro na instalação: " + error, Toast.LENGTH_LONG).show();
                    return;
                }

                int progress = intent.getIntExtra(InstallationService.EXTRA_INSTALL_PROGRESS, 0);
                String message = intent.getStringExtra(InstallationService.EXTRA_INSTALL_MESSAGE);

                progressBar.setProgress(progress);
                progressText.setText(message);

                if (progress >= 100) {
                    dialog.dismiss();
                    Toast.makeText(LibraryActivity.this, "Instalação concluída!", Toast.LENGTH_SHORT).show();
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(installProgressReceiver, new IntentFilter(InstallationService.ACTION_INSTALL_PROGRESS));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (databaseHelper != null) {
            databaseHelper.close();
        }
        if (installProgressReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(installProgressReceiver);
        }
    }
}