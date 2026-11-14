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
import android.widget.TextView;
import android.widget.Toast;

/** Prototype user flow: select photo -> moving preview -> system live-wallpaper dialog. */
public final class MainActivity extends Activity {
    private static final int PICK_PHOTO=51;
    private PreviewView preview;
    private TextView status;
    private Button chooseButton,applyButton;
    private boolean busy;
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9,13,24));
        getWindow().setNavigationBarColor(Color.rgb(9,13,24));
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9,13,24));
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20),dp(24),dp(20),dp(28));
        scroll.addView(column);
        TextView eyebrow=label("MOTION WALL  /  CARS EDITION",12,Color.rgb(178,153,255),true);
        eyebrow.setLetterSpacing(.10f);column.addView(eyebrow);
        addWithMargin(column,label("Night Ride",34,Color.WHITE,true),0,dp(6),0,dp(5));
        addWithMargin(column,label("Твоя фотография — живые обои. Всё работает на телефоне.",
            15,Color.rgb(181,189,207),false),0,0,0,dp(16));
        preview=new PreviewView();
        GradientDrawable border=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(19,27,52),Color.rgb(13,15,34)});
        border.setCornerRadius(dp(22));
        preview.setBackground(border);preview.setClipToOutline(true);
        column.addView(preview,new LinearLayout.LayoutParams(-1,dp(330)));
        addWithMargin(column,label("NIGHT RIDE  ·  LOOP 8 СЕК  ·  БЕЗ ЗВУКА",11,
            Color.rgb(147,159,196),true),0,dp(12),0,dp(18));
        chooseButton=button("1  ·  Выбрать фото машины",false);
        column.addView(chooseButton);chooseButton.setOnClickListener(v->pickPhoto());
        applyButton=button("2  ·  Установить живые обои",true);
        addWithMargin(column,applyButton,0,dp(12),0,dp(12));
        applyButton.setOnClickListener(v->applyWallpaper());
        status=label("",13,Color.rgb(181,189,207),false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        addWithMargin(column,status,0,dp(8),0,dp(14));
        TextView disclaimer=label("Первый тест: движение камеры, свет и дождь. Колёса физически не вращаются, AI-генерации пока нет.",12,
            Color.rgb(123,139,165),false);
        disclaimer.setGravity(Gravity.CENTER_HORIZONTAL);column.addView(disclaimer);
        setContentView(scroll);
        refreshPhoto();
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
        status.setText(loaded?"✅ Фото готово · эффекты Night Ride активны":
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
    private final class PreviewView extends View{
        private final NightRideRenderer renderer=new NightRideRenderer();
        private final long start=SystemClock.uptimeMillis();
        private Bitmap image;
        private boolean attached;
        private final Runnable tick=()->{
            if(attached&&getWindowVisibility()==View.VISIBLE){
                invalidate();postDelayed(this.tick,67);
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
                LoopMath.phase(SystemClock.uptimeMillis()-start));
        }
        @Override protected void onAttachedToWindow(){
            super.onAttachedToWindow();attached=true;removeCallbacks(tick);post(tick);
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
