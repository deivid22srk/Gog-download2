package com.termux.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.termux.R;
import com.termux.activities.LibraryActivity;
import com.termux.api.GOGLibraryManager;
import com.termux.database.DatabaseHelper;
import com.termux.models.DownloadLink;
import com.termux.models.Game;
import com.termux.utils.PreferencesManager;
import com.termux.utils.SAFDownloadManager;
import com.termux.utils.SpeedMeter;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DownloadService extends Service {
    
    private static final String TAG = "DownloadService";
    
    // Actions
    public static final String ACTION_DOWNLOAD_PROGRESS = "com.termux.DOWNLOAD_PROGRESS";
    private static final String ACTION_DOWNLOAD = "com.termux.DOWNLOAD";
    private static final String ACTION_DOWNLOAD_MULTIPLE = "com.termux.DOWNLOAD_MULTIPLE";
    private static final String ACTION_PAUSE = "com.termux.PAUSE";
    private static final String ACTION_RESUME = "com.termux.RESUME";
    private static final String ACTION_RESUME_DOWNLOADS = "com.termux.RESUME_DOWNLOADS";
    private static final String ACTION_CANCEL = "com.termux.CANCEL";
    private static final String ACTION_STOP_SERVICE = "com.termux.STOP_SERVICE";
    
    // Extras
    public static final String EXTRA_GAME_ID = "extra_game_id";
    public static final String EXTRA_BYTES_DOWNLOADED = "extra_bytes_downloaded";
    public static final String EXTRA_TOTAL_BYTES = "extra_total_bytes";
    public static final String EXTRA_CURRENT_FILE_INDEX = "extra_current_file_index";
    public static final String EXTRA_TOTAL_FILES = "extra_total_files";
    public static final String EXTRA_DOWNLOAD_SPEED = "extra_download_speed";
    public static final String EXTRA_ETA = "extra_eta";
    public static final String EXTRA_DOWNLOAD_STATUS = "extra_download_status";
    private static final String EXTRA_GAME = "extra_game";
    private static final String EXTRA_DOWNLOAD_LINK = "extra_download_link";
    private static final String EXTRA_DOWNLOAD_LINKS = "extra_download_links";
    
    // Notification
    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 1000;
    
    private NotificationManager notificationManager;
    private ExecutorService executorService;
    private Map<Long, DownloadTask> activeDownloads;
    private Map<Long, BatchDownloadTask> activeBatchDownloads;
    private Set<Long> autoPausedDownloads;
    private NetworkChangeReceiver networkChangeReceiver;
    
    private GOGLibraryManager libraryManager;
    private DatabaseHelper databaseHelper;
    private PreferencesManager preferencesManager;
    private SAFDownloadManager safDownloadManager;
    private OkHttpClient httpClient;
    
    public static Intent createDownloadIntent(Context context, Game game, DownloadLink downloadLink) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_DOWNLOAD);
        intent.putExtra(EXTRA_GAME, game);
        intent.putExtra(EXTRA_DOWNLOAD_LINK, downloadLink);
        return intent;
    }
    
    public static Intent createMultipleDownloadIntent(Context context, Game game, List<DownloadLink> downloadLinks) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_DOWNLOAD_MULTIPLE);
        intent.putExtra(EXTRA_GAME, game);
        intent.putExtra(EXTRA_DOWNLOAD_LINKS, new ArrayList<>(downloadLinks));
        return intent;
    }
    
    public static Intent createCancelIntent(Context context, long gameId) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_CANCEL);
        intent.putExtra(EXTRA_GAME_ID, gameId);
        return intent;
    }

    public static Intent createPauseIntent(Context context, long gameId) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_PAUSE);
        intent.putExtra(EXTRA_GAME_ID, gameId);
        return intent;
    }

    public static Intent createResumeIntent(Context context, long gameId) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_RESUME);
        intent.putExtra(EXTRA_GAME_ID, gameId);
        return intent;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        executorService = Executors.newFixedThreadPool(3); // Máximo 3 downloads simultâneos
        activeDownloads = new ConcurrentHashMap<>();
        activeBatchDownloads = new ConcurrentHashMap<>();
        autoPausedDownloads = new HashSet<>();
        networkChangeReceiver = new NetworkChangeReceiver();
        
        libraryManager = new GOGLibraryManager(this);
        databaseHelper = new DatabaseHelper(this);
        preferencesManager = new PreferencesManager(this);
        safDownloadManager = new SAFDownloadManager(this);
        
        // Configurar cliente HTTP otimizado para downloads rápidos
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)  // Timeout de conexão mais rápido
                .readTimeout(60, TimeUnit.SECONDS)     // Timeout de leitura otimizado
                .writeTimeout(30, TimeUnit.SECONDS)    // Timeout de escrita otimizado
                .connectionPool(new okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES)) // Pool de conexões
                .retryOnConnectionFailure(true)       // Retry automático em falhas
                .followRedirects(true)                 // Seguir redirects automaticamente
                .followSslRedirects(true)
                .build();
        
        createNotificationChannel();
        
        // Retomar downloads pendentes
        resumePendingDownloads();
        
        // Registrar o receiver de mudança de rede
        IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkChangeReceiver, intentFilter);

        Log.d(TAG, "DownloadService created");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        
        String action = intent.getAction();
        
        if (ACTION_DOWNLOAD.equals(action)) {
            Game game = (Game) intent.getSerializableExtra(EXTRA_GAME);
            DownloadLink downloadLink = (DownloadLink) intent.getSerializableExtra(EXTRA_DOWNLOAD_LINK);
            if (game != null && downloadLink != null) {
                startDownload(game, downloadLink);
            }
        } else if (ACTION_DOWNLOAD_MULTIPLE.equals(action)) {
            Game game = (Game) intent.getSerializableExtra(EXTRA_GAME);
            @SuppressWarnings("unchecked")
            List<DownloadLink> downloadLinks = (List<DownloadLink>) intent.getSerializableExtra(EXTRA_DOWNLOAD_LINKS);
            if (game != null && downloadLinks != null && !downloadLinks.isEmpty()) {
                startBatchDownload(game, downloadLinks);
            }
        } else if (ACTION_RESUME_DOWNLOADS.equals(action)) {
            Log.d(TAG, "Received RESUME_DOWNLOADS action");
            // Não fazer nada aqui, o resumePendingDownloads() já foi chamado no onCreate
        } else if (ACTION_PAUSE.equals(action)) {
            long gameId = intent.getLongExtra(EXTRA_GAME_ID, -1);
            if (gameId != -1) {
                pauseDownload(gameId);
            }
        } else if (ACTION_RESUME.equals(action)) {
            long gameId = intent.getLongExtra(EXTRA_GAME_ID, -1);
            if (gameId != -1) {
                resumeDownload(gameId);
            }
        } else if (ACTION_CANCEL.equals(action)) {
            long gameId = intent.getLongExtra(EXTRA_GAME_ID, -1);
            if (gameId != -1) {
                cancelDownload(gameId);
            }
        } else if (ACTION_STOP_SERVICE.equals(action)) {
            stopService();
        }
        
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Serviço não precisa de binding
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Cancelar todos os downloads ativos
        for (DownloadTask task : activeDownloads.values()) {
            task.cancel();
        }
        
        for (BatchDownloadTask task : activeBatchDownloads.values()) {
            task.cancel();
        }
        
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
        
        if (databaseHelper != null) {
            databaseHelper.close();
        }

        // Desregistrar o receiver
        try {
            unregisterReceiver(networkChangeReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "NetworkChangeReceiver was not registered", e);
        }
        
        Log.d(TAG, "DownloadService destroyed");
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.download_channel_description));
            channel.setShowBadge(true);
            
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private void startDownload(Game game, DownloadLink downloadLink) {
        Log.d(TAG, "Starting download for game: " + game.getTitle());

        if (activeDownloads.containsKey(game.getId())) {
            Log.w(TAG, "Game is already being downloaded: " + game.getTitle());
            return;
        }

        // Insert a record for this download and get its ID
        long downloadId = databaseHelper.insertDownload(
                game.getId(),
                downloadLink.getId(),
                downloadLink.getFileName(),
                downloadLink.getUrl()
        );

        if (downloadId == -1) {
            onDownloadError(game, "Failed to create download record in database.");
            return;
        }

        // Update game status
        game.setStatus(Game.DownloadStatus.DOWNLOADING);
        databaseHelper.updateGame(game);

        // Create initial notification
        showDownloadNotification(game, 0, "Starting download...");
        startForeground(NOTIFICATION_ID + (int) game.getId(),
                createDownloadNotification(game, 0, "Starting download..."));

        // Get the real download URL and then start the task
        libraryManager.getDownloadLink(game.getId(), downloadLink, "installer",
                new GOGLibraryManager.DownloadLinkCallback() {
            @Override
            public void onSuccess(String downloadUrl) {
                downloadLink.setDownloadUrl(downloadUrl);
                DownloadTask task = new DownloadTask(game, downloadLink, downloadId, null);
                activeDownloads.put(game.getId(), task);
                executorService.execute(task);
            }

            @Override
            public void onError(String error) {
                onDownloadError(game, "Failed to get download URL: " + error);
                databaseHelper.updateDownloadStatus(downloadId, "FAILED", error);
            }
        });
    }
    
    private void startBatchDownload(Game game, List<DownloadLink> downloadLinks) {
        Log.d(TAG, "Starting batch download for game: " + game.getTitle() + " with " + downloadLinks.size() + " files");
        
        if (activeBatchDownloads.containsKey(game.getId())) {
            Log.w(TAG, "Game is already being downloaded: " + game.getTitle());
            return;
        }
        
        game.setStatus(Game.DownloadStatus.DOWNLOADING);
        databaseHelper.updateGame(game);

        // Serialize links and create batch record
        String linksJson = DownloadLink.serializeList(downloadLinks);
        if (linksJson == null) {
            onDownloadError(game, "Failed to serialize download links.");
            return;
        }
        databaseHelper.createDownloadBatch(game.getId(), downloadLinks.size(), linksJson);
        
        showBatchDownloadNotification(game, 0, downloadLinks.size(), "Iniciando downloads...");
        
        startForeground(NOTIFICATION_ID + (int) game.getId(),
                createBatchDownloadNotification(game, 0, downloadLinks.size(), "Iniciando downloads..."));
        
        BatchDownloadTask batchTask = new BatchDownloadTask(game, downloadLinks);
        activeBatchDownloads.put(game.getId(), batchTask);
        executorService.execute(batchTask);
    }
    
    private void resumePendingDownloads() {
        Log.d(TAG, "Checking for pending downloads to resume...");
        
        executorService.execute(() -> {
            try {
                // Buscar downloads ativos no banco de dados
                List<ContentValues> activeDownloads = databaseHelper.getActiveDownloads();
                
                if (activeDownloads.isEmpty()) {
                    Log.d(TAG, "No pending downloads found");
                    return;
                }
                
                Log.d(TAG, "Found " + activeDownloads.size() + " pending downloads");
                
                // Agrupar downloads por jogo
                Map<Long, List<ContentValues>> downloadsByGame = new HashMap<>();
                for (ContentValues download : activeDownloads) {
                    long gameId = download.getAsLong("game_id");
                    downloadsByGame.computeIfAbsent(gameId, k -> new ArrayList<>()).add(download);
                }
                
                for (Map.Entry<Long, List<ContentValues>> entry : downloadsByGame.entrySet()) {
                    long gameId = entry.getKey();
                    List<ContentValues> gameDownloads = entry.getValue();
                    
                    Game game = databaseHelper.getGame(gameId);
                    if (game == null) {
                        Log.w(TAG, "Game not found for ID: " + gameId + ", cleaning up downloads");
                        for (ContentValues download : gameDownloads) {
                            long downloadId = download.getAsLong("id");
                            databaseHelper.updateDownloadStatus(downloadId, "CANCELLED", "Game not found");
                        }
                        continue;
                    }

                    // Check the status to decide whether to auto-resume
                    String status = gameDownloads.get(0).getAsString("status");
                    if ("PAUSED".equals(status)) {
                        // If it was explicitly paused by the user, do not auto-resume.
                        // Instead, re-broadcast the paused status to ensure the UI is up-to-date.
                        Log.d(TAG, "Skipping auto-resume for user-paused download: " + game.getTitle());
                        onDownloadPaused(game);
                    } else {
                        // If it was PENDING or DOWNLOADING, auto-resume for crash recovery.
                        Log.d(TAG, "Auto-resuming interrupted download: " + game.getTitle());
                        if (gameDownloads.size() == 1) {
                            resumeSingleDownload(game, gameDownloads.get(0));
                        } else {
                            resumeBatchDownload(game, gameDownloads);
                        }
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error resuming pending downloads", e);
            }
        });
    }
    
    private void resumeSingleDownload(Game game, ContentValues downloadData) {
        try {
            String linkId = downloadData.getAsString("link_id");
            String fileName = downloadData.getAsString("file_name");
            String downloadUrl = downloadData.getAsString("download_url");

            DownloadLink downloadLink = new DownloadLink();
            downloadLink.setId(linkId);
            downloadLink.setName(fileName);
            downloadLink.setDownloadUrl(downloadUrl);
            
            Log.d(TAG, "Resuming single download: " + game.getTitle() + " - " + fileName);
            
            startDownload(game, downloadLink);
            
        } catch (Exception e) {
            Log.e(TAG, "Error resuming single download for game: " + game.getTitle(), e);
        }
    }
    
    private void resumeBatchDownload(Game game, List<ContentValues> downloads) {
        // The 'downloads' list confirms it's a batch, but we get definitive data from the batch table.
        try {
            ContentValues batchData = databaseHelper.getDownloadBatch(game.getId());
            if (batchData == null) {
                Log.e(TAG, "Cannot resume batch, no batch data found for game: " + game.getTitle());
                onDownloadError(game, "Could not find batch data to resume.");
                return;
            }
            String linksJson = batchData.getAsString("links_json");
            List<DownloadLink> links = DownloadLink.deserializeList(linksJson);

            if (links != null && !links.isEmpty()) {
                Log.d(TAG, "Resuming batch download from service startup for: " + game.getTitle());
                startBatchDownload(game, links);
            } else {
                Log.e(TAG, "Failed to resume batch, could not deserialize links for game: " + game.getTitle());
                onDownloadError(game, "Could not read batch data to resume.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resuming batch download for game: " + game.getTitle(), e);
        }
    }

    private void pauseDownload(long gameId) {
        Log.d(TAG, "Pausing download for game ID: " + gameId);
        DownloadTask task = activeDownloads.get(gameId);
        if (task != null) {
            task.pause();
        }

        BatchDownloadTask batchTask = activeBatchDownloads.get(gameId);
        if (batchTask != null) {
            batchTask.pause();
        }
    }

    private void resumeDownload(long gameId) {
        Log.d(TAG, "Attempting to resume download for game ID: " + gameId);

        if (activeDownloads.containsKey(gameId) || activeBatchDownloads.containsKey(gameId)) {
            Log.w(TAG, "Resume called for an already active download: " + gameId);
            return;
        }

        Game game = databaseHelper.getGame(gameId);
        if (game == null) {
            Log.e(TAG, "Cannot resume download, game not found for ID: " + gameId);
            return;
        }

        // Check if it's a batch download
        ContentValues batchData = databaseHelper.getDownloadBatch(gameId);
        if (batchData != null) {
            String linksJson = batchData.getAsString("links_json");
            List<DownloadLink> links = DownloadLink.deserializeList(linksJson);
            if (links != null && !links.isEmpty()) {
                Log.d(TAG, "Resuming batch download for: " + game.getTitle());
                startBatchDownload(game, links);
            } else {
                Log.e(TAG, "Failed to resume batch download, could not deserialize links for game: " + game.getTitle());
                onDownloadError(game, "Falha ao ler dados do batch para resumir.");
            }
        } else {
            // Check for single download
            List<ContentValues> downloads = databaseHelper.getDownloadsForGame(gameId);
            if (downloads.size() == 1) {
                Log.d(TAG, "Resuming single download for: " + game.getTitle());
                ContentValues downloadData = downloads.get(0);
                DownloadLink link = new DownloadLink();
                link.setId(downloadData.getAsString("link_id"));
                link.setName(downloadData.getAsString("file_name"));
                link.setUrl(downloadData.getAsString("download_url"));
                link.setSize(downloadData.getAsLong("total_bytes"));
                startDownload(game, link);
            } else {
                Log.w(TAG, "No active single or batch download found to resume for game: " + game.getTitle());
            }
        }
    }

    private void cancelDownload(long gameId) {
        Log.d(TAG, "Cancelling download for game ID: " + gameId);
        
        DownloadTask task = activeDownloads.get(gameId);
        BatchDownloadTask batchTask = activeBatchDownloads.get(gameId);
        
        if (task != null) {
            task.cancel();
            activeDownloads.remove(gameId);
        }
        
        if (batchTask != null) {
            batchTask.cancel();
            activeBatchDownloads.remove(gameId);
        }
        
        if (task != null || batchTask != null) {
            // Atualizar status no banco
            Game game = databaseHelper.getGame(gameId);
            if (game != null) {
                game.setStatus(Game.DownloadStatus.NOT_DOWNLOADED);
                game.setDownloadProgress(0);
                databaseHelper.updateGame(game);
            }
            
            // Remover notificação
            notificationManager.cancel(NOTIFICATION_ID + (int) game.getId());
            
            // Parar foreground se não há mais downloads
            if (activeDownloads.isEmpty() && activeBatchDownloads.isEmpty()) {
                stopForeground(true);
            }
        }
    }
    
    private void stopService() {
        Log.d(TAG, "Stopping download service");
        
        // Cancelar todos os downloads
        Set<Long> allGameIds = new HashSet<>();
        allGameIds.addAll(activeDownloads.keySet());
        allGameIds.addAll(activeBatchDownloads.keySet());
        
        for (long gameId : allGameIds) {
            cancelDownload(gameId);
        }
        
        stopSelf();
    }
    
    private void onDownloadProgress(Game game, long bytesDownloaded, long totalBytes) {
        onDownloadProgress(game, bytesDownloaded, totalBytes, 0, 0, 0, 0);
    }
    
    private void onDownloadProgress(Game game, long bytesDownloaded, long totalBytes, 
                                   int currentFileIndex, int totalFiles, double speed, long eta) {
        int progress = totalBytes > 0 ? (int) ((bytesDownloaded * 100) / totalBytes) : 0;
        
        // Atualizar banco de dados
        game.setDownloadProgress(bytesDownloaded);
        game.setTotalSize(totalBytes);
        databaseHelper.updateGame(game);
        
        // Atualizar notificação
        String progressText;
        if (totalFiles > 1) {
            // Batch download
            String speedText = speed > 0 ? String.format(" - %.1f MB/s", speed / (1024 * 1024)) : "";
            String etaText = eta > 0 ? String.format(" - ETA: %s", formatETA(eta)) : "";
            progressText = String.format("Arquivo %d/%d - %d%% - %s / %s%s%s",
                    currentFileIndex + 1, totalFiles, progress,
                    Game.formatFileSize(bytesDownloaded),
                    Game.formatFileSize(totalBytes),
                    speedText, etaText);
            showBatchDownloadNotification(game, currentFileIndex, totalFiles, progressText);
        } else {
            // Single download
            String speedText = speed > 0 ? String.format(" - %.1f MB/s", speed / (1024 * 1024)) : "";
            String etaText = eta > 0 ? String.format(" - ETA: %s", formatETA(eta)) : "";
            progressText = String.format("%d%% - %s / %s%s%s", 
                    progress,
                    Game.formatFileSize(bytesDownloaded),
                    Game.formatFileSize(totalBytes),
                    speedText, etaText);
            showDownloadNotification(game, progress, progressText);
        }

        Intent intent = new Intent(ACTION_DOWNLOAD_PROGRESS);
        intent.putExtra(EXTRA_GAME_ID, game.getId());
        intent.putExtra(EXTRA_BYTES_DOWNLOADED, bytesDownloaded);
        intent.putExtra(EXTRA_TOTAL_BYTES, totalBytes);
        intent.putExtra(EXTRA_CURRENT_FILE_INDEX, currentFileIndex);
        intent.putExtra(EXTRA_TOTAL_FILES, totalFiles);
        intent.putExtra(EXTRA_DOWNLOAD_SPEED, (float)speed);
        intent.putExtra(EXTRA_ETA, eta);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    private String formatETA(long etaSeconds) {
        if (etaSeconds < 60) {
            return etaSeconds + "s";
        } else if (etaSeconds < 3600) {
            return (etaSeconds / 60) + "m " + (etaSeconds % 60) + "s";
        } else {
            long hours = etaSeconds / 3600;
            long minutes = (etaSeconds % 3600) / 60;
            return hours + "h " + minutes + "m";
        }
    }
    
    private void onDownloadPaused(Game game) {
        Log.d(TAG, "Broadcasting pause for game: " + game.getTitle());
        Intent intent = new Intent(ACTION_DOWNLOAD_PROGRESS);
        intent.putExtra(EXTRA_GAME_ID, game.getId());
        intent.putExtra(EXTRA_DOWNLOAD_STATUS, Game.DownloadStatus.PAUSED.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void onDownloadComplete(Game game, long downloadId, String filePath) {
        Log.d(TAG, "Download completed for game: " + game.getTitle());
        
        // Atualizar status no banco
        game.setStatus(Game.DownloadStatus.DOWNLOADED);
        game.setLocalPath(filePath);
        databaseHelper.updateGame(game);
        databaseHelper.updateDownloadStatus(downloadId, "COMPLETED", null);
        
        // Remover da lista de downloads ativos
        activeDownloads.remove(game.getId());
        
        // Mostrar notificação de conclusão
        showCompletionNotification(game);
        
        // Parar foreground se não há mais downloads
        if (activeDownloads.isEmpty() && activeBatchDownloads.isEmpty()) {
            stopForeground(true);
        }
    }
    
    private void onDownloadError(Game game, String error) {
        Log.e(TAG, "Download failed for game: " + game.getTitle() + " - " + error);
        
        // Atualizar status no banco
        game.setStatus(Game.DownloadStatus.FAILED);
        databaseHelper.updateGame(game);
        
        // Remover da lista de downloads ativos
        activeDownloads.remove(game.getId());
        activeBatchDownloads.remove(game.getId());
        
        // Mostrar notificação de erro
        showErrorNotification(game, error);
        
        // Parar foreground se não há mais downloads
        if (activeDownloads.isEmpty() && activeBatchDownloads.isEmpty()) {
            stopForeground(true);
        }
    }
    
    private void showDownloadNotification(Game game, int progress, String progressText) {
        Notification notification = createDownloadNotification(game, progress, progressText);
        notificationManager.notify(NOTIFICATION_ID + (int) game.getId(), notification);
    }
    
    private Notification createDownloadNotification(Game game, int progress, String progressText) {
        Intent intent = new Intent(this, LibraryActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Intent cancelIntent = createCancelIntent(this, game.getId());
        PendingIntent cancelPendingIntent = PendingIntent.getService(this, (int) game.getId(), 
                cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.downloading_game, game.getTitle()))
                .setContentText(progressText)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, progress == 0)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_delete, 
                        getString(R.string.cancel), cancelPendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }
    
    private void showCompletionNotification(Game game) {
        Intent intent = new Intent(this, LibraryActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_complete, game.getTitle()))
                .setContentText("Download concluído com sucesso")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        
        notificationManager.notify(NOTIFICATION_ID + (int) game.getId(), notification);
    }
    
    private void showErrorNotification(Game game, String error) {
        Intent intent = new Intent(this, LibraryActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_failed, game.getTitle()))
                .setContentText(error)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        
        notificationManager.notify(NOTIFICATION_ID + (int) game.getId(), notification);
    }
    
    private void showBatchDownloadNotification(Game game, int currentFileIndex, int totalFiles, String progressText) {
        Notification notification = createBatchDownloadNotification(game, currentFileIndex, totalFiles, progressText);
        notificationManager.notify(NOTIFICATION_ID + (int) game.getId(), notification);
    }
    
    private Notification createBatchDownloadNotification(Game game, int currentFileIndex, int totalFiles, String progressText) {
        Intent intent = new Intent(this, LibraryActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Intent cancelIntent = createCancelIntent(this, game.getId());
        PendingIntent cancelPendingIntent = PendingIntent.getService(this, (int) game.getId(), 
                cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        int overallProgress = totalFiles > 0 ? (int) (((currentFileIndex * 100.0) / totalFiles)) : 0;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.downloading_game, game.getTitle()))
                .setContentText(progressText)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, overallProgress, false)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_delete, 
                        getString(R.string.cancel), cancelPendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }
    
    private interface FileDownloadListener {
        void onProgress(String linkId, long bytesDownloaded, long totalBytes, double speed, long eta);
        void onComplete(String linkId, boolean success, String filePath);
    }

    private class SegmentDownloadTask implements Runnable {
        private final Game game;
        private final DownloadLink downloadLink;
        private final long downloadId;
        private final String url;
        private final ContentValues segment;
        private volatile boolean cancelled = false;
        private volatile boolean paused = false;

        public SegmentDownloadTask(Game game, DownloadLink downloadLink, long downloadId, String url, ContentValues segment) {
            this.game = game;
            this.downloadLink = downloadLink;
            this.downloadId = downloadId;
            this.url = url;
            this.segment = segment;
        }

        public void cancel() { this.cancelled = true; }
        public void pause() { this.paused = true; }

        @Override
        public void run() {
            try {
                downloadSegmentWithRetries();
            } catch (IOException e) {
                Log.e(TAG, "Segment download failed permanently.", e);
            }
        }

        private void downloadSegmentWithRetries() throws IOException {
            long segmentId = segment.getAsLong("id");
            databaseHelper.updateSegmentStatus(segmentId, "DOWNLOADING");
            Exception lastException = null;
            final int MAX_RETRIES = 3;
            long retryDelay = 2000;

            for (int i = 0; i < MAX_RETRIES; i++) {
                if (paused || cancelled) break;

                try {
                    int segmentIndex = segment.getAsInteger("segment_index");
                    long startByte = segment.getAsLong("start_byte");
                    long endByte = segment.getAsLong("end_byte");
                    long downloadedBytes = databaseHelper.getDownloadSegments(downloadId).get(segmentIndex).getAsLong("downloaded_bytes");

                    DocumentFile segmentFile = safDownloadManager.createTempSegmentFile(game, downloadLink, segmentIndex);
                    if (segmentFile == null) throw new IOException("Failed to create segment file.");

                    Request request = new Request.Builder()
                        .url(url)
                        .header("Range", "bytes=" + (startByte + downloadedBytes) + "-" + endByte)
                        .get()
                        .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful() && response.code() != 206) {
                            if (response.code() >= 400 && response.code() < 500) {
                                throw new IOException("Unrecoverable HTTP error: " + response.code());
                            }
                            throw new IOException("Unexpected HTTP code " + response);
                        }

                        try (InputStream inputStream = response.body().byteStream();
                             OutputStream outputStream = safDownloadManager.getOutputStream(segmentFile, true)) {

                            byte[] buffer = new byte[256 * 1024];
                            int bytesRead;
                            long currentDownloadedBytes = downloadedBytes;

                            while ((bytesRead = inputStream.read(buffer)) != -1 && !cancelled && !paused) {
                                outputStream.write(buffer, 0, bytesRead);
                                currentDownloadedBytes += bytesRead;
                                databaseHelper.updateSegmentProgress(segmentId, currentDownloadedBytes);
                            }

                            if (paused || cancelled) return;

                            databaseHelper.updateSegmentStatus(segmentId, "COMPLETED");
                            return;
                        }
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (e.getMessage() != null && e.getMessage().contains("Unrecoverable")) {
                        break;
                    }
                }

                Log.w(TAG, String.format("Segment download attempt %d/%d failed: %s", i + 1, MAX_RETRIES, lastException.getMessage()));
                if (i < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            databaseHelper.updateSegmentStatus(segmentId, "FAILED");
            throw new IOException("Segment download failed after all retries", lastException);
        }
    }

    private class DownloadTask implements Runnable {
        private final Game game;
        private final DownloadLink downloadLink;
        private final long downloadId;
        private volatile boolean cancelled = false;
        private volatile boolean paused = false;
        private final FileDownloadListener listener;
        private static final int SEGMENT_COUNT = 4;
        private final List<SegmentDownloadTask> segmentTasks = new ArrayList<>();

        public DownloadTask(Game game, DownloadLink downloadLink, long downloadId, FileDownloadListener listener) {
            this.game = game;
            this.downloadLink = downloadLink;
            this.downloadId = downloadId;
            this.listener = listener;
        }

        public void cancel() {
            cancelled = true;
            for(SegmentDownloadTask task : segmentTasks) {
                task.cancel();
            }
        }

        public void pause() {
            paused = true;
            for(SegmentDownloadTask task : segmentTasks) {
                task.pause();
            }
        }

        @Override
        public void run() {
            if (downloadId != -1) {
                databaseHelper.updateDownloadStatus(downloadId, "DOWNLOADING", null);
            }

            try {
                long totalSize = getTotalSize();
                if (totalSize <= 0) throw new IOException("Could not determine file size.");
                downloadLink.setSize(totalSize);
                databaseHelper.updateDownloadProgress(downloadId, 0, totalSize, 0, 0);

                databaseHelper.createDownloadSegments(downloadId, totalSize, SEGMENT_COUNT);
                List<ContentValues> segments = databaseHelper.getDownloadSegments(downloadId);

                ExecutorCompletionService<Boolean> completionService = new ExecutorCompletionService<>(executorService);
                for (ContentValues segment : segments) {
                    SegmentDownloadTask task = new SegmentDownloadTask(game, downloadLink, downloadId, downloadLink.getDownloadUrl(), segment);
                    segmentTasks.add(task);
                    completionService.submit(task, true);
                }

                int successCount = 0;
                for (int i = 0; i < segments.size(); i++) {
                    try {
                        Future<Boolean> result = completionService.take();
                        result.get();
                        successCount++;
                    } catch (Exception e) {
                        Log.e(TAG, "A segment failed to complete", e);
                    }
                }

                if (successCount == SEGMENT_COUNT) {
                    String finalPath = assembleParts();
                    if (listener != null) {
                        listener.onComplete(downloadLink.getId(), true, finalPath);
                    } else {
                        onDownloadComplete(game, downloadId, finalPath);
                    }
                } else {
                    throw new IOException(SEGMENT_COUNT - successCount + " segments failed to download.");
                }

            } catch (Exception e) {
                Log.e(TAG, "DownloadTask (Master) failed", e);
                onDownloadError(game, e.getMessage());
            }
        }

        private String assembleParts() throws IOException {
            Log.d(TAG, "Assembling parts for " + downloadLink.getFileName());
            DocumentFile finalFile = safDownloadManager.createDownloadFile(game, downloadLink, false);
            try (OutputStream finalOs = safDownloadManager.getOutputStream(finalFile, false)) {
                for (int i = 0; i < SEGMENT_COUNT; i++) {
                    DocumentFile partFile = safDownloadManager.createTempSegmentFile(game, downloadLink, i);
                    try (InputStream partIs = safDownloadManager.getInputStream(partFile)) {
                        byte[] buffer = new byte[1024 * 1024];
                        int bytesRead;
                        while ((bytesRead = partIs.read(buffer)) != -1) {
                            finalOs.write(buffer, 0, bytesRead);
                        }
                    }
                    partFile.delete();
                }
            }
            return finalFile.getUri().toString();
        }

        private long getTotalSize() throws IOException {
            Request request = new Request.Builder()
                .url(downloadLink.getDownloadUrl())
                .head()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Failed to get file size: " + response.code());
                }
                long size = response.body().contentLength();
                if (size <= 0) {
                    return downloadLink.getSize();
                }
                return size;
            }
        }
    }

    private class BatchDownloadTask implements Runnable, FileDownloadListener {
        private final Game game;
        private final List<DownloadLink> downloadLinks;
        private final List<DownloadTask> childTasks = new ArrayList<>();
        private volatile boolean cancelled = false;
        private volatile boolean paused = false;
        
        private final Map<String, Long> fileProgress = new ConcurrentHashMap<>();
        private final AtomicInteger completedFileCount = new AtomicInteger(0);
        private final AtomicInteger successfulFileCount = new AtomicInteger(0);
        private final long totalBatchSizeBytes;

        public BatchDownloadTask(Game game, List<DownloadLink> downloadLinks) {
            this.game = game;
            this.downloadLinks = new ArrayList<>(downloadLinks);
            this.totalBatchSizeBytes = downloadLinks.stream().mapToLong(DownloadLink::getSize).sum();
        }

        public void cancel() {
            cancelled = true;
            for (DownloadTask task : childTasks) {
                task.cancel();
            }
        }

        public void pause() {
            paused = true;
            for (DownloadTask task : childTasks) {
                task.pause();
            }
        }

        @Override
        public void run() {
            Log.d(TAG, "Iniciando download em lote paralelo para: " + game.getTitle());
            for (int i = 0; i < downloadLinks.size(); i++) {
                if (cancelled) break;
                DownloadLink link = downloadLinks.get(i);
                try {
                    String downloadUrl = getUrl(link);
                    if (downloadUrl != null) {
                        link.setDownloadUrl(downloadUrl);
                        long downloadId = databaseHelper.insertDownload(game.getId(), link.getId(), link.getFileName(), downloadUrl);
                        DownloadTask task = new DownloadTask(game, link, downloadId, this);
                        childTasks.add(task);
                        executorService.execute(task);
                    } else {
                        onComplete(link.getId(), false, null);
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "Thread interrompida ao obter URL de download.", e);
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        @Override
        public synchronized void onComplete(String linkId, boolean success, String filePath) {
            if (success) {
                successfulFileCount.incrementAndGet();
            }
            int completed = completedFileCount.incrementAndGet();
            
            Log.d(TAG, "Arquivo concluído: " + linkId + " Sucesso: " + success + " (" + completed + "/" + downloadLinks.size() + ")");

            if (completed == downloadLinks.size()) {
                Log.d(TAG, "Todos os arquivos do lote foram processados para: " + game.getTitle());
                if (cancelled) {
                    return;
                }
                if (paused) {
                    return;
                }
                
                activeBatchDownloads.remove(game.getId());

                ContentValues batch = databaseHelper.getDownloadBatch(game.getId());
                if (batch != null) {
                    long batchId = batch.getAsLong("id");
                    databaseHelper.updateBatchProgress(batchId, successfulFileCount.get(), "COMPLETED");
                }

                if (successfulFileCount.get() == downloadLinks.size()) {
                    onDownloadComplete(game, -1, "Download em lote concluído");
                } else {
                    onDownloadError(game, "Alguns arquivos falharam no download. " + successfulFileCount.get() + "/" + downloadLinks.size() + " concluídos.");
                }
            }
        }

        @Override
        public synchronized void onProgress(String linkId, long bytesDownloaded, long totalBytes, double speed, long eta) {
            fileProgress.put(linkId, bytesDownloaded);
            
            long totalBatchDownloaded = fileProgress.values().stream().mapToLong(Long::longValue).sum();
            
            double overallSpeed = 0;
            for (DownloadTask task : childTasks) {
                overallSpeed += task.speedMeter.getCurrentSpeed();
            }

            long overallEta = (totalBatchSizeBytes - totalBatchDownloaded > 0 && overallSpeed > 0)
                ? (long) ((totalBatchSizeBytes - totalBatchDownloaded) / overallSpeed)
                : 0;

            onDownloadProgress(game, totalBatchDownloaded, totalBatchSizeBytes, completedFileCount.get(), downloadLinks.size(), overallSpeed, overallEta);
        }

        private String getUrl(DownloadLink link) throws InterruptedException {
            final String[] downloadUrl = new String[1];
            final String[] errorMessage = new String[1];
            final Object lock = new Object();
            final boolean[] completed = {false};

            libraryManager.getDownloadLink(game.getId(), link, "installer", new GOGLibraryManager.DownloadLinkCallback() {
                @Override
                public void onSuccess(String url) {
                    synchronized (lock) {
                        downloadUrl[0] = url;
                        completed[0] = true;
                        lock.notify();
                    }
                }

                @Override
                public void onError(String error) {
                    synchronized (lock) {
                        errorMessage[0] = error;
                        completed[0] = true;
                        lock.notify();
                    }
                }
            });

            synchronized (lock) {
                while (!completed[0] && !cancelled) {
                    lock.wait(1000);
                }
            }

            if (errorMessage[0] != null) {
                Log.e(TAG, "Erro ao obter URL de download: " + errorMessage[0]);
                return null;
            }

            return downloadUrl[0];
        }
    }

    // Receiver para monitorar mudanças na conexão de rede
    private class NetworkChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();

                if (isConnected) {
                    Log.d(TAG, "Network connection re-established. Resuming auto-paused downloads.");
                    // Create a copy to avoid ConcurrentModificationException while iterating
                    Set<Long> gamesToResume = new HashSet<>(autoPausedDownloads);
                    for (Long gameId : gamesToResume) {
                        resumeDownload(gameId);
                    }
                    autoPausedDownloads.clear();
                } else {
                    Log.d(TAG, "Network connection lost. Pausing all active downloads.");
                    // Pause all downloads that are not already paused
                    Set<Long> allActiveIds = new HashSet<>();
                    allActiveIds.addAll(activeDownloads.keySet());
                    allActiveIds.addAll(activeBatchDownloads.keySet());

                    for (Long gameId : allActiveIds) {
                        // Check if the download is actually running before pausing
                        Game game = databaseHelper.getGame(gameId);
                        if (game != null && game.getStatus() == Game.DownloadStatus.DOWNLOADING) {
                             pauseDownload(gameId);
                             autoPausedDownloads.add(gameId);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in NetworkChangeReceiver", e);
            }
        }
    }
}