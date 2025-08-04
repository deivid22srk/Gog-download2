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
import java.util.Collections;
import java.util.List;

public class FolderPickerDialogFragment extends DialogFragment {

    private RecyclerView recyclerView;
    private TextView currentPathTextView;
    private Button selectButton;
    private Button cancelButton;

    private File currentPath;
    private FolderPickerAdapter adapter;

    public interface FolderPickerListener {
        void onFolderSelected(String path);
    }

    private FolderPickerListener listener;

    public void setFolderPickerListener(FolderPickerListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return super.onCreateDialog(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_folder_picker, container, false);

        recyclerView = view.findViewById(R.id.folderRecyclerView);
        currentPathTextView = view.findViewById(R.id.currentPath);
        selectButton = view.findViewById(R.id.selectButton);
        cancelButton = view.findViewById(R.id.cancelButton);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        currentPath = Environment.getExternalStorageDirectory();
        updateFileList();

        selectButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFolderSelected(currentPath.getAbsolutePath());
            }
            dismiss();
        });

        cancelButton.setOnClickListener(v -> dismiss());

        return view;
    }

    private void updateFileList() {
        List<File> files = new ArrayList<>();
        File[] fileList = currentPath.listFiles();
        if (fileList != null) {
            for (File file : fileList) {
                if (file.isDirectory()) {
                    files.add(file);
                }
            }
        }
        Collections.sort(files);

        if (currentPath.getParentFile() != null) {
            files.add(0, new File(".."));
        }

        adapter = new FolderPickerAdapter(files, file -> {
            if (file.getName().equals("..")) {
                currentPath = currentPath.getParentFile();
            } else {
                currentPath = file;
            }
            updateFileList();
        });
        recyclerView.setAdapter(adapter);
        currentPathTextView.setText(currentPath.getAbsolutePath());
    }

    private static class FolderPickerAdapter extends RecyclerView.Adapter<FolderPickerAdapter.ViewHolder> {

        private final List<File> files;
        private final OnItemClickListener listener;

        public interface OnItemClickListener {
            void onItemClick(File file);
        }

        public FolderPickerAdapter(List<File> files, OnItemClickListener listener) {
            this.files = files;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(files.get(position), listener);
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView textView;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                textView = itemView.findViewById(android.R.id.text1);
            }

            public void bind(final File file, final OnItemClickListener listener) {
                textView.setText(file.getName());
                itemView.setOnClickListener(v -> listener.onItemClick(file));
            }
        }
    }
}
