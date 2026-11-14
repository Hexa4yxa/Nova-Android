package ai.motionwall.cars;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * FREE on-device cutout for a 2.5D sprite mode.
 * This does NOT create photorealistic AI video. It isolates the car so the
 * subject can move independently of a procedural road and skyline.
 * The ML Kit model may need a one-time download through Google Play services.
 */
public final class CarCutoutStore {
    public static final String CUTOUT_KEY="car_cutout_filename";
    public static final String SOURCE_KEY="car_cutout_source";
    private CarCutoutStore(){}

    public static Bitmap load(Context context) {
        SharedPreferences preferences=context.getSharedPreferences(PhotoStore.PREFS,Context.MODE_PRIVATE);
        String source=preferences.getString(SOURCE_KEY,null);
        String active=preferences.getString(PhotoStore.IMAGE_KEY,null);
        if(source==null||!source.equals(active))return null;
        String filename=preferences.getString(CUTOUT_KEY,null);
        if(filename==null||!filename.matches("cutout_[0-9]+\\.png"))return null;
        File file=new File(context.getFilesDir(),filename);
        if(!file.isFile())return null;
        try{return BitmapFactory.decodeFile(file.getAbsolutePath());}
        catch(Exception ignored){return null;}
    }
    public static boolean ready(Context context){
        SharedPreferences preferences=context.getSharedPreferences(PhotoStore.PREFS,Context.MODE_PRIVATE);
        String active=preferences.getString(PhotoStore.IMAGE_KEY,null);
        String source=preferences.getString(SOURCE_KEY,null);
        String name=preferences.getString(CUTOUT_KEY,null);
        return active!=null&&active.equals(source)&&name!=null
            &&name.matches("cutout_[0-9]+\\.png")
            &&new File(context.getFilesDir(),name).isFile();
    }

    /** Always invoke callbacks on Android main thread; keep the old cutout on failure. */
    public static void generate(Context context, Runnable success, Consumer<String> failure){
        Context app=context.getApplicationContext();
        new Thread(()->{
            Bitmap full=PhotoStore.load(app);
            if(full==null) {
                android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
                handler.post(()->failure.accept("Сначала выбери фото автомобиля"));
                return;
            }
            SharedPreferences prefs=app.getSharedPreferences(PhotoStore.PREFS,Context.MODE_PRIVATE);
            String initial=prefs.getString(PhotoStore.IMAGE_KEY,null);
            int w=full.getWidth(),h=full.getHeight();
            float scale=Math.min(1f,1024f/Math.max(w,h));
            Bitmap small=full;
            if(scale<1f)small=Bitmap.createScaledBitmap(full,
                Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),true);
            if(small!=full)full.recycle();
            final Bitmap bitmap=small;
            SubjectSegmenterOptions options=new SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap().build();
            final SubjectSegmenter segmenter=SubjectSegmentation.getClient(options);
            android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
            segmenter.process(InputImage.fromBitmap(bitmap,0))
                .addOnSuccessListener(result->{
                    final Bitmap foreground=result.getForegroundBitmap();
                    if(foreground==null){
                        ui.post(()->failure.accept("Машина не выделена. Попробуй фото с хорошо видимым кузовом."));
                        return;
                    }
                    new Thread(()->{
                        try{
                            saveCutout(app,foreground,initial);
                            ui.post(success);
                        }catch(Exception error){
                            ui.post(()->failure.accept("Не удалось выделить машину: "+error.getMessage()));
                        }finally{if(!foreground.isRecycled())foreground.recycle();}
                    },"motion-wall-cutout-save").start();
                })
                .addOnFailureListener(error->
                    ui.post(()->failure.accept(
                        "Бесплатная модель пока недоступна: "+error.getMessage()+
                        ". При первом запуске Google Play services может скачать её.")))
                .addOnCompleteListener(task->{
                    try{segmenter.close();}catch(Exception ignored){}
                    if(!bitmap.isRecycled())bitmap.recycle();
                });
        },"motion-wall-mlkit-cutout").start();
    }

    private static void saveCutout(Context context,Bitmap cutout,String imageId) throws IOException {
        if(cutout.getWidth()<64||cutout.getHeight()<64)
            throw new IOException("Недопустимый размер маски");
        if(cutout.getConfig()!=Bitmap.Config.ARGB_8888&& !cutout.hasAlpha())
            throw new IOException("Нет прозрачного фона, нельзя отделить машину");
        // Detect common bogus model output (empty mask / whole photo returned).
        int occupied=0,samples=0;
        int dx=Math.max(1,cutout.getWidth()/64);
        int dy=Math.max(1,cutout.getHeight()/64);
        for(int y=0;y<cutout.getHeight();y+=dy)
         for(int x=0;x<cutout.getWidth();x+=dx){
            samples++;
            if((cutout.getPixel(x,y)>>>24)>96)occupied++;
         }
        if(occupied<samples*.025f||occupied>samples*.90f)
            throw new IOException("Не удалось отделить автомобиль от фона");
        SharedPreferences pref=context.getSharedPreferences(PhotoStore.PREFS,Context.MODE_PRIVATE);
        File tmp=new File(context.getFilesDir(),"cutout_"+System.nanoTime()+".png");
        try{
            try(FileOutputStream out=new FileOutputStream(tmp)){
                if(!cutout.compress(Bitmap.CompressFormat.PNG,100,out))
                    throw new IOException("Ошибка PNG");
                out.getFD().sync();
            }
            if(!imageId.equals(pref.getString(PhotoStore.IMAGE_KEY,null)))
                throw new IOException("Фото изменилось во время обработки");
            String previous=pref.getString(CUTOUT_KEY,null);
            if(!pref.edit().putString(CUTOUT_KEY,tmp.getName())
                .putString(SOURCE_KEY,imageId).commit())
                throw new IOException("Не получилось сохранить маску");
            if(previous!=null&&previous.matches("cutout_[0-9]+\\.png"))
                new File(context.getFilesDir(),previous).delete();
        }catch(Exception error){
            tmp.delete();
            if(error instanceof IOException)throw (IOException)error;
            throw new IOException(error.getMessage(),error);
        }
    }
}
