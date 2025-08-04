package com.termux.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.termux.R;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class FolderPickerDialogFragment extends DialogFragment {

    public interface FolderPickerListener {
        void onFolderSelected(String path);
    }

    private RecyclerView recyclerView;
    private TextView currentPathTextView;
    private Button selectButton;
    private Button cancelButton;
    private File currentPath;
    private FolderPickerListener listener;

    public static FolderPickerDialogFragment newInstance() {
        return new FolderPickerDialogFragment();
    }

    public void setFolderPickerListener(FolderPickerListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (getResources().getDisplayMetrics().heightPixels * 0.8));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_folder_picker, container, false);
        applyMaterialYouStyling(view);
        return view;
    }

    private void applyMaterialYouStyling(View view) {
        // Apply rounded corners to the main container if needed
        if (view instanceof MaterialCardView) {
            MaterialCardView cardView = (MaterialCardView) view;
            ShapeAppearanceModel shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, 24f)
                    .setTopRightCorner(CornerFamily.ROUNDED, 24f)
                    .build();
            cardView.setShapeAppearanceModel(shapeAppearanceModel);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.folderRecyclerView);
        currentPathTextView = view.findViewById(R.id.currentPath);
        selectButton = view.findViewById(R.id.selectButton);
        cancelButton = view.findViewById(R.id.cancelButton);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        currentPath = Environment.getExternalStorageDirectory();
        listFiles(currentPath);

        selectButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFolderSelected(currentPath.getAbsolutePath());
            }
            dismiss();
        });

        cancelButton.setOnClickListener(v -> dismiss());
    }

    private void listFiles(File path) {
        currentPath = path;
        currentPathTextView.setText(path.getAbsolutePath());

        File[] files = path.listFiles((dir, name) -> {
            File file = new File(dir, name);
            return file.isDirectory() || name.toLowerCase().endsWith(".bin") || name.toLowerCase().endsWith(".exe");
        });

        List<File> fileList = new ArrayList<>();
        if (files != null) {
            fileList.addAll(Arrays.asList(files));
        }
        Collections.sort(fileList, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) {
                return -1;
            } else if (!f1.isDirectory() && f2.isDirectory()) {
                return 1;
            } else {
                return f1.getName().compareToIgnoreCase(f2.getName());
            }
        });

        if (!path.equals(Environment.getExternalStorageDirectory())) {
            fileList.add(0, new File(path, ".."));
        }

        recyclerView.setAdapter(new FolderAdapter(fileList, this::onFileClicked));
    }

    private void onFileClicked(File file) {
        if (file.getName().equals("..")) {
            listFiles(file.getParentFile().getParentFile());
        } else if (file.isDirectory()) {
            listFiles(file);
        }
    }

    private static class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.FolderViewHolder> {

        private final List<File> files;
        private final OnFileClickListener listener;

        public interface OnFileClickListener {
            void onFileClick(File file);
        }

        public FolderAdapter(List<File> files, OnFileClickListener listener) {
            this.files = files;
            this.listener = listener;
        }

        @NonNull
        @Override
        public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_folder, parent, false);
            return new FolderViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
            File file = files.get(position);
            
            // Set file name
            holder.textView.setText(file.getName().equals("..") ? "Voltar" : file.getName());
            
            // Set icon and styling based on file type
            if (file.getName().equals("..")) {
                holder.icon.setImageResource(R.drawable.ic_arrow_back);
                holder.subtitle.setVisibility(View.VISIBLE);
                holder.subtitle.setText("Pasta anterior");
                holder.navigationIcon.setVisibility(View.GONE);
            } else if (file.isDirectory()) {
                holder.icon.setImageResource(R.drawable.ic_folder);
                holder.subtitle.setVisibility(View.VISIBLE);
                
                // Show folder info
                File[] contents = file.listFiles();
                int itemCount = contents != null ? contents.length : 0;
                holder.subtitle.setText(itemCount + (itemCount == 1 ? " item" : " itens"));
                
                holder.navigationIcon.setVisibility(View.VISIBLE);
                holder.navigationIcon.setImageResource(R.drawable.ic_arrow_back);
                holder.navigationIcon.setRotation(180f); // Point right for navigation
            } else {
                holder.icon.setImageResource(R.drawable.ic_file);
                holder.subtitle.setVisibility(View.VISIBLE);
                
                // Show file size and date
                long sizeBytes = file.length();
                String size = formatFileSize(sizeBytes);
                String date = DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(file.lastModified()));
                holder.subtitle.setText(size + " • " + date);
                
                holder.navigationIcon.setVisibility(View.GONE);
            }
            
            holder.itemView.setOnClickListener(v -> listener.onFileClick(file));
        }
        
        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            String pre = "KMGTPE".charAt(exp - 1) + "";
            return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        static class FolderViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            TextView subtitle;
            ImageView icon;
            ImageView navigationIcon;

            public FolderViewHolder(@NonNull View itemView) {
                super(itemView);
                textView = itemView.findViewById(R.id.itemName);
                subtitle = itemView.findViewById(R.id.itemSubtitle);
                icon = itemView.findViewById(R.id.itemIcon);
                navigationIcon = itemView.findViewById(R.id.navigationIcon);
            }
        }
    }
}
