package com.termux.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.termux.R;
import com.termux.models.Game;
import com.termux.utils.ImageLoader;

import java.util.ArrayList;
import java.util.List;

public class GamesAdapter extends RecyclerView.Adapter<GamesAdapter.GameViewHolder> {

    private static final String PAYLOAD_PROGRESS_UPDATE = "PAYLOAD_PROGRESS_UPDATE";

    private Context context;
    private List<Game> games;
    private List<Game> filteredGames;
    private OnGameActionListener listener;

    public interface OnGameActionListener {
        void onDownloadGame(Game game);
        void onPauseDownload(Game game);
        void onResumeDownload(Game game);
        void onCancelDownload(Game game);
        void onOpenGame(Game game);
        void onGameClick(Game game);
    }

    public GamesAdapter(Context context) {
        this.context = context;
        this.games = new ArrayList<>();
        this.filteredGames = new ArrayList<>();
    }

    public void setOnGameActionListener(OnGameActionListener listener) {
        this.listener = listener;
    }

    public void setGames(List<Game> games) {
        this.games = new ArrayList<>(games);
        this.filteredGames = new ArrayList<>(games);
        notifyDataSetChanged();
    }

    public void updateGame(Game updatedGame) {
        for (int i = 0; i < games.size(); i++) {
            if (games.get(i).getId() == updatedGame.getId()) {
                games.set(i, updatedGame);
                break;
            }
        }

        for (int i = 0; i < filteredGames.size(); i++) {
            if (filteredGames.get(i).getId() == updatedGame.getId()) {
                filteredGames.set(i, updatedGame);
                notifyItemChanged(i);
                break;
            }
        }
    }

    public void updateGameStatus(long gameId, Game.DownloadStatus status) {
        for (int i = 0; i < filteredGames.size(); i++) {
            Game game = filteredGames.get(i);
            if (game.getId() == gameId) {
                game.setStatus(status);
                notifyItemChanged(i);
                break;
            }
        }
        for (Game game : games) {
            if (game.getId() == gameId) {
                game.setStatus(status);
                break;
            }
        }
    }

    public void updateGameProgress(long gameId, long bytesDownloaded, long totalBytes,
                                   float downloadSpeed, long eta, int currentFileIndex, int totalFiles) {
        for (int i = 0; i < filteredGames.size(); i++) {
            Game game = filteredGames.get(i);
            if (game.getId() == gameId) {
                game.setDownloadProgress(bytesDownloaded);
                game.setTotalSize(totalBytes);
                game.setDownloadSpeed(downloadSpeed);
                game.setEta(eta);
                game.setCurrentFileIndex(currentFileIndex);
                game.setTotalFiles(totalFiles);
                notifyItemChanged(i, PAYLOAD_PROGRESS_UPDATE);
                break;
            }
        }
    }

    public void filter(String query) {
        filteredGames.clear();

        if (query == null || query.isEmpty()) {
            filteredGames.addAll(games);
        } else {
            String lowercaseQuery = query.toLowerCase();
            for (Game game : games) {
                if (game.getTitle().toLowerCase().contains(lowercaseQuery)) {
                    filteredGames.add(game);
                }
            }
        }

        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public GameViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_game_grid, parent, false);
        return new GameViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull GameViewHolder holder, int position) {
        Game game = filteredGames.get(position);
        holder.bind(game);
    }

    @Override
    public void onBindViewHolder(@NonNull GameViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
        } else {
            for (Object payload : payloads) {
                if (payload.equals(PAYLOAD_PROGRESS_UPDATE)) {
                    Game game = filteredGames.get(position);
                    holder.updateProgressViews(game);
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return filteredGames.size();
    }

    public class GameViewHolder extends RecyclerView.ViewHolder {

        private ImageView gameCover;
        private TextView gameTitle;
        private TextView gameStatus;
        private Button moreButton;

        public GameViewHolder(@NonNull View itemView) {
            super(itemView);

            gameCover = itemView.findViewById(R.id.gameCover);
            gameTitle = itemView.findViewById(R.id.gameTitle);
            gameStatus = itemView.findViewById(R.id.gameStatus);
            moreButton = itemView.findViewById(R.id.moreButton);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onGameClick(filteredGames.get(position));
                }
            });

            moreButton.setOnClickListener(this::showPopupMenu);
        }

        public void bind(Game game) {
            gameTitle.setText(game.getTitle());
            updateStatus(game);

            if (game.getCoverImage() != null && !game.getCoverImage().isEmpty()) {
                ImageLoader.loadImage(context, game.getCoverImage(), gameCover);
            } else {
                gameCover.setImageResource(R.drawable.ic_image);
            }
        }
        
        public void updateProgressViews(Game game) {
            updateStatus(game);
        }

        private void updateStatus(Game game) {
            switch (game.getStatus()) {
                case NOT_DOWNLOADED:
                    gameStatus.setText("Not Downloaded");
                    break;
                case PAUSED:
                    gameStatus.setText("Paused");
                    break;
                case DOWNLOADING:
                    gameStatus.setText(String.format("Downloading... %d%%", game.getDownloadProgressPercent()));
                    break;
                case DOWNLOADED:
                    gameStatus.setText("Downloaded");
                    break;
                case FAILED:
                    gameStatus.setText("Failed");
                    break;
            }
        }

        private void showPopupMenu(View view) {
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION || listener == null) return;
            Game game = filteredGames.get(position);

            PopupMenu popup = new PopupMenu(context, view);
            popup.inflate(R.menu.menu_library); // Create a menu for this
            
            // Customize menu based on game status
            // For now, we show all options
            
            popup.setOnMenuItemClickListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.action_download) {
                    listener.onDownloadGame(game);
                    return true;
                } else if (itemId == R.id.action_pause) {
                    listener.onPauseDownload(game);
                    return true;
                } else if (itemId == R.id.action_resume) {
                    listener.onResumeDownload(game);
                    return true;
                } else if (itemId == R.id.action_cancel) {
                    listener.onCancelDownload(game);
                    return true;
                } else if (itemId == R.id.action_open) {
                    listener.onOpenGame(game);
                    return true;
                }
                return false;
            });
            popup.show();
        }
    }
}