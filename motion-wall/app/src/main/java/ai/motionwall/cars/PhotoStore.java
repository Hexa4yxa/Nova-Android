package ai.motionwall.cars;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.Size;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Copies an explicitly selected photo to private storage; never needs a gallery permission. */
public final class PhotoStore {
    public static final String PREFS="wallpaper";
    public static final String IMAGE_KEY="image_filename";
    private PhotoStore(){}
    public static void importPhoto(Context context,Uri uri) throws IOException {
        if(uri==null)throw new IOException("Фото не выбрано");
        Bitmap decoded=null;
        File destination=null;
        try {
            ImageDecoder.Source source=ImageDecoder.createSource(context.getContentResolver(),uri);
            decoded=ImageDecoder.decodeBitmap(source,(decoder,info,src)->{
                Size s=info.getSize();
                if(s.getWidth()<1||s.getHeight()<1)throw new IllegalArgumentException("Размер фото неизвестен");
                float scale=Math.min(1f,1800f/Math.max(s.getWidth(),s.getHeight()));
                decoder.setTargetSize(Math.max(1,Math.round(s.getWidth()*scale)),
                                      Math.max(1,Math.round(s.getHeight()*scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            destination=new File(context.getFilesDir(),"wall_"+System.nanoTime()+".jpg");
            try(FileOutputStream out=new FileOutputStream(destination)){
                if(!decoded.compress(Bitmap.CompressFormat.JPEG,91,out))
                    throw new IOException("Не удалось сохранить фотографию");
                out.getFD().sync();
            }
            if(destination.length()==0)throw new IOException("Файл фотографии пуст");
            SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            String previous=prefs.getString(IMAGE_KEY,null);
            if(!prefs.edit().putString(IMAGE_KEY,destination.getName()).commit())
                throw new IOException("Не удалось сохранить фотографию");
            if(previous!=null&&previous.startsWith("wall_")&&previous.endsWith(".jpg"))
                new File(context.getFilesDir(),previous).delete();
        }catch(Exception e){
            if(destination!=null)destination.delete();
            if(e instanceof IOException)throw (IOException)e;
            throw new IOException("Ошибка чтения фото: "+e.getMessage(),e);
        }finally {if(decoded!=null)decoded.recycle();}
    }
    public static Bitmap load(Context context){
        String filename=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getString(IMAGE_KEY,null);
        if(filename==null||!filename.matches("wall_[0-9]+\\.jpg"))return null;
        File file=new File(context.getFilesDir(),filename);
        if(!file.isFile())return null;
        try{return BitmapFactory.decodeFile(file.getAbsolutePath());}
        catch(Exception ignored){return null;}
    }
}
