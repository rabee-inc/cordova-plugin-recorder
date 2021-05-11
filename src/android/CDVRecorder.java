package jp.rabee.recorder;

import android.Manifest;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;


import com.arthenica.mobileffmpeg.ExecuteCallback;
import com.arthenica.mobileffmpeg.FFmpeg;
import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import com.otaliastudios.transcoder.engine.TrackType;
import com.otaliastudios.transcoder.sink.DataSink;
import com.otaliastudios.transcoder.sink.DefaultDataSink;
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;

import org.apache.cordova.*;


import org.jdeferred2.Deferred;
import org.jdeferred2.DoneCallback;
import org.jdeferred2.FailCallback;
import org.jdeferred2.Promise;
import org.jdeferred2.impl.DefaultDeferredManager;
import org.jdeferred2.impl.DeferredObject;
import org.jdeferred2.multiple.MultipleResults;
import org.jdeferred2.multiple.OneReject;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;


import omrecorder.AudioChunk;
import omrecorder.AudioRecordConfig;
import omrecorder.OmRecorder;
import omrecorder.PullTransport;
import omrecorder.PullableSource;
import omrecorder.Recorder;

import com.tonyodev.fetch2.*;
import com.tonyodev.fetch2.Request;

import static com.arthenica.mobileffmpeg.Config.RETURN_CODE_CANCEL;
import static com.arthenica.mobileffmpeg.Config.RETURN_CODE_SUCCESS;


public class CDVRecorder extends CordovaPlugin {

    private static final String TAG = CDVRecorder.class.getSimpleName();
    // media settings
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_NUM = 4096;
    private static final int SAMPLE_RATE_INDEX = 4;
    private static final int CHANNELS = 1;
    private static final int BIT_RATE = 32000;

    private static final int VOICE_PERMISSION_REQUEST_CODE = 100;

    private int bufferSize;
    private MediaCodec mediaCodec;
    private AudioRecord audioRecord;
    private OutputStream outputStream;

    private File inputFile;

    private Recorder recorder;

    private String RECORDING_ROOT_DIR;
    private String AUDIO_DIR;
    private String TEMP_DIR;
    private String VERSIONS_DIR;

    private String WAVEFORM_PATH;
    private String TEMP_WAV_PATH;
    private String PLAYABLE_AUDIO_NAME;
    private String JOINED_PATH;
    private String COMPRESSION_PATH;
    private String AUDIO_LIST_DIR;
    private String TEMP_AUDIO_LIST_DIR;
    private String action;
    private CallbackContext callbackContext;
    private CallbackContext pushBufferCallbackContext;
    private CallbackContext compressProgressCallbackContext;

    private List<File> sequences = new ArrayList<File>();

    private String currentAudioId;

    private Boolean isRecording = false;
    private List<CDVRecorderBgm> bgms = new ArrayList<>();

    // ダウンロード周り
    private Fetch fetch;
    private AbstractFetchGroupListener groupFetchListener;
    private AbstractFetchListener fetchListener;
    private CallbackContext downloadProgressCallbackId;

    // BT 周り
    private  BroadcastReceiver btReciver;
    private static final int DEFAULT_STATE = -1;
    private static final int DISCONNECTED = 0;
    private static final int CONNECTED = 1;
    private static final int BT_DISCONNECTED = 2;
    private static final int BT_CONNECTED = 3;
    private CallbackContext changeEarPhoneConnectedStatusCallbackContext;



    public void initialize(CordovaInterface cordova, CordovaWebView webView) {

        // for setup bt detection
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);
        intentFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        btReciver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = getConnectionStatus(intent.getAction(), intent);
                if (status == CONNECTED) {
                    resignMute();
                    Log.d(TAG, "Headset is connected");
                } else if (status == DISCONNECTED) {
                    muteBgm();
                    Log.d(TAG, "Headset is disconnected");
                }
                else if (status == BT_CONNECTED) {
                    resignMute();
                    Log.d(TAG, "BT Headset is connected");
                }
                else if (status == BT_DISCONNECTED) {
                    muteBgm();
                    Log.d(TAG, "BT Headset is disconnected");
                }
                else {
                    Log.d(TAG, "Headset state is unknown: " + status);
                }

                try {

                    if (changeEarPhoneConnectedStatusCallbackContext != null) {
                        JSONObject resultData = new JSONObject();
                        resultData.put("isConnected", isHeadsetEnabled());
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, resultData);
                        pluginResult.setKeepCallback(true);
                        changeEarPhoneConnectedStatusCallbackContext.sendPluginResult(pluginResult);
                    }

                } catch (JSONException e) {
                    e.printStackTrace();
                }

            }
        };

        webView.getContext().registerReceiver(this.btReciver, intentFilter);
        PLAYABLE_AUDIO_NAME = "joined.wav";
        // root フォルダーのチェック
        RECORDING_ROOT_DIR = cordova.getContext().getFilesDir() + "/recording";
        AUDIO_DIR = cordova.getContext().getFilesDir() + "/CDVRecorderAudio";
        TEMP_DIR = cordova.getContext().getFilesDir() + "/CDVRecorderTemp";
        WAVEFORM_PATH = TEMP_DIR + "/waveform";
        TEMP_WAV_PATH = TEMP_DIR + "/temp.wav";
        JOINED_PATH = AUDIO_DIR + "/" + PLAYABLE_AUDIO_NAME;
        COMPRESSION_PATH = AUDIO_DIR + "/joined.mp3";
        AUDIO_LIST_DIR = AUDIO_DIR + "/audios";
        TEMP_AUDIO_LIST_DIR = TEMP_DIR + "/audios";
        VERSIONS_DIR = AUDIO_DIR + "/versions";

        String initTargetDirs[] = {AUDIO_DIR, TEMP_DIR, AUDIO_LIST_DIR, TEMP_AUDIO_LIST_DIR, VERSIONS_DIR};

        for(String dir: initTargetDirs) {
            File file = new File(dir);
            if (!file.exists()) {
                file.mkdir();
            }
        }
    }

    public boolean execute(final String action, JSONArray args, final CallbackContext callbackContext)
            throws JSONException {
        this.action = action;
        final Activity activity = this.cordova.getActivity();
        final Context context = activity.getApplicationContext();
        final JSONObject options = args.optJSONObject(0);

        this.callbackContext = callbackContext;

        if (action.equals("start")) {
            cordova.setActivityResultCallback(this);
            startRecording(activity, callbackContext);
            return true;
        } else if (action.equals("pause")) {
            cordova.setActivityResultCallback(this);
            pauseRecording(activity, callbackContext);
            return true;
        } else if (action.equals("stop")) {
            stopRecording(activity, callbackContext);
            cordova.setActivityResultCallback(this);
            return true;
        } else if (action.equals("resume")) {
            cordova.setActivityResultCallback(this);
            resumeRecording(activity, callbackContext);
            return true;
        }
        // 非圧縮
        else if (action.equals("export")) {
            cordova.setActivityResultCallback(this);
            export(activity, callbackContext);
            return true;
        }
        // 圧縮
        else if (action.equals("exportWithCompression")) {
            cordova.setActivityResultCallback(this);
            exportWithCompression(activity, callbackContext);
            return true;
        }
        // push buffer
        else if (action.equals("onPushBuffer")) {
            pushBufferCallbackContext = callbackContext;
            return true;
        } else if (action.equals("onProgressCompression")) {
            compressProgressCallbackContext = callbackContext;
            return true;
        } else if (action.equals("getRecordingFolders")) {
            cordova.setActivityResultCallback(this);
            getRecordingFolders(activity, callbackContext);
            return true;
        } else if (action.equals("removeFolder")) {
            removeFolder(activity, callbackContext);
            return true;
        } else if (action.equals("removeCurrentFolder")) {
            cordova.setActivityResultCallback(this);
            removeCurrentFolder(activity, callbackContext);
            return true;
        } else if (action.equals("setFolder")) {
            String audioId = args.get(0).toString();
            cordova.setActivityResultCallback(this);
            setFolder(activity, callbackContext, audioId);
            return true;
        } else if (action.equals("getAudio")) {
            String audioId = args.get(0).toString();
            cordova.setActivityResultCallback(this);
            getAudio(activity, callbackContext, audioId);
            return true;
        } else if (action.equals("getWaveForm")) {
            cordova.setActivityResultCallback(this);
            String audioPath = args.get(0).toString();
            getWaveForm(activity, callbackContext, audioPath);
            return true;
        } else if (action.equals("splitAndStart")) {
            cordova.setActivityResultCallback(this);
            float second = Float.parseFloat(args.get(0).toString());
            splitAndStart(activity, callbackContext, second);
            return true;
        } else if (action.equals("changeDecibel")) {
            cordova.setActivityResultCallback(this);
            JSONArray jsonArray = args.getJSONArray(0);
            if (jsonArray.length() < 1) {
                callbackContext.error("First argument required. Please specify [number, ...]");
                return false;
            }
            if (jsonArray.length() <= 1) {
                // ファイル全体の音量を変更
                changeDecibel(activity, callbackContext, jsonArray.getDouble(0));
            }
            else {
                // 選択範囲の音量を変更
                changeDecibel(activity, callbackContext, jsonArray.getDouble(0), jsonArray.getDouble(1), jsonArray.getDouble(2));
            }
            return true;
        } else if (action.equals("getWaveFormByFile")) {
            cordova.setActivityResultCallback(this);
            String audioPath = args.get(0).toString();
            getWaveFormByFile(activity, callbackContext, audioPath);
            return true;
        } else if (action.equals("split")) {
            cordova.setActivityResultCallback(this);
            float second = Float.parseFloat(args.get(0).toString());
            split(activity, callbackContext, second);
            return true;
        } else if (action.equals("importAudio")){
            cordova.setActivityResultCallback(this);
            String audioPath = args.get(0).toString();
            importAudio(activity, callbackContext, audioPath);
            return true;
        } else if (action.equals("getMicPermission")){
            cordova.setActivityResultCallback(this);
            getMicPermission(activity, callbackContext);
            return true;
        } else if (action.equals("initSettings")) {
            // TODO: 設定を書く
            callbackContext.success("ok");
            return true;
        } else if (action.equals("setBgm")) {
            JSONObject obj = args.getJSONObject(0);
            return setBgm(activity, callbackContext, obj);
        } else if (action.equals(("downloadBgm"))) {
            return downloadBgm();
        } else if (action.equals("seekBgm")) {
            Double sec = args.getDouble(0);
            return seekBgm(activity, callbackContext, sec);
        } else if (action.equals(("clearBgm"))) {
            return clearBgm(activity, callbackContext);
        } else if (action.equals(("getSampleRate"))) {
            return getSampleRate(activity, callbackContext);
        } else if (action.equals(("setOnDownloadBgmProgress"))) {
            return setOnDownloadBgmProgress(activity, callbackContext);
        } else if (action.equals(("setOnChangeEarPhoneConnectedStatus"))) {
            return setOnChangeEarPhoneConnectedStatus(activity, callbackContext);
        } else if (action.equals(("initialize"))) {
            callbackContext.success("ok");
            return true;
        } else if (action.equals(("canRestore"))) {
            cordova.setActivityResultCallback(this);
            canRestore(activity, callbackContext);
            return true;
        } else if (action.equals(("restore"))) {
            cordova.setActivityResultCallback(this);
            restore(activity, callbackContext);
            return true;
        } else if (action.equals("trim")) {
            cordova.setActivityResultCallback(this);
            JSONArray jsonArray = args.getJSONArray(0);
            trim(activity, callbackContext, jsonArray.getDouble(0), jsonArray.getDouble(1));
            return true;
        } else if (action.equals("addNewVersion")) {
            cordova.setActivityResultCallback(this);
            try {
                int version = addNewVersion();
                PluginResult p = new PluginResult(PluginResult.Status.OK, version);
                callbackContext.sendPluginResult(p);
            } catch (IOException e) {
                callbackContext.error("write file error");
            }
            return true;
        } else if (action.equals("restoreFromVersion")) {
            cordova.setActivityResultCallback(this);
            String err = restoreFromVersion();
            if (err == null) {
                PluginResult p = new PluginResult(PluginResult.Status.OK, true);
                callbackContext.sendPluginResult(p);
            }
            else {
                callbackContext.error(err);
            }
            return true;
        } else if (action.equals("removeVersions")) {
            cordova.setActivityResultCallback(this);
            removeVersions();

            PluginResult p = new PluginResult(PluginResult.Status.OK, true);
            callbackContext.sendPluginResult(p);
            return true;
        } else if (action.equals("cut")) {
            cordova.setActivityResultCallback(this);
            JSONArray jsonArray = args.getJSONArray(0);
            ArrayList<double[]> trims = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONArray doubles = jsonArray.getJSONArray(i);
                trims.add(new double[] {doubles.getDouble(0), doubles.getDouble(1)});
            }
            cut(activity, callbackContext, trims);
            return true;
        } else  {
            return false;
        }

    }

    // パーミッションあるかどうか確認=>なければリクエスト出す
    private boolean checkSelfPermission(String permission, int requestCode) {
        Log.i(TAG, "checkSelfPermission $permission $requestCode");
        return ContextCompat.checkSelfPermission(cordova.getContext(),
                permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean getMicPermission(Activity activity, CallbackContext callbackContext) {
        boolean hasPermission = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO, VOICE_PERMISSION_REQUEST_CODE);
        // mic permission を確認
        if (!hasPermission) {
            cordova.requestPermissions(this, VOICE_PERMISSION_REQUEST_CODE, new String[]{Manifest.permission.RECORD_AUDIO});
        }
        PluginResult p = new PluginResult(PluginResult.Status.OK, hasPermission);
        callbackContext.sendPluginResult(p);
        return true;
    }




    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        super.onActivityResult(requestCode, resultCode, intent);
    }


    public void startRecording(final Activity activity, final CallbackContext callbackContext) {
        removeAudios();
        // 処理
        playBgm();
        start(JOINED_PATH, callbackContext);

    }

    public void pauseRecording(final Activity activity, final CallbackContext callbackContext) {
        try {
            pauseBgm();
            recorder.stopRecording();
            isRecording = false;
            callbackContext.success("ok");
        } catch (IOException e) {
            callbackContext.error(e.getLocalizedMessage());
        }
    }

    public void resumeRecording(final Activity activity, final CallbackContext callbackContext) {
        File[] files = new File(AUDIO_LIST_DIR).listFiles();
        playBgm();
        start(AUDIO_LIST_DIR + "/" + files.length + ".wav", callbackContext);
        isRecording = true;
    }


    public void stopRecording(final Activity activity, final CallbackContext callbackContext) {
        try {
            recorder.stopRecording();
            pauseBgm();
            // 処理
            isRecording = false;

            callbackContext.success("ok");
        } catch (IOException e) {
            callbackContext.error(e.getLocalizedMessage());
        }
    }

    private void export(final Activity activity, final CallbackContext callbackContext) {
        JSONObject audioData = new JSONObject();
        JSONObject fullAudio = new JSONObject();

        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                generateJoinedAudio().then(new DoneCallback<File>() {
                    @Override
                    public void onDone(File file) {
                        try {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, getJoinedAudioData());
                            callbackContext.sendPluginResult(result);
                        } catch (Exception e) {
                            callbackContext.error("json error");
                        }
                    }
                }).fail(new FailCallback<String>() {
                    @Override
                    public void onFail(String result) {
                        callbackContext.error(result);
                    }
                });

            }

        });

    }

    private void exportWithCompression(final Activity activity, final CallbackContext callbackContext) {
        JSONObject audioData = new JSONObject();
        if (currentAudioId == null) {
            callbackContext.error("please set audio id");
            return;
        }
        try {
            File inputFile = new File(RECORDING_ROOT_DIR + "/" + currentAudioId + "/merged/merged.wav");
            File outputDir = new File(RECORDING_ROOT_DIR + "/compressed");
            outputDir.mkdir();


            File outputFile = File.createTempFile("compressed", ".aac", outputDir);
            DataSink sink = new DefaultDataSink(outputFile.getAbsolutePath());

            DefaultAudioStrategy strategy = DefaultAudioStrategy.builder().channels(1).sampleRate(SAMPLE_RATE).build();

            Transcoder.into(sink).addDataSource(TrackType.AUDIO, inputFile.getPath()).setAudioTrackStrategy(strategy).setListener(new TranscoderListener() {
                @Override
                public void onTranscodeProgress(double progress) {
                    if (compressProgressCallbackContext != null) {

                        PluginResult result = new PluginResult(PluginResult.Status.OK, (BigDecimal.valueOf(progress).setScale(3, RoundingMode.CEILING.HALF_UP).toPlainString()));
                        result.setKeepCallback(true);
                        compressProgressCallbackContext.sendPluginResult(result);
                    }
                }

                @Override
                public void onTranscodeCompleted(int successCode) {

                    try {
                        if (compressProgressCallbackContext != null) {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, "1s");
                            result.setKeepCallback(true);
                            compressProgressCallbackContext.sendPluginResult(result);
                        }
                        audioData.put("full_audio", "file://" + outputFile.getAbsoluteFile());
                        PluginResult result = new PluginResult(PluginResult.Status.OK, audioData);
                        callbackContext.sendPluginResult(result);
                    } catch (JSONException e) {
                        callbackContext.error(e.getLocalizedMessage());
                    }

                }

                @Override
                public void onTranscodeCanceled() {

                }

                @Override
                public void onTranscodeFailed(@NonNull Throwable exception) {
                    callbackContext.error(exception.getLocalizedMessage());
                }
            }).transcode();

        } catch (Exception e) {
            callbackContext.error(e.getLocalizedMessage());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void getAudio(final Activity activity, final CallbackContext callbackContext, final String audioId) {
        String pathname = RECORDING_ROOT_DIR + "/" + audioId + "/merged/merged.wav";
        File inputFile = new File(pathname);

        JSONObject audioData = new JSONObject();
        JSONObject fullAudio = new JSONObject();

        try {
            fullAudio.put("path", "file://" + inputFile.getAbsoluteFile());
            Uri uri = Uri.parse(pathname);
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(this.cordova.getContext(), uri);
            String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

            fullAudio.put("duration", Integer.parseInt(durationStr) / 1000.0);
            audioData.put("full_audio", fullAudio);
            callbackContext.success(audioData);

        } catch (Exception e) {

        }
    }

    private void canRestore(final Activity activity, final CallbackContext callbackContext) {
        boolean message = false;
        restoreFromVersion();
        removeVersions();
        message = new File(JOINED_PATH).exists();
        // 新仕様のファイルが存在しない場合は、 結合前のファイルが一つでも存在するかチェック
        if (!message) {
            File audioList = new File(AUDIO_LIST_DIR);
            if (audioList.exists()) {
                File[] files = audioList.listFiles();
                message = files.length != 0;
            }
        }

        if (!message) {
            File recordingDir = new File(RECORDING_ROOT_DIR);
            if (recordingDir.exists()) {
                File[] files = recordingDir.listFiles();
                if (files.length != 0) {
                    Arrays.sort(files, (File a, File b) -> {
                        return Integer.parseInt(b.getName()) - Integer.parseInt(a.getName());
                    });
                    File file = files[0];
                    File joinedFile = new File(RECORDING_ROOT_DIR + "/" + file.getName() + "/merged/merged.wav");
                    if (joinedFile.exists()) {
                        File targetFile = new File(JOINED_PATH);
                        joinedFile.renameTo(targetFile);
                        message = true;
                        deleteDirectory(new File(RECORDING_ROOT_DIR));
                    }
                }
            }
        }

        callbackContext.success(message ? 1 : 0);

    }

    private String restoreFromVersion() {
        File versionsFile = new File(VERSIONS_DIR);
        File[] versions = versionsFile.listFiles();
        String err = null;
        if (versions.length <= 0) {
            return "ファイルが存在しません";
        }
        Arrays.sort(versions, (File a, File b) -> {
            return Integer.parseInt(b.getName()) - Integer.parseInt(a.getName());
        });
        for (File version: versions) {
            err = restoreFromVersion(version.getName());
            if (err == null) {
                break;
            }
        }
        return err;
    }

    private String restoreFromVersion(String version) {
        String dir = VERSIONS_DIR + "/" + version;
        String err = null;
        String playableAudioPath = dir + "/" + PLAYABLE_AUDIO_NAME;
        File playableAudioFile = new File(playableAudioPath);
        if (playableAudioFile.exists()) {
            File joinedFile = new File(JOINED_PATH);
            if (joinedFile.exists()) {
                joinedFile.delete();
            }
            playableAudioFile.renameTo(joinedFile);
        } else {
            err = "ファイルが存在しません";
        }
        return err;
    }

    private void setToVersion(int version) throws IOException {
        String toDirPath = VERSIONS_DIR + "/" + version;
        File toDir = new File(toDirPath);
        if (!toDir.exists()) {
            toDir.mkdir();
        }
        String toPath = toDirPath + "/" + PLAYABLE_AUDIO_NAME;
        File toFile = new File(toPath);
        if (toFile.exists()) {
            toFile.delete();
        }
        copyFile(new File(JOINED_PATH), toFile);
    }

    private int addNewVersion() throws IOException {
        File[] versions = new File(VERSIONS_DIR).listFiles();
        setToVersion(versions.length);
        return versions.length;
    }

    private void removeVersion(String version) {
        File dir = new File(VERSIONS_DIR + "/" + version);
        if (dir.exists()) {
            deleteDirectory(dir);
        }
    }


    private void copyFile(File in, File out) throws IOException {
        FileChannel inChannel = new FileInputStream(in).getChannel();
        FileChannel outChannel = new FileOutputStream(out).getChannel();
        try {
            inChannel.transferTo(0, inChannel.size(),outChannel);
        }
        catch (IOException e) {
            throw e;
        }
        finally {
            if (inChannel != null) inChannel.close();
            if (outChannel != null) outChannel.close();
        }
    }


    private JSONObject getJoinedAudioData() throws JSONException {
        File audioPath = new File(JOINED_PATH);

        JSONObject audioData = new JSONObject();
        JSONObject fullAudio = new JSONObject();

        fullAudio.put("path", "file://" + audioPath.getAbsoluteFile());
        fullAudio.put("duration", getDuration(JOINED_PATH));
        audioData.put("full_audio", fullAudio);
        return audioData;
    }

    private double getDuration(String audioPath) {
        Uri uri = Uri.parse(audioPath);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(this.cordova.getContext(), uri);
        String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        return Double.parseDouble(durationStr) / 1000.0;
    }

    public void restore(Activity activity, CallbackContext callbackContext) {
        Log.v("restore", "restore called");
        Promise promise = generateJoinedAudio();
        promise.then(new DoneCallback<File>() {
            @Override
            public void onDone(File success) {
                try {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, getJoinedAudioData());
                    callbackContext.sendPluginResult(result);
                } catch (Exception e) {
                    callbackContext.error("json error");
                }
            }
        }).fail(new FailCallback<String>() {
            @Override
            public void onFail(String result) {
                callbackContext.error(result);
            }
        });
    }

    // 再帰的にフォルダ削除
    boolean deleteDirectory(File directoryToBeDeleted) {
        File[]allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }

    void deleteDirectoryFiles(File directoryToBeDeleted) {
        File[]allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
    }

    // 音声ファイルをwavに変換する
    private Promise convertToWav(File inputFile, File outputFile) {

        Deferred deferred = new DeferredObject();
        Promise promise = deferred.promise();

        long executionId = FFmpeg.executeAsync("-y -i " + inputFile.getAbsolutePath() + " " + outputFile.getAbsolutePath(), new ExecuteCallback() {

            @Override
            public void apply(final long executionId, final int returnCode) {
                if (returnCode == RETURN_CODE_SUCCESS) {
                    LOG.v(TAG, "finish");
                    deferred.resolve("success");
                } else if (returnCode == RETURN_CODE_CANCEL) {
                    LOG.v(TAG, "Async command execution cancelled by user.");
                    deferred.reject("cancel");
                } else {
                    LOG.v(TAG, String.format("Async command execution failed with returnCode=%d.", returnCode));
                    deferred.reject("failed");
                }
            }
        });

        return promise;
    }

    // id をセット
    private void setAudio(String audioId) {
    }

    // audio id を生成
    private String getNewAudioId() {
        long unixTime = System.currentTimeMillis(); // unixTime
        UUID id = UUID.randomUUID(); // uuid
        return unixTime + "_" + id.toString();
    }

    private File getFileDir(String name, String filename) throws IOException {
        File file;
        if (filename != null) {
            file = new File(RECORDING_ROOT_DIR + "/" + name, filename);
        } else {
            file = new File(RECORDING_ROOT_DIR + "/" + name, "test.wav");
        }

        // あれば作成
        if (file.exists()) {
            return file;
        }
        // なければ mkdir
        else {
            if (!file.getParentFile().exists()) file.getParentFile().mkdir();
            file.createNewFile();
            return file;
        }
    }

    private void getRecordingFolders(final Activity activity, final CallbackContext callbackContext) {
        File[] files = this.getRecordingFolders();
        JSONArray list = new JSONArray();

        for (int i = 0; i < files.length; i++) {
            list.put(files[i].getName());
        }

        PluginResult result = new PluginResult(PluginResult.Status.OK, list);
        callbackContext.sendPluginResult(result);
    }

    private File[] getRecordingFolders() {
        File directory = new File(RECORDING_ROOT_DIR);
        if (directory.exists()) {
            return directory.listFiles();
        } else {
            return new File[0];
        }
    }

    private void setFolder(final Activity activity, final CallbackContext callbackContext, final String audioId) {
        this.currentAudioId = audioId;
        callbackContext.success("ok");
    }

    private void removeCurrentFolder(final Activity activity, final CallbackContext callbackContext) {
        this.currentAudioId = null;
        removeFolder();
        callbackContext.success("succss");
    }

    private Promise concatAudio(List<String> files, String outputPath) {
        // temp.wavを削除する
        // filesにあるfileを結合していく
        // 元のjoined.wavがあれば削除
        // temp.wavをjoined.wavにファイル名変更
        List<String> commands = new ArrayList<String>();
        int concatAudioCounter = 0;
        File tempFile = new File(TEMP_WAV_PATH);
        removeTempWav();

        // success と finish を発火させるのに必要
        commands.add("-y");
        for(String file :files) {
            String filePath = new File(file).getAbsolutePath();
            commands.add("-i");
            commands.add(filePath);
            concatAudioCounter++;
        }
        if (concatAudioCounter > 0) {
            commands.add("-filter_complex");
            commands.add("concat=n=" + concatAudioCounter + ":v=0:a=1");
        }
        commands.add(TEMP_WAV_PATH);

        String command = "";
        for (String str: commands) {
            command += str + " ";
        }
        Deferred deferred = new DeferredObject();
        Promise promise = deferred.promise();

        Log.v("concat audio command is ", command);

        long executionId = FFmpeg.executeAsync(command, new ExecuteCallback() {

            @Override
            public void apply(final long executionId, final int returnCode) {
                Log.v("ffmpeg executeAsync is run", Integer.valueOf(returnCode).toString());
                if (returnCode == RETURN_CODE_SUCCESS) {
                    File outputFile = new File(outputPath);

                    if (outputFile.exists()) {
                        outputFile.delete();
                    }

                    tempFile.renameTo(outputFile);

                    deferred.resolve(outputFile);
                } else if (returnCode == RETURN_CODE_CANCEL) {
                    LOG.v(TAG, "Async command execution cancelled by user.");
                    deferred.reject("cancel");
                } else {
                    LOG.v(TAG, String.format("Async command execution failed with returnCode=%d.", returnCode));
                    deferred.reject("failed");
                }
            }
        });

        return promise;

    }

    private void removeTempAudios() {
        deleteDirectoryFiles(new File(TEMP_AUDIO_LIST_DIR));
    }

    private void removeTempWav() {
        File tempFile = new File(TEMP_WAV_PATH);
        if (tempFile.exists()) {
            tempFile.delete();
        }
    }

    private Promise generateJoinedAudio() {
        List<String> targets = new ArrayList<String>();
        if (new File(JOINED_PATH).exists()) {
            targets.add(JOINED_PATH);
        }
        if (new File(AUDIO_LIST_DIR).exists()) {
            File[] audioList = new File(AUDIO_LIST_DIR).listFiles();
            Arrays.sort(audioList, (File a, File b) -> {
                // 1.wav 2.wav で拡張子を除いて 2 - 1 する
                return Integer.parseInt(a.getName().replaceAll("[^0-9]", "")) - Integer.parseInt(b.getName().replaceAll("[^0-9]", ""));
            });
            for(File file: audioList) {
                targets.add(AUDIO_LIST_DIR + '/' + file.getName());
            }
        }
        Promise promise = concatAudio(targets, JOINED_PATH);
        return promise.then(new DoneCallback() {
            @Override
            public void onDone(Object result) {
                removeAudios();
            }
        });
    }

    private void removeFolder(final Activity activity, final CallbackContext callbackContext) {
        removeFolder();
        callbackContext.success("success");
    }

    private void removeFolder() {
        removeAudios();
        File joined = new File(JOINED_PATH);
        if (joined.exists()) {
            joined.delete();
        }
        File compression = new File(COMPRESSION_PATH);
        if (compression.exists()) {
            compression.delete();
        }
    }

    private void removeVersions() {
        deleteDirectoryFiles(new File(VERSIONS_DIR));
    }

    private void removeAudios() {
        deleteDirectoryFiles(new File(AUDIO_LIST_DIR));
    }

    private File getCurrentAudioFolder() {
        File file = new File(RECORDING_ROOT_DIR + "/" + currentAudioId);
        return file;
    }

    private void split(final Activity activity, final CallbackContext callbackContext, float second) {
        File mergedFile = new File(RECORDING_ROOT_DIR + "/" + currentAudioId + "/merged/merged.wav");
        File outputDir = new File(RECORDING_ROOT_DIR + "/" + currentAudioId + "/merged/temp-merged.wav");

        List<String> commands = new ArrayList<String>();

        commands.add("-ss");
        commands.add("0");
        commands.add("-i");
        commands.add(mergedFile.getAbsolutePath());

        float s = second * 1000;

        // 小数第一位を切り捨て
        BigDecimal bd = new BigDecimal(String.valueOf(s));
        BigDecimal bd1 = bd.setScale(0, RoundingMode.DOWN);
        int plainTime = Integer.parseInt(bd1.toPlainString());

        commands.add("-t");
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss.SSS");
        formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
        String timeFormatted = formatter.format(plainTime);
        commands.add(timeFormatted);

        commands.add(outputDir.getAbsolutePath());


        String[] command = commands.toArray(new String[commands.size()]);


        long executionId = FFmpeg.executeAsync(command, new ExecuteCallback() {

            @Override
            public void apply(final long executionId, final int returnCode) {
                if (returnCode == RETURN_CODE_SUCCESS) {
                    // temp-merged -> merged
                    if (mergedFile.exists()) {
                        mergedFile.delete();
                    }

                    mergedFile.getParentFile().mkdir();

                    File newMergedFile = new File(mergedFile.getAbsolutePath());

                    outputDir.renameTo(newMergedFile);

                    JSONObject fullAudio = new JSONObject();
                    JSONObject audioData = new JSONObject();

                    try {
                        fullAudio.put("path", "file://" + newMergedFile.getAbsoluteFile());
                        audioData.put("full_audio", fullAudio);
                        audioData.put("folder_id", currentAudioId);

                        PluginResult result = new PluginResult(PluginResult.Status.OK, audioData);
                        callbackContext.sendPluginResult(result);
                    } catch (Exception e) {
                        callbackContext.error("error on spliting");
                    }
                } else if (returnCode == RETURN_CODE_CANCEL) {
                    LOG.v(TAG, "Async command execution cancelled by user.");
                } else {
                    LOG.v(TAG, String.format("Async command execution failed with returnCode=%d.", returnCode));
                }
            }
        });
    }

    /**
     *
     * @param input 入力ファイル
     * @param output 出力先
     * @param start 開始時間 (秒)
     * @param end 終了時間 (秒)
     * @return Promise ffmpeg の非同期処理
     */
    private Promise trim(String input, String output, double start, double end) {
        File tempFile = new File(TEMP_WAV_PATH);
        if (tempFile.exists()) {
            tempFile.delete();
        }

        List<String> commands = new ArrayList<String>();

        // 開始時間の設定
        commands.add("-ss");

        start *= 1000;
        // 小数第一位を切り捨て
        BigDecimal formattedStart = new BigDecimal(String.valueOf(start)).setScale(0, RoundingMode.DOWN);
        int plainStart = Integer.parseInt(formattedStart.toPlainString());

        // 時間のフォーマット整形クラス生成
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss.SSS");
        formatter.setTimeZone(TimeZone.getTimeZone("GMT"));

        String formattedStartString = formatter.format(plainStart);

        commands.add(formattedStartString);

        // 終了時間
        commands.add("-to");
        end *= 1000;
        // 小数第一位を切り捨て
        BigDecimal formattedEnd = new BigDecimal(String.valueOf(end)).setScale(0, RoundingMode.DOWN);
        int plainEnd = Integer.parseInt(formattedEnd.toPlainString());
        String formattedEndString = formatter.format(plainEnd);

        commands.add(formattedEndString);


        // 入力ファイル
        commands.add("-i");
        commands.add(new File(input).getAbsolutePath());


        // 出力ファイル
        commands.add(tempFile.getAbsolutePath());


        String[] command = commands.toArray(new String[commands.size()]);

        // 非同期処理
        Deferred deferred = new DeferredObject();
        Promise promise = deferred.promise();

        long executionId = FFmpeg.executeAsync(command, new ExecuteCallback() {

            @Override
            public void apply(final long executionId, final int returnCode) {
                if (returnCode == RETURN_CODE_SUCCESS) {
                    File outputFile = new File(output);
                    // temp-merged -> merged
                    if (outputFile.exists()) {
                        outputFile.delete();
                    }

                    tempFile.renameTo(outputFile);

                    deferred.resolve("success");
                } else if (returnCode == RETURN_CODE_CANCEL) {
                    LOG.v(TAG, "Async command execution cancelled by user.");
                    deferred.reject("cancel");
                } else {
                    LOG.v(TAG, String.format("Async command execution failed with returnCode=%d.", returnCode));
                    deferred.reject("failed");
                }
            }
        });

        return promise;
    }

    private void trim(Activity activity, CallbackContext callbackContext, double start, double end) {
        Promise p = trim(JOINED_PATH, JOINED_PATH, start, end);
        p.then(new DoneCallback<String>() {
            @Override
            public void onDone(String file) {
                try {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, getJoinedAudioData());
                    callbackContext.sendPluginResult(result);
                } catch (Exception e) {
                    callbackContext.error("json error");
                }
            }
        }).fail(new FailCallback<String>() {
            @Override
            public void onFail(String result) {
                callbackContext.error(result);
            }
        });
    }


    private void cut(Activity activity, CallbackContext callbackContext, List<double[]> params) {
        List<double[]> trimParams = new ArrayList<>();
        trimParams.add(new double[]{0.0,  0.0});

        Collections.sort(params, (double[] a, double[] b) -> {
            return (int) (a[0] - b[0]);
        });
        int i = 1;
        for (double[] param : params) {
            double start = param[0];
            double end = param[1];
            trimParams.get(i - 1)[1] = start;
            trimParams.add(new double[]{end, end});
            i++;
        }
        trimParams.get(i - 1)[1] = getDuration(JOINED_PATH);
        i = 1;
        removeAudios();
        for (double[] param : trimParams) {
            if (param[0] != param[1]) {
                Promise trim = trim(JOINED_PATH, AUDIO_LIST_DIR + "/" + i + ".wav", param[0], param[1]);
                try {
                    trim.waitSafely();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    callbackContext.error("カットに失敗しました");
                    removeAudios();
                    return;
                }
                i++;
            }
        }
        File joinedFile = new File(JOINED_PATH);
        if (joinedFile.exists()) {
            joinedFile.delete();
        }
        generateJoinedAudio().then(new DoneCallback() {
            @Override
            public void onDone(Object res) {
                try {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, getJoinedAudioData());
                    callbackContext.sendPluginResult(result);
                } catch (JSONException e) {
                    e.printStackTrace();
                    callbackContext.error("json error");
                }
            }
        }).fail(new FailCallback<String>() {
            @Override
            public void onFail(String result) {
                callbackContext.error(result);
            }
        });
    }

    private void importAudio(final Activity activity, final CallbackContext callbackContext, String audioPath) {
        if (audioPath != null) {
            String path = audioPath.replace("file://", "");
            File input = new File(path);
            input.renameTo(new File(JOINED_PATH));
            removeAudios();
            try {
                PluginResult result = new PluginResult(PluginResult.Status.OK, getJoinedAudioData());
                callbackContext.sendPluginResult(result);
            } catch (Exception e) {
                callbackContext.error("error on importing");
            }
        } else {
            callbackContext.error("please set audio");
            return;
        }
    }

    private void getWaveForm(final Activity activity, final CallbackContext callbackContext, String audioPath) {
        File file;
        if (audioPath != null) {
            String path = audioPath.replace("file://", "");

            file = new File(path);
            File parentPath = file.getParentFile();
            File currentID = parentPath.getParentFile();
            currentAudioId = currentID.getName();

        } else if (currentAudioId != null) {
            file = new File(RECORDING_ROOT_DIR + "/" + currentAudioId + "/merged/merged.wav");
        } else {
            callbackContext.error("please set audio");
            return;
        }

        File tempwaveform = new File(WAVEFORM_PATH);
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    callbackContext.success(getWaveForm(file, tempwaveform));
                }
                catch (Exception e) {
                    callbackContext.error("波形の取得に失敗しました。\n" + e);
                    e.printStackTrace();
                }
            }
        });
    }

    private String getWaveForm(File inputFile, File outputFile) throws IOException, WavFileException {
        if (!outputFile.getParentFile().exists()) {
            outputFile.getParentFile().mkdir();
        }
        WavFile wavFile = WavFile.openWavFile(inputFile);
        // Get the number of audio channels in the wav file
        int numChannels = wavFile.getNumChannels();

        int[] buffer = new int[BUFFER_NUM * numChannels];
        int outputBufferNum = (int) Math.ceil((double) wavFile.getNumFrames() / BUFFER_NUM);
        short[] outputBuffer = new short[outputBufferNum];
        int outputBufferIndex = 0;
        int framesRead;
        while(true) {
            int max = Integer.MIN_VALUE;
            // 波形を読み込む
            framesRead = wavFile.readFrames(buffer, BUFFER_NUM);
            if (framesRead == 0) {
                break;
            }
            // 最大音量を取得
            for (int s = 0; s < framesRead * numChannels ; s++) {
                int v = Math.abs(buffer[s]);
                if (v > max) max = v;
            }
            short sMax = (short) Math.min(max, Short.MAX_VALUE);
            outputBuffer[outputBufferIndex++] = sMax;
        }
        // Close the wavFile
        wavFile.close();
        // short 配列を byte 配列に変換してファイル書き込み
        ByteBuffer byteBuffer = ByteBuffer.allocate(outputBuffer.length * 2);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.asShortBuffer().put(outputBuffer);
        byte[] bytes = byteBuffer.array();
        OutputStream output = new FileOutputStream(outputFile);
        output.write(bytes);
        output.close();
        return "file://" + outputFile.getAbsolutePath();
    }

    private void splitAndStart(final Activity activity, final CallbackContext callbackContext, float splitSeconds) {
        if (isRecording) {
            callbackContext.error("already starting");
            return;
        }

        String pathA = AUDIO_LIST_DIR + "/1.wav";
        String pathB = AUDIO_LIST_DIR + "/2.wav";
        String pathC = AUDIO_LIST_DIR + "/3.wav";

        removeAudios();

        if (splitSeconds <= 0.05) {
            File joinedFile = new File(JOINED_PATH);
            File fileC = new File(pathC);
            if (joinedFile.exists()) {
                joinedFile.renameTo(fileC);
            }
        }
        else {
            Promise trim = trim(JOINED_PATH, pathA, 0, splitSeconds);
            try {
                trim.waitSafely();
                trim = trim(JOINED_PATH, pathC, splitSeconds, getDuration(JOINED_PATH));
                trim.waitSafely();
            } catch (InterruptedException e) {
                e.printStackTrace();
                callbackContext.error("挿入に失敗しました");
                removeAudios();
                return;
            }
        }

        File joinedFile = new File(JOINED_PATH);
        if (joinedFile.exists()) {
            joinedFile.delete();
        }

        start(pathB, callbackContext);
    }

    private void changeDecibel(final Activity activity, final CallbackContext callbackContext, double db, double start, double end) {
        if (start == end) {
            callbackContext.error("選択範囲が狭すぎます");
            return ;
        }

        // A, B, C を結合順として定義する
        // B は音量変更対象のパス
        String pathA = AUDIO_LIST_DIR + "/1.wav";
        String pathB = AUDIO_LIST_DIR + "/2.wav";
        String pathC = AUDIO_LIST_DIR + "/3.wav";
        double duration = getDuration(JOINED_PATH);
        removeAudios();
        start = Math.max(0, start);
        end = Math.min(end, duration);
        try {
            trim(JOINED_PATH, pathB, start, end).waitSafely();
        } catch (InterruptedException e) {
            e.printStackTrace();
            callbackContext.error("音量の変更に失敗しました。(trim B)");
            return;
        }

        try {
            // 範囲の音量を上げる
            Promise p = changeDecibel(pathB, pathB, db);
            p.waitSafely();
            if (p.isRejected()) {
                throw new Exception();
            }
        } catch (Exception e) {
            callbackContext.error("音量の変更に失敗しました。");
            e.printStackTrace();
            return;
        }
        // 範囲より前側を切り取り
        // start が 0.05 以上のときだけ trim
        if (start >= 0.05) {
            try {
                trim(JOINED_PATH, pathA, 0, start).waitSafely();
            } catch (InterruptedException e) {
                e.printStackTrace();
                callbackContext.error("音量の変更に失敗しました。(trim A)");
                return;
            }
        }
        // 範囲より後側を切り取り
        if (end <= (duration - 0.05)) {
            try {
                trim(JOINED_PATH, pathC, end, duration).waitSafely();
            } catch (InterruptedException e) {
                e.printStackTrace();
                callbackContext.error("音量の変更に失敗しました。(trim C)");
                return;
            }
        }
        File joinedFile = new File(JOINED_PATH);
        if (joinedFile.exists()) {
            joinedFile.delete();
        }

        double updatedAudioDuration = getDuration(pathB);
        try {
            generateJoinedAudio().waitSafely();
        } catch (InterruptedException e) {
            e.printStackTrace();
            callbackContext.error("音量の変更に失敗しました。(generateJoinedAudio)");
            return;
        }
        try {
            JSONObject joinedAudioData = getJoinedAudioData();
            JSONObject fullAudio = (JSONObject) joinedAudioData.get("full_audio");
            JSONObject updatedAudio = new JSONObject();
            updatedAudio.put("duration", updatedAudioDuration);
            updatedAudio.put("start", start);
            updatedAudio.put("end", start + updatedAudioDuration);
            joinedAudioData.put("updated_audio", updatedAudio);
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, joinedAudioData);
            callbackContext.sendPluginResult(pluginResult);
        } catch (JSONException e) {
            e.printStackTrace();
            callbackContext.error("json error");
            return;
        }
    }

    private void changeDecibel(final Activity activity, final CallbackContext callbackContext, double db) {
        changeDecibel(JOINED_PATH, JOINED_PATH, db).then(new DoneCallback() {
            @Override
            public void onDone(Object result) {
                try {
                    JSONObject joinedAudioData = getJoinedAudioData();
                    JSONObject fullAudio = (JSONObject) joinedAudioData.get("full_audio");
                    double duration = fullAudio.getDouble("duration");
                    JSONObject updatedAudio = new JSONObject();
                    updatedAudio.put("duration", duration);
                    updatedAudio.put("start", 0);
                    updatedAudio.put("end", duration);
                    joinedAudioData.put("updated_audio", updatedAudio);
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, joinedAudioData);
                    callbackContext.sendPluginResult(pluginResult);
                } catch (JSONException e) {
                    e.printStackTrace();
                    callbackContext.error("json error");
                }
            }
        }).fail(new FailCallback() {
            @Override
            public void onFail(Object result) {
                callbackContext.error("音量の変更に失敗しました。");
            }
        });
    }

    /**
     * ファイル全体の音量変更
     * @param input 入力音声ファイル
     * @param output 出力先
     * @param db デシベル
     */
    private Promise changeDecibel(String input, String output, double db) {
        File tempFile = new File(TEMP_WAV_PATH);
        removeTempWav();

        List<String> commands = new ArrayList<String>();
        // 入力ファイル
        commands.add("-i");
        commands.add(new File(input).getAbsolutePath());

        BigDecimal bd = new BigDecimal(String.valueOf(db));
        // 小数第四位を切り捨て
        BigDecimal bd4 = bd.setScale(3, RoundingMode.DOWN);

        commands.add("-filter:a");
        commands.add("volume=" + bd4.toPlainString() + "dB");

        // 出力ファイル
        commands.add(tempFile.getAbsolutePath());

        // 非同期処理
        Deferred deferred = new DeferredObject();
        Promise promise = deferred.promise();
        String[] command = commands.toArray(new String[commands.size()]);
        long executionId = FFmpeg.executeAsync(command, new ExecuteCallback() {
            @Override
            public void apply(final long executionId, final int returnCode) {
                if (returnCode == RETURN_CODE_SUCCESS) {
                    File outputFile = new File(output);
                    if (outputFile.exists()) {
                        outputFile.delete();
                    }
                    tempFile.renameTo(outputFile);

                    deferred.resolve("success");
                } else if (returnCode == RETURN_CODE_CANCEL) {
                    LOG.v(TAG, "Async command execution cancelled by user.");
                    deferred.reject("cancel");
                } else {
                    LOG.v(TAG, String.format("Async command execution failed with returnCode=%d.", returnCode));
                    deferred.reject("failed");
                }
            }
        });
        return promise;
    }

    /**
     * ファイルパスから波形データのパスを取得する
     * @param activity
     * @param callbackContext
     * @param audioPath
     */
    private void getWaveFormByFile(final Activity activity, final CallbackContext callbackContext, String audioPath) {
        Uri uri = Uri.parse(audioPath);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(cordova.getContext(), uri);
        String mimeType = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

        // 音声ファイルかどうか
        if (mimeType.matches("^audio.*")) {
            // wav かどうか
            if (mimeType.matches(".*wav")) {
                File file = new File(audioPath);
                // 処理をそのまま続行
                cordova.getThreadPool().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callbackContext.success(getWaveForm(file, new File(WAVEFORM_PATH)));
                        }
                        catch (Exception e) {
                            callbackContext.error("波形の取得に失敗しました。\n" + e);
                            e.printStackTrace();
                        }
                    }
                });
            }
            // wav 以外
            else {
                // 復元しないでエラーで返す
                callbackContext.error("invalid file type");
                return;
            }
        }
        else {
            // 音声ファイル以外
            callbackContext.error("音声ファイルを選択してください");
        }
    }

    private PullableSource mic() {
        return
                new PullableSource.Default(
                        new AudioRecordConfig.Default(
                                MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_16BIT,
                                AudioFormat.CHANNEL_IN_MONO, SAMPLE_RATE
                        ), BUFFER_NUM * 2
                );
    }



    // 音源の録音開始
    private void start(String path, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File audioFile = new File(path);
                    Log.v("debug", "run");

                    isRecording = true;

                    recorder = OmRecorder.wav(new PullTransport.Default(mic(), new PullTransport.OnAudioChunkPulledListener() {
                        @Override
                        public void onAudioChunkPulled(AudioChunk audioChunk) {

                            if (pushBufferCallbackContext != null) {
                                JSONArray jsonArray = new JSONArray();
                                jsonArray.put(getMaxVolume(audioChunk.toShorts()));
                                PluginResult result = new PluginResult(PluginResult.Status.OK, jsonArray);
                                result.setKeepCallback(true);
                                pushBufferCallbackContext.sendPluginResult(result);
                            }
                        }
                    }), audioFile);
                    recorder.startRecording();
                    // sample rate を送る
                    JSONObject resultData = new JSONObject();
                    resultData.put("sampleRate", SAMPLE_RATE);
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, resultData);
                    callbackContext.sendPluginResult(pluginResult);

                } catch (Exception e) {
                    callbackContext.error(e.getLocalizedMessage());
                }

            }
        });
    }

    private short getMaxVolume(final short[] shorts) {
        short max = Short.MIN_VALUE;
        for (short s : shorts) {
            if (s > max){
                max = s;
            }
        }
        return max;
    }

    private Boolean setBgm(final Activity activity, final CallbackContext callbackContext, JSONObject bgmObj) throws JSONException {
        String url = bgmObj.getString("url");
        String name = bgmObj.getString("name");
        Double volume = bgmObj.getDouble("volume");
        Boolean loop =  bgmObj.getBoolean("loop");

        if (url == null || name == null) {
            return false;
        }

        if (volume == null) {
            volume = 1.0;
        }

        if (loop == null) {
            loop = false;
        }

        CDVRecorderBgm bgm = new CDVRecorderBgm(activity.getApplicationContext(), name, url,  volume, loop);
        this.bgms.add(bgm);
        PluginResult r = new PluginResult(PluginResult.Status.OK, true);
        callbackContext.sendPluginResult(r);
        return true;
    }

    // BGM のプレイ
    private void playBgm() {
        boolean enableHeadSet = isHeadsetEnabled();
        for (CDVRecorderBgm bgm: bgms) {
            if (!enableHeadSet) {
                bgm.mute();
            }
            else {
                bgm.resignMute();
            }
            bgm.play();
        }
    }
    // ポーズの対応
    private void pauseBgm() {
        for (CDVRecorderBgm bgm: bgms) {
            bgm.pause();
        }
    }
    // シークBGM
    private Boolean seekBgm(final Activity activity, final CallbackContext callbackContext, Double secounds) {
        for(CDVRecorderBgm bgm: this.bgms) {
            bgm.seek(secounds);
        }
        PluginResult r = new PluginResult(PluginResult.Status.OK, true);
        callbackContext.sendPluginResult(r);
        return true;
    }

    private Boolean clearBgm(final Activity activity, final CallbackContext callbackContext) {
        // 一応リリースを呼ぶ
        for (CDVRecorderBgm bgm: bgms) {
            bgm.release();
        }
        // その上でお掃除
        bgms.clear();

        PluginResult r = new PluginResult(PluginResult.Status.OK, true);
        callbackContext.sendPluginResult(r);
        return true;
    }

    private Double sumList(Collection<Double> list) {
        Double result = 0.0;
        for (Double item: list) {
            result += item;
        }
        return result;
    }

    // ダウンロードBGM
    private Boolean downloadBgm() throws JSONException {
        final Activity activity = this.cordova.getActivity();
        final Context context = activity.getApplicationContext();

        // すでに fetch オブジェクトがある場合は、削除しておく
        if (fetch != null && !fetch.isClosed()) {
            if (groupFetchListener != null) {
                fetch.removeListener(groupFetchListener);
            }
            if (fetchListener != null) {
                fetch.removeListener(fetchListener);
            }
            fetch.removeAll();
            fetch.deleteAll();
            fetch.close();
            fetch = null;
        }

        // fetch object の生成
        FetchConfiguration fetchConfiguration = new FetchConfiguration.Builder(context).setDownloadConcurrentLimit(10).build();
        this.fetch = Fetch.Impl.getInstance(fetchConfiguration);

        // セットされている BGM はダウンロードしてくる
        // ダウンロードリストを生成する
        final List<Request> requests = new ArrayList<>();
        HashMap<Integer, Double> progressList = new HashMap<Integer, Double>();

        for (CDVRecorderBgm bgm: this.bgms) {
            if (bgm.hasSource) {
                break;
            }
            try {
                String url = bgm.url;
                String name = bgm.name;
                File file = File.createTempFile(name, null, context.getCacheDir());
                final Request request = new Request(url, file.getPath());
                request.setPriority(Priority.HIGH);
                request.setNetworkType(NetworkType.ALL);
                requests.add(request);
                bgm.id = request.getId();
                progressList.put(bgm.id, 0.0);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }



        fetchListener = new AbstractFetchListener() {
            @Override
            public void onProgress(@NotNull Download download, long etaInMilliSeconds, long downloadedBytesPerSecond) {
                super.onProgress(download, etaInMilliSeconds, downloadedBytesPerSecond);
                Integer progerss =  download.getProgress();
            }
        };

        JSONObject progressResult = new JSONObject();
        progressResult.put("total", (double) bgms.size());
        progressResult.put("progress", 0);

        // グループのダウンロード完了時
        groupFetchListener = new AbstractFetchGroupListener() {
            @Override
            public void onProgress(@NotNull Download download, long etaInMilliSeconds, long downloadedBytesPerSecond) {
                super.onProgress(download, etaInMilliSeconds, downloadedBytesPerSecond);
                Double progerss =  ((double) download.getProgress()) / 100;
                Log.d(TAG, "" + progerss);
                Integer id = download.getId();
                progressList.put(id, progerss);
                Double total = sumList(progressList.values());
                try {
                    progressResult.put("progress", total);
                    if (downloadProgressCallbackId != null) {
                        PluginResult r = new PluginResult(PluginResult.Status.OK, progressResult);
                        downloadProgressCallbackId.sendPluginResult(r);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }

            }
            @Override
            public void onCompleted(int groupId, @NotNull Download download, @NotNull FetchGroup fetchGroup) {
                super.onCompleted(groupId, download, fetchGroup);
                Integer id = download.getId();
                for(CDVRecorderBgm bgm: bgms) {
                    if (bgm.id.equals(id)) {
                        try {
                            bgm.setSrc(download.getFileUri());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                // 全部のダウンロードが完了した
                if (fetchGroup.getCompletedDownloads().size() >= bgms.size()) {
                    fetch.deleteAll();
                    fetch.removeAll();
                    if (!fetch.isClosed()) {
                        fetch.close();
                    }

                    PluginResult r = new PluginResult(PluginResult.Status.OK, true);
                    callbackContext.sendPluginResult(r);

                }
            }
        };

        // リスナーの登録
        fetch.addListener(this.groupFetchListener);
        fetch.addListener(this.fetchListener);

        // キューにリクエストを追加する
        this.fetch.enqueue(requests, null);
        if (bgms.size() == 0 || requests.size() == 0) {
            fetch.deleteAll();
            fetch.removeAll();
            if (!fetch.isClosed()) {
                fetch.close();
            }
            PluginResult r = new PluginResult(PluginResult.Status.OK, true);
            callbackContext.sendPluginResult(r);
        }
        return true;
    }

    private Boolean setOnDownloadBgmProgress(final Activity activity, final CallbackContext callbackContext) {
        downloadProgressCallbackId = callbackContext;
        PluginResult r = new PluginResult(PluginResult.Status.OK, true);
        callbackContext.sendPluginResult(r);
        return true;
    }

    private boolean getSampleRate(Activity activity, CallbackContext callbackContext) throws JSONException {
        JSONObject resultData = new JSONObject();
        resultData.put("sampleRate", SAMPLE_RATE);
        PluginResult r = new PluginResult(PluginResult.Status.OK, resultData);
        callbackContext.sendPluginResult(r);
        return true;
    }

    private void muteBgm() {
        for (CDVRecorderBgm bgm: bgms) {
            bgm.mute();
        }
    }

    private void resignMute() {
        for (CDVRecorderBgm bgm: bgms) {
            bgm.resignMute();
        }
    }
    private int getConnectionStatus(String action, Intent intent) {
        int state = DEFAULT_STATE;
        int normalizedState = DEFAULT_STATE;
        boolean isBT = false;
        if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
            state = intent.getIntExtra("state", DEFAULT_STATE);
        } else if (action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
            state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, DEFAULT_STATE);
            isBT = true;
        }

        if ((state == 1 && action.equals(Intent.ACTION_HEADSET_PLUG)) || (state == 2 && action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED))) {
            if (isBT)
            {
                normalizedState = BT_CONNECTED;
            }
            else
            {
                normalizedState = CONNECTED;
            }

        } else if (state == 0) {
            if (isBT)
            {
                normalizedState = BT_DISCONNECTED;
            }
            else
            {
                normalizedState = DISCONNECTED;
            }

        }

        return normalizedState;
    }

    private boolean isHeadsetEnabled() {
        Context context = this.cordova.getContext();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        if (am == null)
            return false;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return am.isWiredHeadsetOn() || am.isBluetoothScoOn() || am.isBluetoothA2dpOn();
        } else {
            AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

            for (int i = 0; i < devices.length; i++) {
                AudioDeviceInfo device = devices[i];

                if (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || device.getType() == AudioDeviceInfo.TYPE_AUX_LINE
                        || device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET
                        || device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return true;
                }
            }
        }
        return false;
    }

    private Boolean setOnChangeEarPhoneConnectedStatus(Activity activity, CallbackContext callbackContext) throws JSONException {
        changeEarPhoneConnectedStatusCallbackContext = callbackContext;
        JSONObject resultData = new JSONObject();
        resultData.put("isConnected", isHeadsetEnabled());
        PluginResult r = new PluginResult(PluginResult.Status.OK, resultData);
        r.setKeepCallback(true);
        callbackContext.sendPluginResult(r);
        return true;
    }
}