package com.mobilecut.editor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight, fully offline single-clip video editor. */
public final class MainActivity extends Activity {
    private static final int PICK_VIDEO = 15;
    private static final int BG = 0xFF0B0D12;
    private static final int PANEL = 0xFF1B202B;
    private static final int MINT = 0xFF86F2CA;
    private static final int LIGHT = 0xFFF6F8FC;
    private static final int GRAY = 0xFF9BA5B6;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private Uri source;
    private Uri lastExport;
    private int duration;
    private int start;
    private int end;
    private boolean ready;
    private boolean seeking;
    private boolean exporting;
    private boolean destroyed;

    private VideoView video;
    private SeekBar playbar;
    private SeekBar inbar;
    private SeekBar outbar;
    private TextView mediaName;
    private TextView rangeInfo;
    private TextView timeInfo;
    private TextView status;
    private TextView playIcon;
    private TextView saveButton;
    private TextView shareButton;
    private TextView importButton;
    private ProgressBar spinner;
    private ImageView[] frames = new ImageView[8];

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (ready && !seeking && !exporting) {
                int at = video.getCurrentPosition();
                if (video.isPlaying() && at >= end - 40) {
                    video.pause();
                    video.seekTo(start);
                    at = start;
                }
                playbar.setProgress(Math.max(0, Math.min(at, duration)));
                timeInfo.setText(TrimMath.clock(at) + " / " + TrimMath.clock(duration));
                playIcon.setText(video.isPlaying() ? "Ⅱ" : "▶");
            }
            handler.postDelayed(this, 150);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        draw();
        handler.post(ticker);
        if (saved != null) {
            String uri = saved.getString("uri");
            if (uri != null) load(Uri.parse(uri), saved.getInt("start"), saved.getInt("end"));
        }
    }

    private void draw() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(24), dp(18), dp(40));
        scroll.addView(content);
        setContentView(scroll);

        LinearLayout titleRow = horizontal();
        TextView title = text("MobileCut", 28, LIGHT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text("BETA 0.0.1", 11, MINT, true);
        badge.setPadding(dp(10), dp(8), dp(10), dp(8));
        badge.setBackground(round(0xFF213E35, 24));
        titleRow.addView(badge);
        content.addView(titleRow);
        TextView subtitle = text("留住精彩，剪掉多余。", 13, GRAY, false);
        space(content, subtitle, 7);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(round(0xFF131820, 18));
        stage.setClipToOutline(true);
        video = new VideoView(this);
        stage.addView(video, new FrameLayout.LayoutParams(-1, -1));
        TextView placeholder = text("▶\n\n选择一段视频\n开启创作", 16, GRAY, false);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setOnClickListener(view -> pick());
        stage.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout touch = new FrameLayout(this);
        touch.setClickable(true);
        touch.setVisibility(View.GONE);
        touch.setOnClickListener(view -> toggle());
        playIcon = text("▶", 24, MINT, true);
        playIcon.setGravity(Gravity.CENTER);
        playIcon.setBackground(round(0xCC132D26, 100));
        touch.addView(playIcon, new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER));
        stage.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-1, dp(238));
        stageLp.topMargin = dp(20);
        content.addView(stage, stageLp);
        video.setOnPreparedListener(player -> {
            ready = true;
            player.setLooping(false);
            video.seekTo(start);
            video.pause();
        });
        video.setOnCompletionListener(player -> {
            video.seekTo(start);
            playIcon.setText("▶");
        });
        video.setOnErrorListener((player, what, extra) -> {
            status.setText("预览失败，可能是设备不支持该视频编码");
            return true;
        });

        mediaName = text("尚未导入素材", 15, LIGHT, true);
        mediaName.setSingleLine(true);
        mediaName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mediaName.setPadding(dp(14), dp(16), dp(14), dp(16));
        mediaName.setBackground(round(PANEL, 12));
        space(content, mediaName, 16);

        timeInfo = text("00:00 / 00:00", 13, GRAY, true);
        space(content, timeInfo, 16);
        playbar = new SeekBar(this);
        playbar.setEnabled(false);
        tint(playbar);
        content.addView(playbar);
        playbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { seeking = true; if (ready) video.pause(); }
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && ready) {
                    int selected = (int) TrimMath.clamp(value, start, end);
                    video.seekTo(selected);
                    timeInfo.setText(TrimMath.clock(selected) + " / " + TrimMath.clock(duration));
                }
            }
            public void onStopTrackingTouch(SeekBar bar) { seeking = false; }
        });

        space(content, text("裁剪片段", 19, LIGHT, true), 19);
        LinearLayout strip = horizontal();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < frames.length; i++) {
            ImageView frame = new ImageView(this);
            frame.setBackgroundColor(i % 2 == 0 ? 0xFF303846 : 0xFF242A37);
            frame.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frames[i] = frame;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(72), 1);
            params.rightMargin = dp(2);
            strip.addView(frame, params);
        }
        space(content, strip, 12);

        TextView inLabel = text("起点 · 拖动滑块", 13, GRAY, false);
        space(content, inLabel, 16);
        inbar = new SeekBar(this);
        tint(inbar);
        inbar.setEnabled(false);
        content.addView(inbar);
        TextView outLabel = text("终点 · 拖动滑块", 13, GRAY, false);
        space(content, outLabel, 8);
        outbar = new SeekBar(this);
        tint(outbar);
        outbar.setEnabled(false);
        content.addView(outbar);
        inbar.setOnSeekBarChangeListener(new RangeListener(true));
        outbar.setOnSeekBarChangeListener(new RangeListener(false));

        rangeInfo = text("起点 00:00    终点 00:00    时长 00:00", 13, LIGHT, true);
        rangeInfo.setGravity(Gravity.CENTER);
        rangeInfo.setPadding(dp(9), dp(17), dp(9), dp(17));
        rangeInfo.setBackground(round(PANEL, 12));
        space(content, rangeInfo, 10);

        saveButton = button("导出到相册", MINT, 0xFF102622);
        saveButton.setOnClickListener(view -> export());
        space(content, saveButton, 21);
        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        spinLp.gravity = Gravity.CENTER;
        spinLp.topMargin = dp(10);
        content.addView(spinner, spinLp);
        shareButton = button("分享最近导出的视频", 0xFF284C3F, MINT);
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(view -> share());
        space(content, shareButton, 12);
        importButton = button("＋ 选择手机视频", PANEL, MINT);
        importButton.setOnClickListener(view -> { if (!exporting) pick(); });
        space(content, importButton, 14);
        status = text("视频仅在手机本地处理，不上传网络。", 12, GRAY, false);
        status.setGravity(Gravity.CENTER);
        space(content, status, 14);
        stage.setOnClickListener(view -> { if (source == null) pick(); });
        // A touch layer prevents playback controls from stealing the pick gesture.
        touch.setTag(placeholder);
        touch.setOnClickListener(view -> toggle());
        placeholder.setTag(touch);
    }

    private class RangeListener implements SeekBar.OnSeekBarChangeListener {
        private final boolean isStart;
        RangeListener(boolean isStart) { this.isStart = isStart; }
        public void onStartTrackingTouch(SeekBar bar) { if (ready) video.pause(); }
        public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            if (!fromUser || source == null || exporting) return;
            if (isStart) {
                start = (int) TrimMath.clamp(value, 0, Math.max(0, end - 500));
                if (bar.getProgress() != start) bar.setProgress(start);
            } else {
                end = (int) TrimMath.clamp(value, start + 500, duration);
                if (bar.getProgress() != end) bar.setProgress(end);
            }
            refreshRange();
            if (ready) video.seekTo(isStart ? start : Math.max(start, end - 1));
        }
        public void onStopTrackingTouch(SeekBar bar) { }
    }

    private void refreshRange() {
        rangeInfo.setText("起点 " + TrimMath.clock(start) + "    终点 " +
                TrimMath.clock(end) + "    时长 " + TrimMath.clock(end - start));
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_VIDEO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK ||
                data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
        load(uri, 0, 0);
    }

    private void load(Uri uri, int savedStart, int savedEnd) {
        int length;
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(this, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationLong = Long.parseLong(value);
            if (durationLong < 500 || durationLong > Integer.MAX_VALUE)
                throw new IllegalArgumentException("视频时长不支持");
            length = (int) durationLong;
        } catch (Exception error) {
            toast("无法读取视频，请使用常见 MP4 文件");
            return;
        }
        generation.incrementAndGet();
        source = uri;
        lastExport = null;
        shareButton.setVisibility(View.GONE);
        duration = length;
        start = (int) TrimMath.clamp(savedStart, 0, duration - 500);
        end = savedEnd > start + 500 ? (int) TrimMath.clamp(savedEnd, start + 500, duration) : duration;
        ready = false;
        playbar.setEnabled(true);
        inbar.setEnabled(true);
        outbar.setEnabled(true);
        playbar.setMax(duration);
        inbar.setMax(duration);
        outbar.setMax(duration);
        inbar.setProgress(start);
        outbar.setProgress(end);
        refreshRange();
        mediaName.setText(fileName(uri));
        timeInfo.setText("00:00 / " + TrimMath.clock(duration));
        status.setText("选择起点和终点，导出后会保存在 电影 / MobileCut。");
        FrameLayout touch = (FrameLayout) playIcon.getParent();
        ((View)touch.getTag()).setVisibility(View.GONE);
        touch.setVisibility(View.VISIBLE);
        video.setVideoURI(uri);
        loadThumbnails(uri, length);
    }

    private String fileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null)
                return cursor.getString(0);
        } catch (Exception ignored) { }
        return "本地视频";
    }

    private void loadThumbnails(Uri uri, int length) {
        int requested = generation.get();
        for (ImageView frame : frames) frame.setImageDrawable(null);
        thumbnails.execute(() -> {
            Bitmap[] bitmaps = new Bitmap[frames.length];
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(this, uri);
                for (int i = 0; i < bitmaps.length; i++) {
                    if (requested != generation.get()) return;
                    long microseconds = ((long) length * i / bitmaps.length) * 1000;
                    bitmaps[i] = retriever.getScaledFrameAtTime(microseconds,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90);
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (destroyed || requested != generation.get()) return;
                for (int i = 0; i < frames.length; i++) frames[i].setImageBitmap(bitmaps[i]);
            });
        });
    }

    private void toggle() {
        if (!ready || exporting) return;
        if (video.isPlaying()) video.pause();
        else {
            int position = video.getCurrentPosition();
            if (position < start || position >= end - 100) video.seekTo(start);
            video.start();
        }
        playIcon.setText(video.isPlaying() ? "Ⅱ" : "▶");
    }

    private void export() {
        if (source == null) { toast("请先选择视频"); return; }
        if (exporting) return;
        Uri input = source;
        int from = start, to = end;
        video.pause();
        exporting = true;
        spinner.setVisibility(View.VISIBLE);
        saveButton.setEnabled(false);
        importButton.setEnabled(false);
        inbar.setEnabled(false);
        outbar.setEnabled(false);
        status.setText("正在本地导出视频，请保持应用打开…");
        worker.execute(() -> {
            try {
                Uri result = VideoTrimmer.trimToMovies(getApplicationContext(), input, from, to);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    finishExport();
                    lastExport = result;
                    shareButton.setVisibility(View.VISIBLE);
                    status.setText("已保存到相册的 电影 / MobileCut 文件夹");
                    toast("导出成功");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    finishExport();
                    status.setText("导出失败：" + (error.getMessage() == null ?
                            "视频格式不兼容" : error.getMessage()));
                    toast("视频导出失败");
                });
            }
        });
    }

    private void finishExport() {
        exporting = false;
        spinner.setVisibility(View.GONE);
        saveButton.setEnabled(true);
        importButton.setEnabled(true);
        inbar.setEnabled(true);
        outbar.setEnabled(true);
    }

    private void share() {
        if (lastExport == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_STREAM, lastExport);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享视频"));
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private void space(LinearLayout parent, View view, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginTop);
        parent.addView(view, params);
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private GradientDrawable round(int background, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }
    private TextView button(String label, int background, int foreground) {
        TextView view = text(label, 15, foreground, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(background, 14));
        view.setClickable(true);
        view.setMinHeight(dp(52));
        return view;
    }
    private void tint(SeekBar bar) {
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(MINT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF444B56));
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    @Override public void onSaveInstanceState(Bundle state) {
        if (source != null) {
            state.putString("uri", source.toString());
            state.putInt("start", start);
            state.putInt("end", end);
        }
        super.onSaveInstanceState(state);
    }
    @Override protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        generation.incrementAndGet();
        handler.removeCallbacks(ticker);
        thumbnails.shutdownNow();
        worker.shutdown();
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
