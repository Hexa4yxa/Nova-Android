package ai.motionwall.cars;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.File;

/** One photo, one selected effect, one Android wallpaper confirmation. */
public final class MainActivity extends Activity {
    private static final int PICK_PHOTO=51;
    private static final int PICK_VIDEO=52;
    private PreviewView preview;
    private VideoView videoPreview;
    private boolean videoPreviewOn;
    private TextView status;
    private Button chooseButton,applyButton,chooseVideoButton,applyVideoButton,videoPreviewButton;
    private Button nightButton,rainButton,neonButton,driveButton,segmentButton;
    private TextView strengthCaption;
    private MotionSettings settings=new MotionSettings(MotionSettings.NIGHT,65);
    private boolean busy;
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        settings=MotionSettings.read(this);
        getWindow().setStatusBarColor(Color.rgb(9,13,24));
        getWindow().setNavigationBarColor(Color.rgb(9,13,24));
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9,13,24));
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18),dp(18),dp(18),dp(26));
        scroll.addView(column);
        TextView eyebrow=label("MOTION WALL  /  CARS EDITION",11,Color.rgb(178,153,255),true);
        eyebrow.setLetterSpacing(.09f);column.addView(eyebrow);
        addWithMargin(column,label("Live Video Wallpaper",29,Color.WHITE,true),0,dp(6),0,dp(4));
        addWithMargin(column,label("Настоящее видео на рабочем столе · бесплатно и без подписки.",
            14,Color.rgb(181,189,207),false),0,0,0,dp(12));
        // True video preview is first. Old photo-only effects are now secondary.
        LinearLayout videoSection=new LinearLayout(this);
        videoSection.setOrientation(LinearLayout.VERTICAL);
        videoPreview=new VideoView(this);
        videoPreview.setBackgroundColor(Color.rgb(12,16,32));
        videoSection.addView(videoPreview,new LinearLayout.LayoutParams(-1,dp(330)));
        videoPreview.setOnPreparedListener(player->{
            player.setLooping(true);
            player.setVolume(0f,0f);
            if(videoPreviewOn)videoPreview.start();
        });
        videoPreview.setOnErrorListener((player,what,extra)->{
            if(status!=null)status.setText("⚠ Не удалось воспроизвести этот MP4. Попробуй H.264.");
            return true;
        });
        column.addView(videoSection);
        TextView freeLabel=label("● Без сервера, без платных API · MP4 1–30 секунд",12,
            Color.rgb(147,159,196),true);
        addWithMargin(videoSection,freeLabel,0,dp(10),0,dp(7));

        Button showPhotoTools=button("Дополнительно: фотоэффекты (не AI-видео) ▾",false);
        addWithMargin(column,showPhotoTools,0,dp(14),0,dp(8));
        LinearLayout photoSection=new LinearLayout(this);
        photoSection.setOrientation(LinearLayout.VERTICAL);
        photoSection.setVisibility(View.GONE);
        column.addView(photoSection);
        showPhotoTools.setOnClickListener(v->{
            boolean open=photoSection.getVisibility()!=View.VISIBLE;
            photoSection.setVisibility(open?View.VISIBLE:View.GONE);
            showPhotoTools.setText(open?
                "Скрыть фотоэффекты ▴":"Дополнительно: фотоэффекты (не AI-видео) ▾");
        });
        preview=new PreviewView();
        GradientDrawable border=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(19,27,52),Color.rgb(13,15,34)});
        border.setCornerRadius(dp(20));
        preview.setBackground(border);preview.setClipToOutline(true);
        photoSection.addView(preview,new LinearLayout.LayoutParams(-1,dp(330)));

        addWithMargin(photoSection,label("ФОТОЭФФЕКТ  ·  НЕ НАСТОЯЩЕЕ ДВИЖЕНИЕ",11,
            Color.rgb(147,159,196),true),0,dp(13),0,dp(6));
        LinearLayout modes=new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        nightButton=button("Night Ride",false);
        rainButton=button("Rain Drive",false);
        neonButton=button("Neon",false);
        driveButton=button("Drive 2.5D",false);
        addMode(modes,nightButton);
        addMode(modes,rainButton);
        addMode(modes,neonButton);
        addMode(modes,driveButton);
        photoSection.addView(modes);
        nightButton.setOnClickListener(v->setMode(MotionSettings.NIGHT));
        rainButton.setOnClickListener(v->setMode(MotionSettings.RAIN));
        neonButton.setOnClickListener(v->setMode(MotionSettings.NEON));
        driveButton.setOnClickListener(v->setMode(MotionSettings.DRIVE));
        driveButton.setEnabled(CarCutoutStore.ready(this));
        segmentButton=button("✂ Выделить машину бесплатно (ML Kit)",false);
        addWithMargin(photoSection,segmentButton,0,dp(8),0,0);
        segmentButton.setOnClickListener(v->segmentCar());
        addWithMargin(photoSection,label(
            "Drive 2.5D: машина движется отдельно от дороги. Это бесплатная композиция, не AI-видеогенерация. Колёса из фото не вращаются. ML Kit может скачать модель один раз.",
            12,Color.rgb(145,158,182),false),0,dp(6),0,dp(6));
        updateModeButtons();

        strengthCaption=label("",13,Color.rgb(181,189,207),false);
        addWithMargin(photoSection,strengthCaption,0,dp(10),0,0);
        showStrength();
        SeekBar strength=new SeekBar(this);
        strength.setMax(80);strength.setProgress(settings.strength-20);
        photoSection.addView(strength);
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser){
                if(!fromUser)return;
                settings=new MotionSettings(settings.mode,progress+20);
                showStrength();preview.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar){}
            @Override public void onStopTrackingTouch(SeekBar seekBar){
                if(!settings.save(MainActivity.this))status.setText("Не удалось сохранить силу эффекта.");
            }
        });

        chooseButton=button("1 · Выбрать фото машины",false);
        addWithMargin(photoSection,chooseButton,0,dp(8),0,0);
        chooseButton.setOnClickListener(v->pickPhoto());
        applyButton=button("2 · Установить живые обои",true);
        addWithMargin(photoSection,applyButton,0,dp(10),0,0);
        applyButton.setOnClickListener(v->applyWallpaper());

        // Free video playback is a genuine product feature, but not yet an
        // AI photo-to-video generator. Label this honestly.
        addWithMargin(videoSection,label("Выбери MP4, где машина действительно едет. Генерация из одного фото пока не реализована.",
            13,Color.rgb(181,189,207),false),0,dp(5),0,dp(8));
        Button freeSample=button("▶ Бесплатный пример: машина едет",false);
        addWithMargin(videoSection,freeSample,0,dp(4),0,dp(8));
        freeSample.setOnClickListener(v->{
            if(busy)return;
            if(VideoClipStore.get(this)!=null){
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Заменить выбранное видео?")
                    .setMessage("Исходный MP4 в Галерее останется, но в Motion Wall будет выбран бесплатный пример.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Заменить",(dialog,which)->installDemo())
                    .show();
            }else installDemo();
        });
        chooseVideoButton=button("1 · Выбрать видео автомобиля",false);
        addWithMargin(videoSection,chooseVideoButton,0,dp(4),0,0);
        chooseVideoButton.setOnClickListener(v->pickVideo());
        applyVideoButton=button("2 · Установить живые видеообои",true);
        addWithMargin(videoSection,applyVideoButton,0,dp(10),0,0);
        applyVideoButton.setOnClickListener(v->applyVideoWallpaper());
        applyVideoButton.setEnabled(VideoClipStore.get(this)!=null);
        videoPreviewButton=button("▶ Смотреть выбранное видео",false);
        addWithMargin(videoSection,videoPreviewButton,0,dp(10),0,0);
        videoPreviewButton.setEnabled(VideoClipStore.get(this)!=null);
        videoPreviewButton.setOnClickListener(v->playSelectedVideo());
        status=label("",13,Color.rgb(181,189,207),false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        addWithMargin(column,status,0,dp(10),0,0);
        TextView disclaimer=label("Все файлы остаются на телефоне. В первой бесплатной версии можно установить реальное MP4; AI-генерации движения из фото пока нет.",12,
            Color.rgb(123,139,165),false);
        disclaimer.setGravity(Gravity.CENTER_HORIZONTAL);
        addWithMargin(column,disclaimer,0,dp(9),0,0);
        setContentView(scroll);
        refreshPhoto();
        if(VideoClipStore.get(this)!=null)playSelectedVideo();
    }
    private void addMode(LinearLayout parent,Button button){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(54),1f);
        p.setMargins(dp(2),0,dp(2),0);
        button.setTextSize(12);button.setMinHeight(dp(54));
        parent.addView(button,p);
    }
    private void updateModeButtons(){
        tintMode(nightButton,MotionSettings.NIGHT.equals(settings.mode));
        tintMode(rainButton,MotionSettings.RAIN.equals(settings.mode));
        tintMode(neonButton,MotionSettings.NEON.equals(settings.mode));
        tintMode(driveButton,MotionSettings.DRIVE.equals(settings.mode));
    }
    private void tintMode(Button button,boolean selected){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(selected?Color.rgb(104,65,205):Color.rgb(35,43,64));
        bg.setCornerRadius(dp(13));button.setBackground(bg);
    }
    private void showStrength(){strengthCaption.setText("Сила эффекта: "+settings.strength+"%");}
    private void setMode(String mode){
        if(MotionSettings.DRIVE.equals(mode)&&!CarCutoutStore.ready(this)){
            status.setText("Сначала выбери фото и нажми «Выделить машину».");
            return;
        }
        MotionSettings changed=new MotionSettings(mode,settings.strength);
        if(!changed.save(this)){status.setText("Не удалось сохранить режим.");return;}
        settings=changed;updateModeButtons();preview.invalidate();
        status.setText("✅ Режим "+mode+" сохранён для живых обоев.");
    }
    private TextView label(String text,int sp,int color,boolean bold){
        TextView view=new TextView(this);
        view.setText(text);view.setTextSize(sp);view.setTextColor(color);
        if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    private void addWithMargin(LinearLayout parent,View view,int left,int top,int right,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(left,top,right,bottom);parent.addView(view,p);
    }
    private Button button(String title,boolean accent){
        Button result=new Button(this);
        result.setText(title);result.setTextSize(15);result.setAllCaps(false);
        result.setTextColor(Color.WHITE);
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(accent?Color.rgb(104,65,205):Color.rgb(39,48,71));
        bg.setCornerRadius(dp(16));result.setBackground(bg);
        result.setMinHeight(dp(60));return result;
    }
    private void segmentCar(){
        if(busy)return;
        if(!preview.hasPhoto()) {
            status.setText("Сначала выбери фото автомобиля.");
            return;
        }
        busy=true;
        segmentButton.setEnabled(false);
        chooseButton.setEnabled(false);
        status.setText("Бесплатная модель выделяет автомобиль прямо на телефоне...");
        CarCutoutStore.generate(getApplicationContext(),()->{
            busy=false;
            chooseButton.setEnabled(true);
            segmentButton.setEnabled(true);
            driveButton.setEnabled(CarCutoutStore.ready(this));
            preview.reload();
            status.setText("✅ Машина отделена от фона. Выбери Drive 2.5D, затем установи живые обои.");
        },error->{
            busy=false;
            chooseButton.setEnabled(true);
            segmentButton.setEnabled(true);
            driveButton.setEnabled(CarCutoutStore.ready(this));
            status.setText("Ошибка выделения: "+error);
        });
    }
    private void installDemo(){
        if(busy)return;
        busy=true;
        chooseVideoButton.setEnabled(false);applyVideoButton.setEnabled(false);
        videoPreviewButton.setEnabled(false);
        status.setText("Подготавливаю бесплатный пример...");
        new Thread(()->{
            String error=null;
            try{VideoClipStore.installFreeDemo(getApplicationContext());}
            catch(Exception exception){error=exception.getMessage();}
            final String failure=error;
            runOnUiThread(()->{
                busy=false;
                chooseVideoButton.setEnabled(true);
                boolean ready=VideoClipStore.get(this)!=null;
                applyVideoButton.setEnabled(ready);
                videoPreviewButton.setEnabled(ready);
                if(failure==null){
                    playSelectedVideo();
                    status.setText("✅ Пример готов: дорога движется, колёса вращаются. Это 2D-анимация, не AI.");
                }else status.setText("Не удалось загрузить пример: "+failure);
            });
        },"motion-wall-demo").start();
    }
    private void playSelectedVideo(){
        File file=VideoClipStore.get(this);
        if(file==null){if(status!=null)status.setText("Сначала выбери MP4.");return;}
        videoPreviewOn=true;
        videoPreviewButton.setEnabled(true);
        videoPreview.setVideoPath(file.getAbsolutePath());
        videoPreview.requestFocus();
        videoPreview.start();
        if(status!=null)status.setText("▶ Настоящее видео: проверь движение автомобиля перед установкой.");
    }
    private void pickVideo(){
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        try{startActivityForResult(intent,PICK_VIDEO);}
        catch(Exception e){status.setText("Не удалось открыть видео: "+e.getMessage());}
    }
    private void applyVideoWallpaper(){
        if(VideoClipStore.get(this)==null){status.setText("Сначала выбери настоящее MP4.");return;}
        Intent intent=new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            new ComponentName(this,MotionVideoWallpaperService.class));
        try{startActivity(intent);}
        catch(Exception e){status.setText("Android не открыл видеообои: "+e.getMessage());}
    }
    private void pickPhoto(){
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try{startActivityForResult(intent,PICK_PHOTO);}
        catch(Exception e){status.setText("Не удалось открыть фото: "+e.getMessage());}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==PICK_VIDEO&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            busy=true;
            chooseButton.setEnabled(false);
            chooseVideoButton.setEnabled(false);
            applyButton.setEnabled(false);
            applyVideoButton.setEnabled(false);
            status.setText("Проверяю и сохраняю настоящее MP4...");
            final android.net.Uri video=data.getData();
            new Thread(()->{
                String error=null;
                try{VideoClipStore.importVideo(getApplicationContext(),video);}
                catch(Exception e){error=e.getMessage();}
                final String finalError=error;
                runOnUiThread(()->{
                    busy=false;
                    chooseButton.setEnabled(true);
                    chooseVideoButton.setEnabled(true);
                    boolean loaded=VideoClipStore.get(this)!=null;
                    applyVideoButton.setEnabled(loaded);
                    videoPreviewButton.setEnabled(loaded);
                    applyButton.setEnabled(preview.hasPhoto());
                    if(finalError==null)playSelectedVideo();
                    status.setText(finalError==null?
                        "✅ MP4 сохранён локально. Можно установить настоящие видеообои.":
                        "Ошибка MP4: "+finalError);
                });
            },"motion-wall-video-import").start();
            return;
        }
        if(requestCode!=PICK_PHOTO||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        busy=true;chooseButton.setEnabled(false);applyButton.setEnabled(false);
        chooseVideoButton.setEnabled(false);applyVideoButton.setEnabled(false);
        status.setText("Подготавливаю фотографию...");
        final android.net.Uri photo=data.getData();
        new Thread(()->{
            String error=null;
            try{PhotoStore.importPhoto(getApplicationContext(),photo);}
            catch(Exception e){error=e.getMessage();}
            final String failed=error;
            runOnUiThread(()->{
                busy=false;chooseButton.setEnabled(true);
                chooseVideoButton.setEnabled(true);
                applyVideoButton.setEnabled(VideoClipStore.get(this)!=null);
                if(failed==null){
                    refreshPhoto();
                    driveButton.setEnabled(CarCutoutStore.ready(this));
                    Toast.makeText(this,"Фотография готова",Toast.LENGTH_SHORT).show();
                }else{
                    status.setText("Ошибка: "+failed);applyButton.setEnabled(preview.hasPhoto());
                }
            });
        },"motion-wall-photo").start();
    }
    private void refreshPhoto(){
        if(preview==null)return;
        boolean loaded=preview.reload();
        applyButton.setEnabled(loaded&&!busy);
        status.setText(VideoClipStore.get(this)!=null?
            "✅ Видео сохранено на телефоне. Нажми «Смотреть», затем «Установить».":
            "Попробуй бесплатный пример или выбери своё MP4. Фотоэффекты — отдельно.");
    }
    private void applyWallpaper(){
        if(busy||!preview.hasPhoto()){status.setText("Сначала выбери фотографию.");return;}
        Intent intent=new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            new ComponentName(this,MotionWallpaperService.class));
        try{startActivity(intent);}
        catch(Exception e){
            try{startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));}
            catch(Exception ignored){status.setText("Не удалось открыть живые обои: "+e.getMessage());}
        }
    }
    @Override protected void onResume(){
        super.onResume();
        if(preview!=null)preview.invalidate();
        if(videoPreviewOn&&videoPreview!=null&&VideoClipStore.get(this)!=null)
            playSelectedVideo();
    }
    @Override protected void onPause(){
        videoPreviewOn=false;
        if(videoPreview!=null)videoPreview.stopPlayback();
        super.onPause();
    }
    private final class PreviewView extends View{
        private final NightRideRenderer renderer=new NightRideRenderer();
        private final DriveSceneRenderer driveRenderer=new DriveSceneRenderer();
        private final long start=SystemClock.uptimeMillis();
        private Bitmap image,carCutout;
        private boolean attached;
        private final Runnable tick=()->{
            if(attached&&isShown()&&getWindowVisibility()==View.VISIBLE){
                invalidate();postDelayed(this.tick,MotionMath.frameDelay(false));
            }
        };
        PreviewView(){super(MainActivity.this);}
        boolean hasPhoto(){return image!=null&&!image.isRecycled();}
        boolean reload(){
            Bitmap fresh=PhotoStore.load(MainActivity.this);
            Bitmap old=image;image=fresh;
            if(old!=null&&old!=fresh&&!old.isRecycled())old.recycle();
            Bitmap cutout=CarCutoutStore.load(MainActivity.this);
            Bitmap prior=carCutout;carCutout=cutout;
            driveRenderer.release();
            if(prior!=null&&prior!=cutout&&!prior.isRecycled())prior.recycle();
            invalidate();return hasPhoto();
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float phase=LoopMath.phase(SystemClock.uptimeMillis()-start);
            if(MotionSettings.DRIVE.equals(settings.mode)&&carCutout!=null)
                driveRenderer.draw(c,getWidth(),getHeight(),carCutout,phase,settings.strength);
            else renderer.draw(c,getWidth(),getHeight(),image,phase,settings);
        }
        @Override protected void onAttachedToWindow(){
            super.onAttachedToWindow();attached=true;
            if(!hasPhoto())reload();
            removeCallbacks(tick);post(tick);
        }
        @Override protected void onDetachedFromWindow(){
            attached=false;removeCallbacks(tick);
            renderer.release();
            driveRenderer.release();
            if(image!=null&&!image.isRecycled())image.recycle();image=null;
            if(carCutout!=null&&!carCutout.isRecycled())carCutout.recycle();carCutout=null;
            super.onDetachedFromWindow();
        }
        @Override protected void onWindowVisibilityChanged(int visibility){
            super.onWindowVisibilityChanged(visibility);
            removeCallbacks(tick);
            if(attached&&visibility==View.VISIBLE&&isShown())post(tick);
        }
    }
}
