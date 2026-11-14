package ai.motionwall.cars;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Local MP4 clips for real video wallpapers, independent of photo presets. */
public final class VideoClipStore {
    public static final String VIDEO_KEY = "video_filename";
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private VideoClipStore() {}

    public static void importVideo(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("Видео не выбрано");
        File next = new File(context.getFilesDir(), "clip_" + System.nanoTime() + ".mp4");
        try {
            long size=0;
            try (InputStream in=context.getContentResolver().openInputStream(uri);
                 FileOutputStream out=new FileOutputStream(next)) {
                if (in == null) throw new IOException("Не удалось прочитать MP4");
                byte[] buf=new byte[32768];
                int n;
                while ((n=in.read(buf))!=-1) {
                    size+=n;
                    if (size>MAX_BYTES) throw new IOException("MP4 больше 50 МБ. Выбери короткий ролик.");
                    out.write(buf,0,n);
                }
                out.getFD().sync();
            }
            if(size<4096)throw new IOException("MP4 пустой или повреждён");
            // Probe the actual file: a renamed .jpg must never be accepted as video.
            MediaMetadataRetriever check=new MediaMetadataRetriever();
            try {
                check.setDataSource(next.getAbsolutePath());
                String width=check.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String height=check.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String length=check.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                int w=Integer.parseInt(width==null?"0":width);
                int h=Integer.parseInt(height==null?"0":height);
                long ms=Long.parseLong(length==null?"0":length);
                if(w<64||h<64||ms<1000||ms>30000)
                    throw new IOException("Нужно настоящее видео длительностью 1–30 секунд.");
            } finally { check.release(); }
            SharedPreferences pref=context.getSharedPreferences(PhotoStore.PREFS,Context.MODE_PRIVATE);
            String previous=pref.getString(VIDEO_KEY,null);
            if(!pref.edit().putString(VIDEO_KEY,next.getName()).commit())
                throw new IOException("Не удалось сохранить видео.");
            if(previous!=null&&previous.matches("clip_[0-9]+\\.mp4"))
                new File(context.getFilesDir(),previous).delete();
        } catch(Exception err) {
            next.delete();
            if(err instanceof IOException)throw (IOException)err;
            throw new IOException("Невозможно подготовить MP4: "+err.getMessage(),err);
        }
    }
    public static File get(Context context) {
        String name=context.getSharedPreferences(PhotoStore.PREFS,Context.MODE_PRIVATE)
            .getString(VIDEO_KEY,null);
        if(name==null||!name.matches("clip_[0-9]+\\.mp4"))return null;
        File f=new File(context.getFilesDir(),name);
        return f.isFile()&&f.length()>0?f:null;
    }
}
