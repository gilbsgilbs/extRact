package ca.pkay.rcloneexplorer.Fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.FileProvider;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import com.leinardi.android.speeddial.SpeedDialActionItem;
import com.leinardi.android.speeddial.SpeedDialOverlayLayout;
import com.leinardi.android.speeddial.SpeedDialView;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import ca.pkay.rcloneexplorer.BreadcrumbView;
import ca.pkay.rcloneexplorer.Dialogs.InputDialog;
import ca.pkay.rcloneexplorer.Dialogs.LoadingDialog;
import ca.pkay.rcloneexplorer.Dialogs.SortDialog;
import ca.pkay.rcloneexplorer.FileComparators;
import ca.pkay.rcloneexplorer.Dialogs.FilePropertiesDialog;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.MainActivity;
import ca.pkay.rcloneexplorer.Dialogs.OpenAsDialog;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.FileExplorerRecyclerViewAdapter;
import ca.pkay.rcloneexplorer.Services.DownloadService;
import ca.pkay.rcloneexplorer.Services.StreamingService;
import ca.pkay.rcloneexplorer.Services.UploadService;
import es.dmoral.toasty.Toasty;
import jp.wasabeef.recyclerview.animators.LandingAnimator;
import ru.bartwell.exfilepicker.ExFilePicker;
import ru.bartwell.exfilepicker.data.ExFilePickerResult;

public class FileExplorerFragment extends Fragment implements   FileExplorerRecyclerViewAdapter.OnClickListener,
                                                                SwipeRefreshLayout.OnRefreshListener,
                                                                BreadcrumbView.OnClickListener {

    private static final String ARG_REMOTE = "remote_param";
    private static final String ARG_REMOTE_TYPE = "remote_type_param";
    private static final String SHARED_PREFS_SORT_ORDER = "ca.pkay.rcexplorer.sort_order";
    private static final int EX_FILE_PICKER_UPLOAD_RESULT = 186;
    private static final int EX_FILE_PICKER_DOWNLOAD_RESULT = 204;
    private static final int STREAMING_INTENT_RESULT = 468;
    private String originalToolbarTitle;
    private List<FileItem> directoryContent;
    private Stack<String> pathStack;
    private Map<String, List<FileItem>> directoryCache;
    private List<FileItem> moveList;
    private BreadcrumbView breadcrumbView;
    private Rclone rclone;
    private String remote;
    private String remoteType;
    private String path;
    private FileExplorerRecyclerViewAdapter recyclerViewAdapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private AsyncTask fetchDirectoryTask;
    private Boolean isRunning;
    private int sortOrder;
    private Boolean isInMoveMode;
    private SpeedDialView fab;
    private MenuItem menuPropertiesAction;
    private MenuItem menuOpenAsAction;
    private Boolean isDarkTheme;
    //private NetworkStateReceiver networkStateReceiver;
    private Context context;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public FileExplorerFragment() {
    }

    @SuppressWarnings("unused")
    public static FileExplorerFragment newInstance(String remote, String remoteType) {
        FileExplorerFragment fragment = new FileExplorerFragment();
        Bundle args = new Bundle();
        args.putString(ARG_REMOTE, remote);
        args.putString(ARG_REMOTE_TYPE, remoteType);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            remote = getArguments().getString(ARG_REMOTE);
            remoteType = getArguments().getString(ARG_REMOTE_TYPE);
            path = "//" + getArguments().getString(ARG_REMOTE);
        }
        if (getContext() == null) {
            return;
        }
        originalToolbarTitle = ((FragmentActivity) context).getTitle().toString();
        ((FragmentActivity) context).setTitle(remoteType);
        setHasOptionsMenu(true);

        SharedPreferences sharedPreferences = context.getSharedPreferences(MainActivity.SHARED_PREFS_TAG, Context.MODE_PRIVATE);
        sortOrder = sharedPreferences.getInt(SHARED_PREFS_SORT_ORDER, SortDialog.ALPHA_ASCENDING);

        //networkStateReceiver = ((MainActivity)context).getNetworkStateReceiver();
        rclone = new Rclone(getContext());
        pathStack = new Stack<>();
        directoryCache = new HashMap<>();
        fetchDirectoryTask = new FetchDirectoryContent().execute();

        isInMoveMode = false;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_file_explorer_list, container, false);

        swipeRefreshLayout = view.findViewById(R.id.file_explorer_srl);
        swipeRefreshLayout.setOnRefreshListener(this);
        if (directoryContent != null && fetchDirectoryTask != null) {
            swipeRefreshLayout.setRefreshing(false);
        } else {
            swipeRefreshLayout.setRefreshing(true);
        }

        Context context = view.getContext();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        isDarkTheme = sharedPreferences.getBoolean(getString(R.string.pref_key_dark_theme), false);

        RecyclerView recyclerView = view.findViewById(R.id.file_explorer_list);
        recyclerView.setItemAnimator(new LandingAnimator());
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerViewAdapter = new FileExplorerRecyclerViewAdapter(context, directoryContent, view.findViewById(R.id.empty_folder_view), this);
        recyclerView.setAdapter(recyclerViewAdapter);

        fab = view.findViewById(R.id.fab);
        fab.setSpeedDialOverlayLayout((SpeedDialOverlayLayout)view.findViewById(R.id.fab_overlay));
        fab.setMainFabOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (fab.isFabMenuOpen()) {
                    fab.closeOptionsMenu();
                }
            }
        });
        fab.addFabOptionItem(new SpeedDialActionItem.Builder(R.id.fab_upload, R.drawable.ic_file_upload)
                .setLabel(getString(R.string.fab_upload_files))
                .create());
        fab.addFabOptionItem(new SpeedDialActionItem.Builder(R.id.fab_add_folder, R.drawable.ic_create_new_folder)
                .setLabel(getString(R.string.fab_new_folder))
                .create());
        setFabClickListeners();

        breadcrumbView = ((FragmentActivity) context).findViewById(R.id.breadcrumb_view);
        breadcrumbView.setOnClickListener(this);
        breadcrumbView.setVisibility(View.VISIBLE);
        breadcrumbView.addCrumb(remote, "//" + remote);

        final TypedValue accentColorValue = new TypedValue ();
        context.getTheme ().resolveAttribute (R.attr.colorAccent, accentColorValue, true);
        view.findViewById(R.id.bottom_bar).setBackgroundColor(accentColorValue.data);
        view.findViewById(R.id.move_bar).setBackgroundColor(accentColorValue.data);

        setBottomBarClickListeners(view);

        isRunning = true;
        return view;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EX_FILE_PICKER_UPLOAD_RESULT) {
            ExFilePickerResult result = ExFilePickerResult.getFromIntent(data);
            if (result != null && result.getCount() > 0) {
                ArrayList<String> uploadList = new ArrayList<>();
                for (String fileName : result.getNames()) {
                    uploadList.add(result.getPath() + fileName);
                }
                Intent intent = new Intent(getContext(), UploadService.class);
                intent.putStringArrayListExtra(UploadService.LOCAL_PATH_ARG, uploadList);
                intent.putExtra(UploadService.UPLOAD_PATH_ARG, path);
                intent.putExtra(UploadService.REMOTE_ARG, remote);
                context.startService(intent);
            }
        } else if (requestCode == EX_FILE_PICKER_DOWNLOAD_RESULT) {
            ExFilePickerResult result = ExFilePickerResult.getFromIntent(data);
            if (result != null && result.getCount() > 0) {
                final ArrayList<FileItem> downloadList = new ArrayList<>(recyclerViewAdapter.getSelectedItems());
                recyclerViewAdapter.cancelSelection();
                String selectedPath = result.getPath() + result.getNames().get(0);
                Intent intent = new Intent(getContext(), DownloadService.class);
                intent.putParcelableArrayListExtra(DownloadService.DOWNLOAD_LIST_ARG, downloadList);
                intent.putExtra(DownloadService.DOWNLOAD_PATH_ARG, selectedPath);
                intent.putExtra(DownloadService.REMOTE_ARG, remote);
                context.startService(intent);
            }
        } else if (requestCode == STREAMING_INTENT_RESULT) {
            Intent serveIntent = new Intent(getContext(), StreamingService.class);
            context.stopService(serveIntent);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.file_explorer, menu);
        menuPropertiesAction = menu.findItem(R.id.action_file_properties);
        menuOpenAsAction = menu.findItem(R.id.action_open_as);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case R.id.action_sort:
                showSortMenu();
                return true;
            case R.id.action_select_all:
                recyclerViewAdapter.toggleSelectAll();
                return true;
            case R.id.action_http_serve:
                Intent intent = new Intent(getContext(), StreamingService.class);
                intent.putExtra(StreamingService.SERVE_PATH_ARG, path);
                intent.putExtra(StreamingService.REMOTE_ARG, remote);
                intent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, true);
                context.startService(intent);
                return true;
            case R.id.action_file_properties:
                showFileProperties();
                return true;
            case R.id.action_open_as:
                showOpenAsDialog();
                return true;
            default:
                    return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Swipe to refresh
     */
    @Override
    public void onRefresh() {
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        fetchDirectoryTask = new FetchDirectoryContent().execute();
    }

    private void showOpenAsDialog() {
        OpenAsDialog openAsDialog = new OpenAsDialog();
        openAsDialog
                .setContext(context)
                .setDarkTheme(isDarkTheme)
                .setOnClickListener(new OpenAsDialog.OnClickListener() {
                    @Override
                    public void onClickText() {
                        if (recyclerViewAdapter.getNumberOfSelectedItems() == 1) {
                            FileItem fileItem = recyclerViewAdapter.getSelectedItems().get(0);
                            new DownloadAndOpen(DownloadAndOpen.OPEN_AS_TEXT).execute(fileItem);
                        }
                    }
                    @Override
                    public void onClickAudio() {
                        if (recyclerViewAdapter.getNumberOfSelectedItems() == 1) {
                            new StreamTask(StreamTask.OPEN_AS_AUDIO).execute(recyclerViewAdapter.getSelectedItems().get(0));
                        }
                    }
                    @Override
                    public void onClickVideo() {
                        if (recyclerViewAdapter.getNumberOfSelectedItems() == 1) {
                            new StreamTask(StreamTask.OPEN_AS_VIDEO).execute(recyclerViewAdapter.getSelectedItems().get(0));
                        }
                    }
                    @Override
                    public void onClickImage() {
                        FileItem fileItem = recyclerViewAdapter.getSelectedItems().get(0);
                        new DownloadAndOpen(DownloadAndOpen.OPEN_AS_IMAGE).execute(fileItem);
                    }
                });
        if (getFragmentManager() != null) {
            openAsDialog.show(getFragmentManager(), "open as");
        }
    }

    private void showFileProperties() {
        if (!recyclerViewAdapter.isInSelectMode() || recyclerViewAdapter.getNumberOfSelectedItems() > 2) {
            return;
        }
        FileItem fileItem = recyclerViewAdapter.getSelectedItems().get(0);
        FilePropertiesDialog filePropertiesDialog = new FilePropertiesDialog()
                .withContext(context)
                .setFile(fileItem)
                .setRclone(rclone)
                .setRemote(remote)
                .setDarkTheme(isDarkTheme);
        if (remoteType.equals("crypt")) {
            filePropertiesDialog.withHashCalculations(false);
        }
        if (getFragmentManager() != null) {
            filePropertiesDialog.show(getFragmentManager(), "file properties");
        }
    }

    private void setFabClickListeners() {
        fab.setOptionFabSelectedListener(new SpeedDialView.OnOptionFabSelectedListener() {
            @Override
            public void onOptionFabSelected(SpeedDialActionItem speedDialActionItem) {
                fab.closeOptionsMenu();
                switch (speedDialActionItem.getId()) {
                    case R.id.fab_add_folder:
                        onCreateNewDirectory();
                        break;
                    case R.id.fab_upload:
                        onUploadFiles();
                        break;
                }
            }
        });
    }

    private void setBottomBarClickListeners(final View view) {
        view.findViewById(R.id.file_download).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                downloadClicked();
            }
        });

        view.findViewById(R.id.file_move).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                moveClicked();
            }
        });

        view.findViewById(R.id.file_rename).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renameClicked();
            }
        });

        view.findViewById(R.id.file_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                deleteClicked();
            }
        });

        view.findViewById(R.id.cancel_move).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelMoveClicked();
            }
        });

        view.findViewById(R.id.select_move).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveLocationSelected();
            }
        });

        view.findViewById(R.id.new_folder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCreateNewDirectory();
            }
        });
    }

    private void cancelMoveClicked() {
        ((FragmentActivity) context).setTitle(remoteType);
        recyclerViewAdapter.setMoveMode(false);
        isInMoveMode = false;
        hideMoveBar();
        fab.show();
        fab.setVisibility(View.VISIBLE);
        ((FragmentActivity) context).findViewById(R.id.action_select_all).setVisibility(View.VISIBLE);
        recyclerViewAdapter.refreshData();
    }

    private void moveLocationSelected() {
        ((FragmentActivity) context).setTitle(remoteType);
        hideMoveBar();
        fab.show();
        fab.setVisibility(View.VISIBLE);
        ((FragmentActivity) context).findViewById(R.id.action_select_all).setVisibility(View.VISIBLE);
        recyclerViewAdapter.setMoveMode(false);
        isInMoveMode = false;
        String oldPath = moveList.get(0).getPath();
        int index = oldPath.lastIndexOf(moveList.get(0).getName());
        if (index > 0) {
            directoryCache.remove(moveList.get(0).getPath().substring(0, index - 1));
        } else {
            directoryCache.remove("//" + remote);
        }
        new MoveTask().execute();
    }

    private void showSortMenu() {
        SortDialog sortDialog = new SortDialog();
        sortDialog.withContext(context)
                .setTitle(R.string.sort)
                .setNegativeButton(R.string.cancel)
                .setPositiveButton(R.string.ok)
                .setListener(new SortDialog.OnClickListener() {
                    @Override
                    public void onPositiveButtonClick(int sortById, int sortOrderId) {
                        if (directoryContent != null && !directoryContent.isEmpty()) {
                            sortSelected(sortById, sortOrderId);
                        }
                    }
                })
                .setSortOrder(sortOrder)
                .setDarkTheme(isDarkTheme);
        if (getFragmentManager() != null) {
                sortDialog.show(getFragmentManager(), "sort dialog");
        }
    }

    private void sortSelected(int sortById, int sortOrderId) {
        switch (sortById) {
            case R.id.radio_sort_name:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortAlphaAscending());
                    sortOrder = SortDialog.ALPHA_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortAlphaDescending());
                    sortOrder = SortDialog.ALPHA_DESCENDING;
                }
                break;
            case R.id.radio_sort_date:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortModTimeAscending());
                    sortOrder = SortDialog.MOD_TIME_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortModTimeDescending());
                    sortOrder = SortDialog.MOD_TIME_DESCENDING;
                }
                break;
            case R.id.radio_sort_size:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortSizeAscending());
                    sortOrder = SortDialog.SIZE_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortSizeDescending());
                    sortOrder = SortDialog.SIZE_DESCENDING;
                }
                break;
        }
        recyclerViewAdapter.updateData(directoryContent);
        if (sortOrder > 0) {
            SharedPreferences sharedPreferences = context.getSharedPreferences(MainActivity.SHARED_PREFS_TAG, Context.MODE_PRIVATE);
            sharedPreferences.edit().putInt(SHARED_PREFS_SORT_ORDER, sortOrder).apply();
        }
    }

    private void sortDirectory() {
        switch (sortOrder) {
            case SortDialog.MOD_TIME_DESCENDING:
                Collections.sort(directoryContent, new FileComparators.SortModTimeDescending());
                sortOrder = SortDialog.MOD_TIME_ASCENDING;
                break;
            case SortDialog.MOD_TIME_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortModTimeAscending());
                sortOrder = SortDialog.MOD_TIME_DESCENDING;
                break;
            case SortDialog.SIZE_DESCENDING:
                Collections.sort(directoryContent, new FileComparators.SortSizeDescending());
                sortOrder = SortDialog.SIZE_ASCENDING;
                break;
            case SortDialog.SIZE_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortSizeAscending());
                sortOrder = SortDialog.SIZE_DESCENDING;
                break;
            case SortDialog.ALPHA_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortAlphaAscending());
                sortOrder = SortDialog.ALPHA_ASCENDING;
                break;
            case SortDialog.ALPHA_DESCENDING:
            default:
                Collections.sort(directoryContent, new FileComparators.SortAlphaDescending());
                sortOrder = SortDialog.ALPHA_DESCENDING;
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        fetchDirectoryTask.cancel(true);
        breadcrumbView.clearCrumbs();
        breadcrumbView.setVisibility(View.GONE);
        ((FragmentActivity) context).setTitle(originalToolbarTitle);
        isRunning = false;
        context = null;
    }

    public boolean onBackButtonPressed() {
        if (recyclerViewAdapter.isInSelectMode()) {
            recyclerViewAdapter.cancelSelection();
            return true;
        } else if (fab.isFabMenuOpen()) {
            fab.closeOptionsMenu();
            return true;
        } else if (pathStack.isEmpty() || directoryCache.isEmpty()) {
            return false;
        }
        swipeRefreshLayout.setRefreshing(false);
        fetchDirectoryTask.cancel(true);
        breadcrumbView.removeLastCrumb();
        path = pathStack.pop();
        recyclerViewAdapter.clear();
        if (directoryCache.containsKey(path)) {
            directoryContent = directoryCache.get(path);
            recyclerViewAdapter.newData(directoryContent);
        } else {
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
        return true;
    }

    @Override
    public void onFileClicked(FileItem fileItem) {
        String extension = fileItem.getName().substring(fileItem.getName().lastIndexOf(".") + 1);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (type != null && (type.startsWith("video/") || type.startsWith("audio/"))) {
            // stream video or audio
            new StreamTask().execute(fileItem);
        } else {
            // download and open
            new DownloadAndOpen().execute(fileItem);
        }
    }

    @Override
    public void onDirectoryClicked(FileItem fileItem) {
        breadcrumbView.addCrumb(fileItem.getName(), fileItem.getPath());
        swipeRefreshLayout.setRefreshing(true);
        pathStack.push(path);
        directoryCache.put(path, new ArrayList<>(directoryContent));

        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        if (directoryCache.containsKey(fileItem.getPath())) {
            path = fileItem.getPath();
            directoryContent = directoryCache.get(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryContent);
            swipeRefreshLayout.setRefreshing(false);
            return;
        }
        path = fileItem.getPath();
        recyclerViewAdapter.clear();
        directoryContent.clear();
        fetchDirectoryTask = new FetchDirectoryContent().execute();

    }

    @Override
    public void onFilesSelected(boolean longClick) {
        int numOfSelected = recyclerViewAdapter.getNumberOfSelectedItems();

        if (numOfSelected > 0) { // something is selected
            ((FragmentActivity) context).setTitle(numOfSelected + " " + getString(R.string.selected));
            showBottomBar();
            fab.hide();
            fab.setVisibility(View.INVISIBLE);
            if (numOfSelected > 1) {
                ((FragmentActivity) context).findViewById(R.id.file_rename).setAlpha(.5f);
                ((FragmentActivity) context).findViewById(R.id.file_rename).setClickable(false);
                menuPropertiesAction.setVisible(false);
                menuOpenAsAction.setVisible(false);
            } else {
                ((FragmentActivity) context).findViewById(R.id.file_rename).setAlpha(1f);
                ((FragmentActivity) context).findViewById(R.id.file_rename).setClickable(true);
                menuPropertiesAction.setVisible(true);
                if (recyclerViewAdapter.getSelectedItems().get(0).isDir()) {
                    menuOpenAsAction.setVisible(false);
                } else {
                    menuOpenAsAction.setVisible(true);
                }
            }
        } else if (!isInMoveMode) {
            ((FragmentActivity) context).setTitle(remoteType);
            menuPropertiesAction.setVisible(false);
            menuOpenAsAction.setVisible(false);
            hideBottomBar();
            fab.show();
            fab.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBreadCrumbClicked(String path) {
        if (fab.isFabMenuOpen()) {
            fab.closeOptionsMenu();
        }
        if (this.path.equals(path)) {
            return;
        }
        swipeRefreshLayout.setRefreshing(false);
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        this.path = path;
        //noinspection StatementWithEmptyBody
        while (!pathStack.pop().equals(path)) {
            // pop stack until we find path
        }
        breadcrumbView.removeCrumbsUpTo(path);
        recyclerViewAdapter.clear();
        if (directoryCache.containsKey(path)) {
            directoryContent = directoryCache.get(path);
            recyclerViewAdapter.newData(directoryContent);
        } else {
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    private void showBottomBar() {
        View bottomBar = ((FragmentActivity) context).findViewById(R.id.bottom_bar);
        if (bottomBar.getVisibility() == View.VISIBLE) {
            return;
        }
        bottomBar.setVisibility(View.VISIBLE);
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_in_animation);
        bottomBar.startAnimation(animation);
    }

    private void hideBottomBar() {
        View bottomBar = ((FragmentActivity) context).findViewById(R.id.bottom_bar);
        if (bottomBar.getVisibility() != View.VISIBLE) {
            return;
        }
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_out_animation);
        bottomBar.setAnimation(animation);
        bottomBar.setVisibility(View.GONE);
    }

    private void hideMoveBar() {
        View moveBar = ((FragmentActivity) context).findViewById(R.id.move_bar);
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_out_animation);
        moveBar.setAnimation(animation);
        moveBar.setVisibility(View.GONE);
    }

    private void deleteClicked() {
        if (!recyclerViewAdapter.isInSelectMode()) {
            return;
        }

        final List<FileItem> deleteList = new ArrayList<>(recyclerViewAdapter.getSelectedItems());
        String title = "Delete " + deleteList.size();
        String content = (deleteList.size() == 1) ? deleteList.get(0).getName() + " will be deleted" : "";
        title += (deleteList.size() > 1) ? " items?" : " item?";
        AlertDialog.Builder builder;
        if (isDarkTheme) {
            builder = new AlertDialog.Builder(context, R.style.DarkDialogTheme);
        } else {
            builder = new AlertDialog.Builder(context);
        }
        builder
                .setTitle(title)
                .setNegativeButton(getResources().getString(R.string.cancel), null)
                .setPositiveButton(getResources().getString(R.string.delete), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        recyclerViewAdapter.cancelSelection();
                        new DeleteFilesTask().execute(deleteList);
                    }
                });
        if (!content.trim().isEmpty()) {
            builder.setMessage(content);
        }
        builder.create().show();
    }

    private void renameClicked() {
        if (!recyclerViewAdapter.isInSelectMode() || recyclerViewAdapter.getNumberOfSelectedItems() > 1) {
            return;
        }

        List<FileItem> list = recyclerViewAdapter.getSelectedItems();
        final FileItem renameItem = list.get(0);
        if (getFragmentManager() != null) {
            new InputDialog()
                    .setContext(context)
                    .setTitle(R.string.rename_file)
                    .setMessage(R.string.type_new_file_name)
                    .setNegativeButton(R.string.cancel)
                    .setPositiveButton(R.string.okay_confirmation)
                    .setFilledText(renameItem.getName())
                    .setDarkTheme(isDarkTheme)
                    .setOnPositiveListener(new InputDialog.OnPositive() {
                        @Override
                        public void onPositive(String input) {
                            if (renameItem.getName().equals(input)) {
                                return;
                            }
                            recyclerViewAdapter.cancelSelection();
                            String newFilePath;
                            if (path.equals("//" + remote)) {
                                newFilePath = input;
                            } else {
                                newFilePath = path + "/" + input;
                            }
                            new RenameFileTask().execute(renameItem.getPath(), newFilePath);
                        }
                    })
                    .show(getFragmentManager(), "input dialog");
        }
    }

    private void downloadClicked() {
        if (!recyclerViewAdapter.isInSelectMode()) {
            return;
        }
        ExFilePicker exFilePicker = new ExFilePicker();
        exFilePicker.setUseFirstItemAsUpEnabled(true);
        exFilePicker.setChoiceType(ExFilePicker.ChoiceType.DIRECTORIES);
        exFilePicker.setCanChooseOnlyOneItem(true);
        exFilePicker.setQuitButtonEnabled(true);
        exFilePicker.start(this, EX_FILE_PICKER_DOWNLOAD_RESULT);
    }

    private void moveClicked() {
        if (recyclerViewAdapter.getNumberOfSelectedItems() < 1) {
            return;
        }
        moveList = new ArrayList<>(recyclerViewAdapter.getSelectedItems());
        recyclerViewAdapter.cancelSelection();
        recyclerViewAdapter.setMoveMode(true);
        isInMoveMode = true;
        ((FragmentActivity) context).setTitle(getString(R.string.select_destination));
        ((FragmentActivity) context).findViewById(R.id.move_bar).setVisibility(View.VISIBLE);
        ((FragmentActivity) context).findViewById(R.id.action_select_all).setVisibility(View.GONE);
        fab.hide();
        fab.setVisibility(View.INVISIBLE);
    }

    private void onCreateNewDirectory() {
        if (getFragmentManager() != null) {
            new InputDialog()
                    .setContext(context)
                    .setTitle(R.string.create_new_folder)
                    .setMessage(R.string.type_new_folder_name)
                    .setNegativeButton(R.string.cancel)
                    .setPositiveButton(R.string.okay_confirmation)
                    .setOnPositiveListener(new InputDialog.OnPositive() {
                        @Override
                        public void onPositive(String input) {
                            if (input.trim().length() == 0) {
                                return;
                            }
                            String newDir;
                            if (path.equals("//" + remote)) {
                                newDir = input;
                            } else {
                                newDir = path + "/" + input;
                            }
                            new MakeDirectoryTask().execute(newDir);
                        }
                    })
                    .show(getFragmentManager(), "input dialog");
        }
    }

    private void onUploadFiles() {
        ExFilePicker exFilePicker = new ExFilePicker();
        exFilePicker.setUseFirstItemAsUpEnabled(true);
        exFilePicker.setChoiceType(ExFilePicker.ChoiceType.ALL);
        exFilePicker.setQuitButtonEnabled(true);
        exFilePicker.start(this, EX_FILE_PICKER_UPLOAD_RESULT);
    }

    /***********************************************************************************************
     * AsyncTask classes
     ***********************************************************************************************/
    @SuppressLint("StaticFieldLeak")
    private class FetchDirectoryContent extends AsyncTask<Void, Void, List<FileItem>> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            if (null != swipeRefreshLayout) {
                swipeRefreshLayout.setRefreshing(true);
            }
        }

        @Override
        protected List<FileItem> doInBackground(Void... voids) {
            List<FileItem> fileItemList;
            fileItemList = rclone.getDirectoryContent(remote, path);
            return fileItemList;
        }

        @Override
        protected void onPostExecute(List<FileItem> fileItems) {
            super.onPostExecute(fileItems);
            if (fileItems == null) {
                Toasty.error(context, getString(R.string.error_getting_dir_content), Toast.LENGTH_SHORT, true).show();
                fileItems = new ArrayList<>();
            }
            directoryContent = fileItems;
            sortDirectory();
            directoryCache.put(path, new ArrayList<>(directoryContent));

            if (recyclerViewAdapter != null) {
                recyclerViewAdapter.newData(fileItems);
            }

            if (null != swipeRefreshLayout) {
                swipeRefreshLayout.setRefreshing(false);
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            if (null != swipeRefreshLayout) {
                swipeRefreshLayout.setRefreshing(false);
            }
        }
    }
    
    @SuppressLint("StaticFieldLeak")
    private class RenameFileTask extends AsyncTask<String, Void, Boolean> {

        private String pathWhenTaskStarted;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pathWhenTaskStarted = path;
            swipeRefreshLayout.setRefreshing(true);
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            String oldFileName = strings[0];
            String newFileName = strings[1];

            return rclone.moveTo(remote, oldFileName, newFileName);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!isRunning) {
                return;
            }
            if (result) {
                Toasty.success(context, getString(R.string.file_renamed_success), Toast.LENGTH_SHORT, true).show();
            } else {
                Toasty.error(context, getString(R.string.error_moving_file), Toast.LENGTH_SHORT, true).show();

            }
            if (!pathWhenTaskStarted.equals(path)) {
                directoryCache.remove(pathWhenTaskStarted);
                return;
            }
            if (null != fetchDirectoryTask) {
                fetchDirectoryTask.cancel(true);
            }
            swipeRefreshLayout.setRefreshing(false);

            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class MoveTask extends AsyncTask<Void, Void, Boolean> {

        private String pathWhenTaskStarted;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pathWhenTaskStarted = path;
            swipeRefreshLayout.setRefreshing(true);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            return rclone.moveTo(remote, moveList, path);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!isRunning) {
                return;
            }
            if (result) {
                Toasty.success(context, getString(R.string.files_moved_success), Toast.LENGTH_SHORT, true).show();
            } else {
                Toasty.error(context, getString(R.string.error_moving_file), Toast.LENGTH_SHORT, true).show();
            }
            if (!pathWhenTaskStarted.equals(path)) {
                directoryCache.remove(pathWhenTaskStarted);
                return;
            }
            if (null != fetchDirectoryTask) {
                fetchDirectoryTask.cancel(true);
            }
            swipeRefreshLayout.setRefreshing(false);

            fetchDirectoryTask = new FetchDirectoryContent().execute();
            moveList.clear();
        }
    }
    
    @SuppressLint("StaticFieldLeak")
    private class DeleteFilesTask extends AsyncTask<List, Void, Boolean> {

        private String pathWhenTaskStarted;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pathWhenTaskStarted = path;
            swipeRefreshLayout.setRefreshing(true);

        }

        @Override
        protected Boolean doInBackground(List[] lists) {
            List<FileItem> list = lists[0];
            return rclone.deleteItems(remote, list);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!isRunning) {
                return;
            }
            if (result) {
                Toasty.success(context, getString(R.string.files_deleted_success), Toast.LENGTH_SHORT, true).show();
            } else {
                Toasty.error(context, getString(R.string.error_deleting_file), Toast.LENGTH_SHORT, true).show();
            }
            if (!pathWhenTaskStarted.equals(path)) {
                directoryCache.remove(pathWhenTaskStarted);
                return;
            }
            if (null != fetchDirectoryTask) {
                fetchDirectoryTask.cancel(true);
            }
            swipeRefreshLayout.setRefreshing(false);

            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class MakeDirectoryTask extends AsyncTask<String, Void, Boolean> {

        private String pathWhenTaskStarted;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pathWhenTaskStarted = path;
            swipeRefreshLayout.setRefreshing(true);
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            String newDir = strings[0];
            return rclone.makeDirectory(remote, newDir);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!isRunning) {
                return;
            }
            if (result) {
                Toasty.success(context, getString(R.string.make_directory_success), Toast.LENGTH_SHORT, true).show();
            } else {
                Toasty.error(context, getString(R.string.error_mkdir), Toast.LENGTH_SHORT, true).show();
            }
            if (!pathWhenTaskStarted.equals(path)) {
                directoryCache.remove(pathWhenTaskStarted);
                return;
            }
            swipeRefreshLayout.setRefreshing(false);
            if (null != fetchDirectoryTask) {
                fetchDirectoryTask.cancel(true);
            }
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class DownloadAndOpen extends AsyncTask<FileItem, Void, Boolean> {

        public static final int OPEN_AS_TEXT = 1;
        public static final int OPEN_AS_IMAGE = 2;
        private int openAs;
        private LoadingDialog loadingDialog;
        private String fileLocation;
        private Process process;

        DownloadAndOpen() {
            this(-1);
        }

        DownloadAndOpen(int openAs) {
            this.openAs = openAs;
        }

        private void cancelProcess() {
            process.destroy();
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            loadingDialog = new LoadingDialog()
                    .setContext(context)
                    .setCanCancel(false)
                    .setDarkTheme(isDarkTheme)
                    .setTitle(getString(R.string.loading_file))
                    .setNegativeButton(getResources().getString(R.string.cancel))
                    .setOnNegativeListener(new LoadingDialog.OnNegative() {
                @Override
                public void onNegative() {
                    cancelProcess();
                    cancel(true);
                }
            });
            if (getFragmentManager() != null) {
                loadingDialog.show(getFragmentManager(), "loading dialog");
            }
        }

        @Override
        protected Boolean doInBackground(FileItem... fileItems) {
            FileItem fileItem = fileItems[0];
            File file = context.getExternalCacheDir();
            String saveLocation;
            if (file != null) {
                saveLocation = file.getAbsolutePath();
            } else {
                return false;
            }

            fileLocation = saveLocation + "/" + fileItem.getName();

            process = rclone.downloadItems(remote, fileItem, saveLocation);

            try {
                process.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return false;
            }

            if (process.exitValue() == 0) {
                File savedFile = new File(fileLocation);
                savedFile.setReadOnly();
            }

            return process.exitValue() == 0;
        }

        @Override
        protected void onPostExecute(Boolean status) {
            super.onPostExecute(status);
            loadingDialog.dismiss();
            if (!status) {
                return;
            }
            Uri sharedFileUri = FileProvider.getUriForFile(context, "ca.pkay.rcloneexplorer.fileprovider", new File(fileLocation));
            Intent intent = new Intent(Intent.ACTION_VIEW, sharedFileUri);

            if (openAs == OPEN_AS_TEXT) {
                intent.setDataAndType(sharedFileUri,"text/*");
            } else if (openAs == OPEN_AS_IMAGE) {
                intent.setDataAndType(sharedFileUri, "image/*");
            } else {
                String extension = MimeTypeMap.getFileExtensionFromUrl(sharedFileUri.toString());
                String type = context.getContentResolver().getType(sharedFileUri);
                if (extension == null || extension.trim().isEmpty()) {
                    intent.setDataAndType(sharedFileUri, "*/*");
                } else if (type == null || type.equals("application/octet-stream")) {
                    intent.setDataAndType(sharedFileUri, "*/*");
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class StreamTask extends AsyncTask<FileItem, Void, Void> {

        public static final int OPEN_AS_VIDEO = 0;
        public static final int OPEN_AS_AUDIO = 1;
        private int openAs;
        private LoadingDialog loadingDialog;

        StreamTask() {
            this(-1);
        }

        StreamTask(int openAs) {
            this.openAs = openAs;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingDialog = new LoadingDialog()
                    .setContext(context)
                    .setCanCancel(false)
                    .setDarkTheme(isDarkTheme)
                    .setTitle(R.string.loading);

            if (getFragmentManager() != null) {
                loadingDialog.show(getFragmentManager(), "loading dialog");
            }
        }

        @Override
        protected Void doInBackground(FileItem... fileItems) {
            FileItem fileItem = fileItems[0];

            Intent serveIntent = new Intent(getContext(), StreamingService.class);
            serveIntent.putExtra(StreamingService.SERVE_PATH_ARG, fileItem.getPath());
            serveIntent.putExtra(StreamingService.REMOTE_ARG, remote);
            serveIntent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, false);
            context.startService(serveIntent);

            String url = "http://127.0.0.1:8080/" + fileItem.getName();
            Intent intent = new Intent(Intent.ACTION_VIEW);

            // open as takes precedence
            if (openAs == OPEN_AS_VIDEO) {
                intent.setDataAndType(Uri.parse(url), "video/*");
            } else if (openAs == OPEN_AS_AUDIO) {
                intent.setDataAndType(Uri.parse(url), "audio/*");
            } else {
                String extension = fileItem.getName().substring(fileItem.getName().lastIndexOf(".") + 1);
                String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (type != null && type.startsWith("audio/")) {
                    intent.setDataAndType(Uri.parse(url), "audio/*");
                } else if (type != null && type.startsWith("video/")) {
                    intent.setDataAndType(Uri.parse(url), "video/*");
                } else {
                    intent.setData(Uri.parse(url));
                }
            }

            int code = -1;
            HttpURLConnection connection;

            int retries = 10;
            while (retries > 0) {
                try {
                    URL checkUrl = new URL(url);
                    connection = (HttpURLConnection) checkUrl.openConnection();
                    code = connection.getResponseCode();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (code == 200) {
                    break;
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                retries--;
            }

            loadingDialog.dismiss();
            startActivityForResult(intent, STREAMING_INTENT_RESULT);


            return null;
        }
    }
}
