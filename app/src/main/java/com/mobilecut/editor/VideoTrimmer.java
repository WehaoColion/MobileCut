package com.mobilecut.editor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offline keyframe-aligned MP4 stream copy, without transcoding or network use. */
public final class VideoTrimmer {
    private VideoTrimmer() { }

    public static Uri trimToMovies(Context context, Uri input, long startMs, long endMs)
            throws IOException {
        if (input == null || startMs < 0 || endMs - startMs < TrimMath.MIN_CLIP_MS) {
            throw new IOException("裁剪区间无效");
        }
        ContentResolver resolver = context.getContentResolver();
        MediaExtractor reader = new MediaExtractor();
        MediaExtractor keyReader = new MediaExtractor();
        Uri output = null;
        try {
            reader.setDataSource(context, input, null);
            keyReader.setDataSource(context, input, null);
            int videoIndex = -1;
            int sampleCapacity = 16 * 1024 * 1024;
            List<Integer> selected = new ArrayList<>();
            for (int i = 0; i < reader.getTrackCount(); i++) {
                MediaFormat format = reader.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) {
                    if (videoIndex >= 0) continue;
                    videoIndex = i;
                } else if (!mime.startsWith("audio/")) continue;
                selected.add(i);
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    sampleCapacity = Math.max(sampleCapacity,
                            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            if (videoIndex < 0) throw new IOException("未发现视频轨道");
            if (sampleCapacity > 64 * 1024 * 1024) throw new IOException("视频帧过大");
            keyReader.selectTrack(videoIndex);
            keyReader.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long actualStart = keyReader.getSampleTime();
            if (actualStart < 0) throw new IOException("无法定位视频关键帧");
            for (int i : selected) reader.selectTrack(i);
            reader.seekTo(actualStart, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,
                    String.format(Locale.US, "MobileCut_%d.mp4", System.currentTimeMillis()));
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MobileCut");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            output = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (output == null) throw new IOException("无法在相册创建文件");

            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(output, "w")) {
                if (descriptor == null) throw new IOException("相册文件无法写入");
                MediaMuxer muxer = new MediaMuxer(descriptor.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                boolean started = false;
                try {
                    int[] mapping = new int[reader.getTrackCount()];
                    java.util.Arrays.fill(mapping, -1);
                    for (int i : selected) mapping[i] = muxer.addTrack(reader.getTrackFormat(i));
                    try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                        retriever.setDataSource(context, input);
                        String angle = retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                        if (angle != null) {
                            int degrees = Integer.parseInt(angle);
                            if (degrees == 0 || degrees == 90 ||
                                    degrees == 180 || degrees == 270) {
                                muxer.setOrientationHint(degrees);
                            }
                        }
                    } catch (RuntimeException ignored) { }
                    muxer.start();
                    started = true;
                    ByteBuffer buffer = ByteBuffer.allocateDirect(sampleCapacity);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    long[] lastPts = new long[reader.getTrackCount()];
                    java.util.Arrays.fill(lastPts, -1);
                    boolean wroteVideo = false;
                    while (true) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("处理已取消");
                        int track = reader.getSampleTrackIndex();
                        if (track < 0) break;
                        long pts = reader.getSampleTime();
                        if (pts >= endMs * 1000) break;
                        if (pts < actualStart || mapping[track] < 0) {
                            reader.advance();
                            continue;
                        }
                        boolean key = (reader.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                        if (track == videoIndex && !wroteVideo && !key) {
                            reader.advance();
                            continue;
                        }
                        buffer.clear();
                        int size = reader.readSampleData(buffer, 0);
                        if (size < 0) break;
                        if (size == 0 || pts - actualStart <= lastPts[track]) {
                            reader.advance();
                            continue;
                        }
                        info.set(0, size, pts - actualStart,
                                key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        muxer.writeSampleData(mapping[track], buffer, info);
                        lastPts[track] = info.presentationTimeUs;
                        if (track == videoIndex) wroteVideo = true;
                        reader.advance();
                    }
                    if (!wroteVideo) throw new IOException("片段缺少有效视频关键帧");
                } finally {
                    try {
                        if (started) muxer.stop();
                    } finally {
                        muxer.release();
                    }
                }
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (resolver.update(output, publish, null, null) <= 0) {
                throw new IOException("导出视频发布失败");
            }
            return output;
        } catch (Exception error) {
            if (output != null) {
                try { resolver.delete(output, null, null); }
                catch (RuntimeException ignored) { }
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(error.getMessage() == null ? "视频格式不受支持" :
                    error.getMessage(), error);
        } finally {
            reader.release();
            keyReader.release();
        }
    }
}
