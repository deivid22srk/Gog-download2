package com.termux.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.termux.R;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_folder_picker, container, false);
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
            holder.textView.setText(file.getName());
            if (file.isDirectory()) {
                holder.icon.setImageResource(R.drawable.ic_folder);
            } else {
                holder.icon.setImageResource(R.drawable.ic_file);
            }
            holder.itemView.setOnClickListener(v -> listener.onFileClick(file));
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        static class FolderViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            ImageView icon;

            public FolderViewHolder(@NonNull View itemView) {
                super(itemView);
                textView = itemView.findViewById(R.id.itemName);
                icon = itemView.findViewById(R.id.itemIcon);
            }
        }
    }
}
