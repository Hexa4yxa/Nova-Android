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

/** One photo, one selected effect, one Android wallpaper confirmation. */
public final class MainActivity extends Activity {
    private static final int PICK_PHOTO=51;
    private PreviewView preview;
    private TextView status;
    private Button chooseButton,applyButton;
    private Button nightButton,rainButton,neonButton;
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
        addWithMargin(column,label("Live Car Wallpaper",29,Color.WHITE,true),0,dp(6),0,dp(4));
        addWithMargin(column,label("Своё фото машины → живые обои. Без интернета.",
            14,Color.rgb(181,189,207),false),0,0,0,dp(12));
        preview=new PreviewView();
        GradientDrawable border=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(19,27,52),Color.rgb(13,15,34)});
        border.setCornerRadius(dp(20));
        preview.setBackground(border);preview.setClipToOutline(true);
        column.addView(preview,new LinearLayout.LayoutParams(-1,dp(330)));

        addWithMargin(column,label("ЭФФЕКТ  ·  БЕСШОВНЫЙ ЦИКЛ 8 СЕК",11,
            Color.rgb(147,159,196),true),0,dp(13),0,dp(6));
        LinearLayout modes=new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        nightButton=button("Night Ride",false);
        rainButton=button("Rain Drive",false);
        neonButton=button("Neon",false);
        addMode(modes,nightButton);
        addMode(modes,rainButton);
        addMode(modes,neonButton);
        column.addView(modes);
        nightButton.setOnClickListener(v->setMode(MotionSettings.NIGHT));
        rainButton.setOnClickListener(v->setMode(MotionSettings.RAIN));
        neonButton.setOnClickListener(v->setMode(MotionSettings.NEON));
        updateModeButtons();

        strengthCaption=label("",13,Color.rgb(181,189,207),false);
        addWithMargin(column,strengthCaption,0,dp(10),0,0);
        showStrength();
        SeekBar strength=new SeekBar(this);
        strength.setMax(80);strength.setProgress(settings.strength-20);
        column.addView(strength);
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
        addWithMargin(column,chooseButton,0,dp(8),0,0);
        chooseButton.setOnClickListener(v->pickPhoto());
        applyButton=button("2 · Установить живые обои",true);
        addWithMargin(column,applyButton,0,dp(10),0,0);
        applyButton.setOnClickListener(v->applyWallpaper());
        status=label("",13,Color.rgb(181,189,207),false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        addWithMargin(column,status,0,dp(10),0,0);
        TextView disclaimer=label("Фото сохраняется только на телефоне. Машина не вращает колёса и не ездит физически: движение создают камера, свет и атмосфера.",12,
            Color.rgb(123,139,165),false);
        disclaimer.setGravity(Gravity.CENTER_HORIZONTAL);
        addWithMargin(column,disclaimer,0,dp(9),0,0);
        setContentView(scroll);
        refreshPhoto();
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
    }
    private void tintMode(Button button,boolean selected){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(selected?Color.rgb(104,65,205):Color.rgb(35,43,64));
        bg.setCornerRadius(dp(13));button.setBackground(bg);
    }
    private void showStrength(){strengthCaption.setText("Сила эффекта: "+settings.strength+"%");}
    private void setMode(String mode){
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
        if(requestCode!=PICK_PHOTO||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        busy=true;chooseButton.setEnabled(false);applyButton.setEnabled(false);
        status.setText("Подготавливаю фотографию...");
        final android.net.Uri photo=data.getData();
        new Thread(()->{
            String error=null;
            try{PhotoStore.importPhoto(getApplicationContext(),photo);}
            catch(Exception e){error=e.getMessage();}
            final String failed=error;
            runOnUiThread(()->{
                busy=false;chooseButton.setEnabled(true);
                if(failed==null){
                    refreshPhoto();Toast.makeText(this,"Фотография готова",Toast.LENGTH_SHORT).show();
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
        status.setText(loaded?"✅ Фото готово · режим и сила эффекта сохраняются":
            "Выбери фотографию. Пока показана демонстрационная сцена.");
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
    }
    private final class PreviewView extends View{
        private final NightRideRenderer renderer=new NightRideRenderer();
        private final long start=SystemClock.uptimeMillis();
        private Bitmap image;
        private boolean attached;
        private final Runnable tick=()->{
            if(attached&&getWindowVisibility()==View.VISIBLE){
                invalidate();postDelayed(this.tick,MotionMath.frameDelay(false));
            }
        };
        PreviewView(){super(MainActivity.this);}
        boolean hasPhoto(){return image!=null&&!image.isRecycled();}
        boolean reload(){
            Bitmap fresh=PhotoStore.load(MainActivity.this);
            Bitmap old=image;image=fresh;
            if(old!=null&&old!=fresh&&!old.isRecycled())old.recycle();
            invalidate();return hasPhoto();
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            renderer.draw(c,getWidth(),getHeight(),image,
                LoopMath.phase(SystemClock.uptimeMillis()-start),settings);
        }
        @Override protected void onAttachedToWindow(){
            super.onAttachedToWindow();attached=true;
            if(!hasPhoto())reload();
            removeCallbacks(tick);post(tick);
        }
        @Override protected void onDetachedFromWindow(){
            attached=false;removeCallbacks(tick);
            if(image!=null&&!image.isRecycled())image.recycle();image=null;
            super.onDetachedFromWindow();
        }
        @Override protected void onWindowVisibilityChanged(int visibility){
            super.onWindowVisibilityChanged(visibility);
            removeCallbacks(tick);
            if(attached&&visibility==View.VISIBLE)post(tick);
        }
    }
}
