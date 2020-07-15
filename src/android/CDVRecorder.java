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
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.view.animation.AccelerateInterpolator;
import androidx.core.content.ContextCompat;


import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import com.otaliastudios.transcoder.engine.TrackType;
import com.otaliastudios.transcoder.sink.DataSink;
import com.otaliastudios.transcoder.sink.DefaultDataSink;
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;

import org.apache.cordova.*;


import org.jdeferred2.Deferred;
import org.jdeferred2.DoneCallback;
import org.jdeferred2.Promise;
import org.jdeferred2.impl.DeferredObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;


import nl.bravobit.ffmpeg.ExecuteBinaryResponseHandler;
import nl.bravobit.ffmpeg.FFmpeg;
import omrecorder.AudioChunk;
import omrecorder.AudioRecordConfig;
import omrecorder.OmRecorder;
import omrecorder.PullTransport;
import omrecorder.PullableSource;
import omrecorder.Recorder;

import com.tonyodev.fetch2.*;
import com.tonyodev.fetch2.Error;
import com.tonyodev.fetch2core.DownloadBlock;
import com.tonyodev.fetch2.Request;



public class CDVRecorder extends CordovaPlugin {

    private static final String TAG = CDVRecorder.class.getSimpleName();
    // media settings
    private static final int SAMPLE_RATE = 44100;
    private static final int SAMPLE_RATE_INDEX = 4;
    private static final int CHANNELS = 1;
    private static final int BIT_RATE = 32000;

    private static final int VOICE_PERMISSION_REQUEST_CODE = 100;

    private int bufferSize;
    private MediaCodec mediaCodec;
    private AudioRecord audioRecord;
    private OutputStream outputStream;

    private File file;

    private Recorder recorder;

    private String RECORDING_ROOT_DIR;
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

        // root フォルダーのチェック
        RECORDING_ROOT_DIR = cordova.getContext().getFilesDir() + "/recording";
        // root フォルダーの存在チェック
        File file = new File(RECORDING_ROOT_DIR);
        if (!file.exists()) {
            file.mkdir();
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
            String audioId = args.get(0).toString();
            cordova.setActivityResultCallback(this);
            removeFolder(activity, callbackContext, audioId);
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

//    @Override
//    public void onRequestPermissionResult(int requestCode, List<String> permissions, List<int> grantResults) {
//
//    }


    
    

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        super.onActivityResult(requestCode, resultCode, intent);
    }


    public void startRecording(final Activity activity, final CallbackContext callbackContext) {
        // 処理
        currentAudioId = null;
        sequences = new ArrayList<File>();
        playBgm();
        start(currentAudioId, callbackContext);

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
        // currentAudio がなければ
        if (currentAudioId == null) {
            callbackContext.error("not initialize audio");
        } else {
            playBgm();
            start(currentAudioId, callbackContext);
            callbackContext.success("ok");
        }
    }


    public void stopRecording(final Activity activity, final CallbackContext callbackContext) {
        try {
            recorder.stopRecording();
            pauseBgm();
            // 処理
            isRecording = false;
            currentAudioId = null;
            sequences = new ArrayList<File>();
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
                mergeAudio().done(new DoneCallback<File>() {
                    @Override
                    public void onDone(File file) {
                        try {
                            fullAudio.put("path", "file://" + file.getAbsoluteFile());
                            audioData.put("full_audio", fullAudio);
                            PluginResult result = new PluginResult(PluginResult.Status.OK, audioData);
                            callbackContext.sendPluginResult(result);
                        } catch (Exception e) {

                        }
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


    private void getAudio(final Activity activity, final CallbackContext callbackContext, final String audioId) {
        File inputFile = new File(RECORDING_ROOT_DIR + "/" + audioId + "/merged/merged.wav");

        JSONObject audioData = new JSONObject();
        JSONObject fullAudio = new JSONObject();

        try {
            fullAudio.put("path", "file://" + inputFile.getAbsoluteFile());
            audioData.put("full_audio", fullAudio);

            callbackContext.success(audioData);

        } catch (Exception e) {

        }
    }

    // 録音したファイルをマージする
    private Promise mergeAudio() {

        File currentAudioFolder = getCurrentAudioFolder();
        ArrayList<String> commands = new ArrayList<String>();
        File outputDir = new File(currentAudioFolder.getAbsolutePath() + "/merged" + "/temp-merged.wav");
        File mergedFile = new File(currentAudioFolder.getAbsolutePath() + "/merged" + "/merged.wav");

        int concatAudioCounter = 0;

        // success と finish を発火させるのに必要
        commands.add("-y");

        // すでに 録音している音声があった場合はマージする最初に追加
        if (mergedFile.exists()) {
            commands.add("-i");
            commands.add(mergedFile.getAbsolutePath());
            concatAudioCounter++;
        }

        if (!outputDir.getParentFile().exists()) {
            outputDir.getParentFile().mkdir();
        }

        // sequences に入ってる音声データをマージ
        for (int i = 0; i < sequences.size(); i++) {
            commands.add("-i");
            commands.add(sequences.get(i).getAbsolutePath());
            concatAudioCounter++;
        }
        if (concatAudioCounter > 0) {
            commands.add("-filter_complex");
            commands.add("concat=n=" + concatAudioCounter + ":v=0:a=1");
        }


        // 出力先の指定
        String outputPath = outputDir.getAbsolutePath();
        commands.add(outputPath);

        String[] command = commands.toArray(new String[commands.size()]);

        Deferred deferred = new DeferredObject();
        Promise promise = deferred.promise();

        FFmpeg ffmpeg = FFmpeg.getInstance(cordova.getContext());
        if (ffmpeg.isSupported()) {
            ffmpeg.execute(command, new ExecuteBinaryResponseHandler() {
                @Override
                public void onStart() {
                    LOG.v(TAG, "start");
                }

                @Override
                public void onProgress(String message) {
                    LOG.v(TAG, message);
                }

                @Override
                public void onFailure(String message) {
                    LOG.v(TAG, message);

                }

                @Override
                public void onSuccess(String message) {
                    LOG.v(TAG, message);
                }

                @Override
                public void onFinish() {
                    LOG.v(TAG, "finish");

                    // temp-merged -> merged
                    if (mergedFile.exists()) {
                        mergedFile.delete();
                    }

                    mergedFile.getParentFile().mkdir();

                    File newMergedFile = new File(mergedFile.getAbsolutePath());

                    outputDir.renameTo(newMergedFile);
                    // 削除
                    for (int i = 0; i < sequences.size(); i++) {
                        sequences.get(i).delete();
                    }
                    sequences.clear();
                    sequences = new ArrayList<File>();

                    deferred.resolve(newMergedFile);
                }
            });
        }

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
        String id = this.currentAudioId;
        this.currentAudioId = null;
        removeFolder(id);
        callbackContext.success("succss");
    }


    private void removeFolder(final Activity activity, final CallbackContext callbackContext, final String id) {
        removeFolder(id);
        callbackContext.success("success");
    }

    private void removeFolder(String id) {
        File dir = new File(RECORDING_ROOT_DIR + "/" + id);
        if (dir.exists()) {
            String deleteCmd = "rm -r " + dir.getAbsolutePath();
            Runtime runtime = Runtime.getRuntime();
            try {
                runtime.exec(deleteCmd);
            } catch (IOException e) {
            }
        }
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
        FFmpeg ffmpeg = FFmpeg.getInstance(cordova.getContext());
        if (ffmpeg.isSupported()) {
            ffmpeg.execute(command, new ExecuteBinaryResponseHandler() {
                @Override
                public void onStart() {
                    LOG.v(TAG, "start");
                }

                @Override
                public void onProgress(String message) {
                    LOG.v(TAG, message);
                }

                @Override
                public void onFailure(String message) {
                    LOG.v(TAG, message);

                }

                @Override
                public void onSuccess(String message) {
                    LOG.v(TAG, message);
                }

                @Override
                public void onFinish() {

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


                }
            });
        }
    }

    private void importAudio(final Activity activity, final CallbackContext callbackContext, String audioPath) {
        File originalPath;
        if (audioPath != null) {

            String path = audioPath.replace("file://", "");

            originalPath = new File(path);

            currentAudioId = getNewAudioId();
            File newMergedParentFile = new File(RECORDING_ROOT_DIR + "/" + currentAudioId);
            if (!newMergedParentFile.exists()) {
                newMergedParentFile.mkdir();
            }
            File newMergedFile = new File(RECORDING_ROOT_DIR + "/" + currentAudioId + "/merged/merged.wav");
            File mergedPath = new File(newMergedFile.getAbsolutePath());
            if (!mergedPath.getParentFile().exists()) {
                mergedPath.getParentFile().mkdir();
            }

            JSONObject fullAudio = new JSONObject();
            JSONObject audioData = new JSONObject();

            try {
                originalPath.renameTo(mergedPath);
                fullAudio.put("path", "file://" + newMergedFile.getAbsoluteFile());
                audioData.put("full_audio", fullAudio);
                audioData.put("folder_id", currentAudioId);

                PluginResult result = new PluginResult(PluginResult.Status.OK, audioData);
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


        File tempwaveform = new File(RECORDING_ROOT_DIR + "/" + currentAudioId + "/tempwaveform/temppcmbuffer");
        if (!tempwaveform.getParentFile().exists()) {
            tempwaveform.getParentFile().mkdir();
        }

        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {

                    InputStream input = new FileInputStream(file);

                    input.skip(36);
                    byte[] chunkID = new byte[4];
                    input.read(chunkID);
                    LOG.e(TAG, new String(chunkID, "UTF-8"));
                    if (new String(chunkID, "UTF-8").equals("LIST")) {
                        byte[] chunkSize = new byte[4];
                        input.read(chunkSize);
                        int chunkSizeInt = chunkSize[0] + (chunkSize[1] << 8) + (chunkSize[2] << 16) + (chunkSize[3] << 24);
                        input.skip(chunkSizeInt + 4 + 4);
                    }
                    // chunkID が data と仮定
                    else {
                        input.skip(4);
                    }
                    byte[] data = new byte[input.available()];
                    input.read(data);
                    input.close();
                    try {

                        OutputStream output = new FileOutputStream(tempwaveform);
                        output.write(data);
                        output.close();
                        callbackContext.success("file://" + tempwaveform.getAbsolutePath());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });


    }


    private PullableSource mic() {
        return
                new PullableSource.Default(
                        new AudioRecordConfig.Default(
                                MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_16BIT,
                                AudioFormat.CHANNEL_IN_MONO, SAMPLE_RATE
                        ), 4096 * 4
                );
    }



    // 音源の録音開始
    private void start(String audioId, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {

                    if (audioId != null) {
                        currentAudioId = audioId;
                    } else {
                        currentAudioId = getNewAudioId();
                    }

                    String sequencePath = RECORDING_ROOT_DIR + "/" + currentAudioId + "/" + "sequences";
                    File sequenseDir = new File(sequencePath);

                    // フォルダーがなければ生成する
                    if (!sequenseDir.exists()) {
                        if (!sequenseDir.getParentFile().exists()) {
                            sequenseDir.getParentFile().mkdir();
                        }
                        sequenseDir.mkdir();
                    }

                    // 実際に録音するファイル
                    File audio = File.createTempFile("sequence", ".wav", sequenseDir);

                    // シーケンスに追加
                    sequences.add(audio);

                    isRecording = true;

                    recorder = OmRecorder.wav(new PullTransport.Default(mic(), new PullTransport.OnAudioChunkPulledListener() {
                        @Override
                        public void onAudioChunkPulled(AudioChunk audioChunk) {

                            if (pushBufferCallbackContext != null) {
                                PluginResult result = new PluginResult(PluginResult.Status.OK, audioChunk.toBytes());
                                result.setKeepCallback(true);
                                pushBufferCallbackContext.sendPluginResult(result);
                            }
                        }
                    }), audio);
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