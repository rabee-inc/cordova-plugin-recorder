package jp.snuffy.rec;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaRecorder;

import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import com.otaliastudios.transcoder.engine.TrackType;
import com.otaliastudios.transcoder.sink.DataSink;
import com.otaliastudios.transcoder.sink.DefaultDataSink;
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.jdeferred2.Deferred;
import org.jdeferred2.DoneCallback;
import org.jdeferred2.Promise;
import org.jdeferred2.impl.DeferredObject;
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
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import nl.bravobit.ffmpeg.ExecuteBinaryResponseHandler;
import nl.bravobit.ffmpeg.FFmpeg;
import omrecorder.AudioChunk;
import omrecorder.AudioRecordConfig;
import omrecorder.OmRecorder;
import omrecorder.PullTransport;
import omrecorder.PullableSource;
import omrecorder.Recorder;

public class CDVRec extends CordovaPlugin {

    private static final String TAG = CDVRec.class.getSimpleName();
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


    public void initialize(CordovaInterface cordova, CordovaWebView webView) {

        ActivityCompat.requestPermissions(cordova.getActivity(), new String[]{Manifest.permission.RECORD_AUDIO}, VOICE_PERMISSION_REQUEST_CODE);


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
        } else if (action.equals("initSettings")) {
            // TODO: 設定を書く
            callbackContext.success("ok");
            return true;
        } else {
            return false;
        }

    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        super.onActivityResult(requestCode, resultCode, intent);
    }


    public void startRecording(final Activity activity, final CallbackContext callbackContext) {
        // 処理
        currentAudioId = null;
        sequences = new ArrayList<File>();
        start(currentAudioId, callbackContext);
    }

    public void pauseRecording(final Activity activity, final CallbackContext callbackContext) {
        try {
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
            start(currentAudioId, callbackContext);
            callbackContext.success("ok");
        }
    }


    public void stopRecording(final Activity activity, final CallbackContext callbackContext) {
        try {
            recorder.stopRecording();
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

            DefaultAudioStrategy strategy = DefaultAudioStrategy.builder().channels(1).sampleRate(44100).build();

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
                                AudioFormat.CHANNEL_IN_MONO, 44100
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
                    callbackContext.success("ok");

                } catch (Exception e) {
                    callbackContext.error(e.getLocalizedMessage());
                }

            }
        });
    }

}