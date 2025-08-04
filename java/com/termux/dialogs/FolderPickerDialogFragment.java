package com.termux.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
        listFolders(currentPath);

        selectButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFolderSelected(currentPath.getAbsolutePath());
            }
            dismiss();
        });

        cancelButton.setOnClickListener(v -> dismiss());
    }

    private void listFolders(File path) {
        currentPath = path;
        currentPathTextView.setText(path.getAbsolutePath());

        File[] files = path.listFiles();
        List<File> folderList = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    folderList.add(file);
                }
            }
        }
        Collections.sort(folderList, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));

        if (!path.equals(Environment.getExternalStorageDirectory())) {
            folderList.add(0, new File(path, ".."));
        }

        recyclerView.setAdapter(new FolderAdapter(folderList, this::onFolderClicked));
    }

    private void onFolderClicked(File folder) {
        if (folder.getName().equals("..")) {
            listFolders(folder.getParentFile().getParentFile());
        } else {
            listFolders(folder);
        }
    }

    private static class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.FolderViewHolder> {

        private final List<File> folders;
        private final OnFolderClickListener listener;

        public interface OnFolderClickListener {
            void onFolderClick(File file);
        }

        public FolderAdapter(List<File> folders, OnFolderClickListener listener) {
            this.folders = folders;
            this.listener = listener;
        }

        @NonNull
        @Override
        public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new FolderViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
            File folder = folders.get(position);
            holder.textView.setText(folder.getName());
            holder.itemView.setOnClickListener(v -> listener.onFolderClick(folder));
        }

        @Override
        public int getItemCount() {
            return folders.size();
        }

        static class FolderViewHolder extends RecyclerView.ViewHolder {
            TextView textView;

            public FolderViewHolder(@NonNull View itemView) {
                super(itemView);
                textView = itemView.findViewById(android.R.id.text1);
            }
        }
    }
}
