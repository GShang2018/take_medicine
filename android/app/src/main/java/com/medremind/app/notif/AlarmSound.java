package com.medremind.app.notif;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.medremind.app.MediApp;
import com.medremind.app.R;

/**
 * 闹钟发声器：
 * - playSystem：循环/单次播放系统铃声；
 * - playVoice：循环/单次播放 App 内置的语音录音。
 * 两者使用同样的循环方式，语音不依赖手机的文字转语音（TTS）引擎，离线也能用。
 */
public final class AlarmSound {
    private final Context app;
    private MediaPlayer player;

    public AlarmSound(Context context) {
        app = context.getApplicationContext();
    }

    /** 播放系统铃声；uri 为 null 时使用系统默认闹铃，播放失败时也退回默认闹铃。 */
    public void playSystem(@Nullable Uri uri, boolean loop) {
        if (playSystemExact(uri, loop)) return;

        // 自定义铃声被删除、或系统不允许读取时，不让闹钟变成“无声”，退回默认闹铃。
        Uri defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri != null && (defaultUri == null || !defaultUri.equals(uri))) {
            MediApp.log("alarm sound: fall back to default alarm");
            playSystemExact(defaultUri, loop);
        }
    }

    /** 只播放请求的铃声，失败时不做回退；供设置页试听判断铃声是否可播放。 */
    public boolean playSystemExact(@Nullable Uri uri, boolean loop) {
        stopPlayer();

        Uri target = uri;
        if (target == null) target = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (target == null) target = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (target == null) return false;
        ensureAlarmVolume();
        return startSystemPlayer(target, loop);
    }

    /** 播放内置语音录音；loop=true 时和系统闹铃一样循环播放。 */
    public void playVoice(boolean loop) {
        stopPlayer();
        ensureAlarmVolume();

        AssetFileDescriptor afd = null;
        MediaPlayer next = null;
        try {
            afd = app.getResources().openRawResourceFd(R.raw.voice_reminder);
            if (afd == null) return;
            next = new MediaPlayer();
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            next.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            next.setLooping(loop);
            next.prepare();
            next.start();
            player = next;
        } catch (Exception e) {
            MediApp.log("voice clip: play failed: " + e);
            if (next != null) {
                try {
                    next.release();
                } catch (Exception ignored) {
                }
            }
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 关闭声音并释放播放器。 */
    public void release() {
        stopPlayer();
    }

    private boolean startSystemPlayer(Uri uri, boolean loop) {
        MediaPlayer next = null;
        try {
            next = new MediaPlayer();
            next.setAudioAttributes(Notifier.alarmAttributes());
            next.setDataSource(app, uri);
            next.setLooping(loop);
            next.prepare();
            next.start();
            player = next;
            return true;
        } catch (Exception e) {
            MediApp.log("alarm sound: play failed: " + e);
            if (next != null) {
                try {
                    next.release();
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    private void stopPlayer() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    /** 闹铃音量被调到 0 时临时调到 80%，否则响铃等于没响。 */
    private void ensureAlarmVolume() {
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, Math.max(1, (int) (max * 0.8f)), 0);
            }
        } catch (Exception ignored) {
        }
    }
}
