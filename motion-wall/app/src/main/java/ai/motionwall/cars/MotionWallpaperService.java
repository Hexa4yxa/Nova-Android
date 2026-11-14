package ai.motionwall.cars;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/** Visible-only Android live wallpaper. Never runs background animation on a hidden surface. */
public final class MotionWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine(){return new RideEngine();}
    private final class RideEngine extends Engine {
        private final Handler handler=new Handler(Looper.getMainLooper());
        private final NightRideRenderer renderer=new NightRideRenderer();
        private final SharedPreferences prefs=getSharedPreferences(PhotoStore.PREFS,MODE_PRIVATE);
        private final SharedPreferences.OnSharedPreferenceChangeListener listener=(p,key)->{
            if(PhotoStore.IMAGE_KEY.equals(key))handler.post(this::reload);
            if(MotionSettings.MODE_KEY.equals(key)||MotionSettings.STRENGTH_KEY.equals(key)) {
                handler.post(()->{
                    settings=MotionSettings.read(MotionWallpaperService.this);
                    request();
                });
            }
        };
        private final Runnable nextFrame=this::render;
        private Bitmap image;
        private MotionSettings settings=new MotionSettings(MotionSettings.NIGHT,65);
        private PowerManager power;
        private boolean visible,surfaceReady,destroyed;
        private long firstFrameTime=SystemClock.uptimeMillis();

        @Override public void onCreate(SurfaceHolder holder){
            super.onCreate(holder);
            prefs.registerOnSharedPreferenceChangeListener(listener);
            settings=MotionSettings.read(MotionWallpaperService.this);
            power=(PowerManager)getSystemService(POWER_SERVICE);
            reload();
        }
        private void reload(){
            if(destroyed)return;
            Bitmap old=image;image=PhotoStore.load(MotionWallpaperService.this);
            if(old!=null&&old!=image&&!old.isRecycled())old.recycle();
            request();
        }
        @Override public void onSurfaceCreated(SurfaceHolder holder){
            super.onSurfaceCreated(holder);surfaceReady=true;request();
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder,int format,int width,int height){
            super.onSurfaceChanged(holder,format,width,height);surfaceReady=true;request();
        }
        @Override public void onVisibilityChanged(boolean isVisible){
            visible=isVisible;handler.removeCallbacks(nextFrame);if(visible)request();
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder){
            surfaceReady=false;handler.removeCallbacks(nextFrame);super.onSurfaceDestroyed(holder);
        }
        private void request(){
            handler.removeCallbacks(nextFrame);
            if(!destroyed&&visible&&surfaceReady)handler.post(nextFrame);
        }
        private void render(){
            if(destroyed||!visible||!surfaceReady)return;
            SurfaceHolder holder=getSurfaceHolder();
            Canvas canvas=null;
            try{
                canvas=holder.lockCanvas();
                if(canvas!=null)renderer.draw(canvas,canvas.getWidth(),canvas.getHeight(),
                    image,LoopMath.phase(SystemClock.uptimeMillis()-firstFrameTime),settings);
            }catch(Exception ignored){ /* surface may be closing during a frame */ }
            finally{if(canvas!=null)try{holder.unlockCanvasAndPost(canvas);}catch(Exception ignored){}}
            boolean saving=power!=null&&power.isPowerSaveMode();
            if(!destroyed&&visible&&surfaceReady)
                handler.postDelayed(nextFrame,MotionMath.frameDelay(saving));
        }
        @Override public void onDestroy(){
            destroyed=true;visible=false;surfaceReady=false;
            handler.removeCallbacksAndMessages(null);
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
            if(image!=null&&!image.isRecycled())image.recycle();
            image=null;super.onDestroy();
        }
    }
}
