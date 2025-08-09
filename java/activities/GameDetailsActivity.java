package com.termux.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.termux.R;
import com.termux.api.GOGLibraryManager;
import com.termux.database.DatabaseHelper;
import com.termux.models.DownloadLink;
import com.termux.models.Game;
import com.termux.services.DownloadService;
import com.termux.utils.ImageLoader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class GameDetailsActivity extends BaseActivity {

    public static final String EXTRA_GAME_ID = "extra_game_id";

    private DatabaseHelper databaseHelper;
    private GOGLibraryManager libraryManager;
    private Game currentGame;
    private long gameId;

    // UI Elements
    private ImageView gameBackdrop;
    private CollapsingToolbarLayout collapsingToolbar;
    private Toolbar toolbar;
    private TextView gameTitle;
    private TextView gameDeveloperPublisher;
    private TextView gameDescription;
    private ChipGroup genresChipGroup;
    private FloatingActionButton fabDownload;
    private LinearLayout downloadSection;
    private ProgressBar downloadProgressBar;
    private TextView downloadProgressText;
    private TextView downloadSpeedText;

    private BroadcastReceiver downloadProgressReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_details);

        if (!getIntent().hasExtra(EXTRA_GAME_ID)) {
            Toast.makeText(this, "Game ID not provided.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        gameId = getIntent().getLongExtra(EXTRA_GAME_ID, -1);
        databaseHelper = new DatabaseHelper(this);
        libraryManager = new GOGLibraryManager(this);

        initializeViews();
        setupToolbar();
        loadGameDetails();
        setupBroadcastReceiver();
        setupClickListeners();
    }

    private void initializeViews() {
        toolbar = findViewById(R.id.toolbar);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        gameBackdrop = findViewById(R.id.game_backdrop);
        gameTitle = findViewById(R.id.game_title);
        gameDeveloperPublisher = findViewById(R.id.game_developer_publisher);
        gameDescription = findViewById(R.id.game_description);
        genresChipGroup = findViewById(R.id.game_genres_chip_group);
        fabDownload = findViewById(R.id.fab_download);
        downloadSection = findViewById(R.id.download_section);
        downloadProgressBar = findViewById(R.id.download_progress_bar);
        downloadProgressText = findViewById(R.id.download_progress_text);
        downloadSpeedText = findViewById(R.id.download_speed_text);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void loadGameDetails() {
        Game game = databaseHelper.getGame(gameId);
        if (game == null) {
            Toast.makeText(this, "Error loading game details.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        currentGame = game;
        populateUI();
        updateDownloadUI();
    }

    private void populateUI() {
        collapsingToolbar.setTitle(currentGame.getTitle());
        gameTitle.setText(currentGame.getTitle());
        ImageLoader.loadImage(this, currentGame.getBackgroundImage(), gameBackdrop);

        String devPub = getString(R.string.developer_publisher_format, currentGame.getDeveloper(), currentGame.getPublisher());
        gameDeveloperPublisher.setText(devPub);

        gameDescription.setText(currentGame.getDescription());

        genresChipGroup.removeAllViews();
        if (currentGame.getGenres() != null) {
            for (String genre : currentGame.getGenres()) {
                Chip chip = new Chip(this);
                chip.setText(genre);
                genresChipGroup.addView(chip);
            }
        }
    }

    private void updateDownloadUI() {
        if (currentGame == null) return;

        switch (currentGame.getStatus()) {
            case NOT_DOWNLOADED:
            case FAILED:
            case CANCELLED:
                downloadSection.setVisibility(View.GONE);
                fabDownload.setImageDrawable(ContextCompat.getDrawable(this, android.R.drawable.stat_sys_download));
                break;
            case DOWNLOADING:
                downloadSection.setVisibility(View.VISIBLE);
                fabDownload.setImageDrawable(ContextCompat.getDrawable(this, android.R.drawable.ic_media_pause));
                updateProgress(currentGame.getDownloadProgress(), currentGame.getTotalSize(), 0, 0, 0, 0);
                break;
            case PAUSED:
                downloadSection.setVisibility(View.VISIBLE);
                fabDownload.setImageDrawable(ContextCompat.getDrawable(this, android.R.drawable.ic_media_play));
                updateProgress(currentGame.getDownloadProgress(), currentGame.getTotalSize(), 0, 0, 0, 0);
                break;
            case DOWNLOADED:
                downloadSection.setVisibility(View.GONE);
                fabDownload.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_file)); // Placeholder for "install" or "play"
                break;
        }
    }

    private void setupClickListeners() {
        fabDownload.setOnClickListener(v -> {
            if (currentGame == null) return;

            switch (currentGame.getStatus()) {
                case NOT_DOWNLOADED:
                case FAILED:
                case CANCELLED:
                    // In a real app, you'd show a dialog to select files.
                    // For now, we'll just trigger a download for the first installer.
                    // This part needs to be replaced with the download selection dialog from LibraryActivity
                    Toast.makeText(this, "Download selection not implemented yet. Please use the library screen.", Toast.LENGTH_LONG).show();
                    break;
                case DOWNLOADING:
                    startService(DownloadService.createPauseIntent(this, gameId));
                    break;
                case PAUSED:
                    startService(DownloadService.createResumeIntent(this, gameId));
                    break;
                case DOWNLOADED:
                    // TODO: Implement install/play logic
                    Toast.makeText(this, "Install/Play logic not implemented yet.", Toast.LENGTH_SHORT).show();
                    break;
            }
        });
    }

    private void setupBroadcastReceiver() {
        downloadProgressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long receivedGameId = intent.getLongExtra(DownloadService.EXTRA_GAME_ID, -1);
                if (receivedGameId != gameId) return;

                // Reload the game from DB to get the latest status
                loadGameDetails();

                String statusStr = intent.getStringExtra(DownloadService.EXTRA_DOWNLOAD_STATUS);
                if (statusStr == null) {
                    // It's a progress update
                    updateProgress(
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

    private void updateProgress(long downloaded, long total, float speed, long eta, int currentFile, int totalFiles) {
        if (total <= 0) {
            downloadSection.setVisibility(View.GONE);
            return;
        }
        downloadSection.setVisibility(View.VISIBLE);

        int progress = (int) ((downloaded * 100) / total);
        downloadProgressBar.setProgress(progress);

        String progressTextStr;
        if (totalFiles > 1) {
            progressTextStr = String.format("%d%% - File %d/%d (%s / %s)",
                progress, currentFile + 1, totalFiles, Game.formatFileSize(downloaded), Game.formatFileSize(total));
        } else {
            progressTextStr = String.format("%d%% (%s / %s)",
                progress, Game.formatFileSize(downloaded), Game.formatFileSize(total));
        }
        downloadProgressText.setText(progressTextStr);

        if (speed > 0) {
            downloadSpeedText.setText(String.format("%.1f MB/s", speed / (1024 * 1024)));
        } else {
            downloadSpeedText.setText("");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadProgressReceiver, new IntentFilter(DownloadService.ACTION_DOWNLOAD_PROGRESS));
        loadGameDetails(); // Refresh data on resume
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadProgressReceiver);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
