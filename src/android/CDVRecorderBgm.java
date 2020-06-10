package jp.rabee.recorder;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;

import org.apache.cordova.LOG;

import java.io.IOException;

public class CDVRecorderBgm  implements MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener {
    private String TAG = "CDVRecorderBgm";

    public enum PlayerStates {
        NOT_READY,
        LOADING,
        READY,
        PAUSED,
        STOPPED,
        PLAYING
    };

    public PlayerStates status = PlayerStates.NOT_READY;
    private MediaPlayer player;
    public Boolean isPlaying;
    public String url;
    public String name;
    public Boolean loop = false;
    public Integer id;
    public Float volume;
    private Context context;
    private Integer offset = 0;
    public Boolean hasSource = false;


    public CDVRecorderBgm(Context context, String name, String url, Double volume ,Boolean loop) {
        this.name = name;
        this.url = url;
        this.context = context;
        this.player = new MediaPlayer();
        this.volume = volume.floatValue();
        this.player.setVolume(this.volume, this.volume);
        this.loop = loop;
        player.setLooping(loop);
    }

    public void play() {
        if (!hasSource) {
            LOG.w(TAG,"ソースがありません");
            return ;
        }
        // 用意がまだだったらバックグラウンドで再生を実行する
        if (status.equals(PlayerStates.NOT_READY)) {
            player.prepareAsync();
            status = PlayerStates.LOADING;
        }
        // 用意されているか、一時停止中の場合にはすぐにスタートさせて、シークさせる
        else if (status.equals(PlayerStates.PAUSED) || status.equals(PlayerStates.READY)) {
            seek(offset.doubleValue()/1000);
            player.start();
            status = PlayerStates.PLAYING;
        }

    }

    public void pause() {
        status = PlayerStates.PAUSED;
        player.pause();
        offset = player.getCurrentPosition();
    }

    public void resume() {
        this.play();
    }

    public void stop() {
        player.stop();
        status = PlayerStates.STOPPED;
        offset = 0;
    }

    public void seek(Double seconds) {
        Integer msec = (int) (seconds * 1000);
        // 始まっていないなら offset をアップデートするだけ
        if (status == PlayerStates.NOT_READY) {
            offset = msec;
            return ;
        }

        Integer duration = player.getDuration();

        if (loop && duration < msec) {
            offset = msec%duration;
        }
        else {
            offset = msec;
        }

        // シークする
        player.seekTo(offset);
        if (status == PlayerStates.PLAYING) {
            player.start();
        }
    }

    public void release() {
        player.release();
    }

    public void setSrc(Uri uri) throws IOException {
        player.setDataSource(this.context, uri);
        player.setOnErrorListener(this);
        player.setOnPreparedListener(this);
        hasSource = true;
    }

    // ミュート
    public void mute() {
        player.setVolume(0, 0);
    }

    // ミュート解除
    public void resignMute() {
        player.setVolume(volume, volume);
    }

    // destruction
    private void destruction() {
        // 念のため　relase する
        player.release();
    }

    // override
    @Override
    public void onPrepared(MediaPlayer play) {
        status = PlayerStates.READY;
        this.play();
    }
    @Override
    public boolean onError(MediaPlayer arg0, int arg1, int arg2) {
        return false;
    }
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destruction();
        }
    }
}
