package ai.motionwall.cars;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Zero-cost 2.5D offline scene. A genuinely separated transparent car sprite
 * moves relative to a procedural driving environment; this is not generated
 * photorealistic video and wheels remain part of the original photograph.
 * For actual rolling wheels choose a true MP4 or run a powerful local model.
 */
public final class DriveSceneRenderer {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Rect src=new Rect();
    private final RectF dst=new RectF();
    private Bitmap lastCutout;
    private boolean boundsReady;
    private int ww=-1,hh=-1;
    private Shader sky,road;

    public void release(){
        lastCutout=null;boundsReady=false;ww=hh=-1;sky=road=null;
    }
    private void computeBounds(Bitmap image){
        lastCutout=image;boundsReady=false;
        if(image==null||image.isRecycled())return;
        final int w=image.getWidth(),h=image.getHeight();
        int xStep=Math.max(1,w/160),yStep=Math.max(1,h/160);
        int xMin=w,xMax=0,yMin=h,yMax=0;boolean found=false;
        for(int y=0;y<h;y+=yStep){
            for(int x=0;x<w;x+=xStep){
                if((image.getPixel(x,y)>>>24)>100){
                    xMin=Math.min(xMin,x);xMax=Math.max(xMax,x);
                    yMin=Math.min(yMin,y);yMax=Math.max(yMax,y);
                    found=true;
                }
            }
        }
        if(!found)return;
        src.set(Math.max(0,xMin-3),Math.max(0,yMin-3),
            Math.min(w,xMax+4),Math.min(h,yMax+4));
        boundsReady=src.width()>20&&src.height()>20;
    }
    private void shades(int w,int h){
        if(w==ww&&h==hh)return;
        ww=w;hh=h;
        sky=new LinearGradient(0,0,0,h*.82f,
            new int[]{Color.rgb(5,9,23),Color.rgb(18,27,58),Color.rgb(16,27,46)},
            null,Shader.TileMode.CLAMP);
        road=new LinearGradient(0,h*.67f,0,h,
            new int[]{Color.rgb(28,43,55),Color.rgb(8,17,29),Color.rgb(4,9,18)},
            null,Shader.TileMode.CLAMP);
    }
    public void draw(Canvas c,int w,int h,Bitmap separatedCar,float phase,int strength){
        if(w<1||h<1)return;
        if(separatedCar!=lastCutout)computeBounds(separatedCar);
        shades(w,h);
        float amount=MotionMath.clamp(strength,20,100)/100f;
        c.save();
        c.clipRect(0,0,w,h);
        p.setShader(sky);
        c.drawRect(0,0,w,h,p);
        p.setShader(null);
        // The skyline moves a full tile across one 8s loop while the car
        // shifts only a few pixels. Distinct subject/background motion.
        float distant=w*phase;
        for(int repetition=-1;repetition<3;repetition++){
            for(int i=0;i<14;i++){
                float x=i*w/12f-distant+repetition*w;
                float top=h*(.42f+(i*19%13)*.009f);
                float bw=w*.09f;
                p.setColor(Color.rgb(13+i%3*2,22+i%4*2,45+i%5*3));
                c.drawRect(x,top,x+bw,h*.69f,p);
                p.setColor(Color.argb(160,140,175,220));
                for(int row=0;row<5;row++)
                 for(int col=0;col<2;col++){
                    if(((i*7+row*3+col*5)%4)!=0)continue;
                    float winX=x+w*.015f+col*w*.026f;
                    float winY=top+h*.018f+row*h*.032f;
                    c.drawRect(winX,winY,winX+w*.012f,winY+h*.009f,p);
                }
            }
        }
        p.setShader(road);
        c.drawRect(0,h*.68f,w,h,p);
        p.setShader(null);
        p.setStrokeWidth(Math.max(1f,w*.004f));
        // Road reflections / markings use periodic coordinates: no jump
        // across the eight-second loop.
        for(int i=0;i<14;i++){
            float x=((i/14f+phase*2f)%1f)*w;
            float y=h*.85f;
            p.setColor(Color.argb(195,196,173,112));
            c.drawLine(x,y,x+w*.045f,y,p);
        }
        p.setColor(Color.argb(110,80,133,184));
        c.drawLine(0,h*.74f,w,h*.74f,p);

        if(boundsReady){
            // Keep original car proportions; always fit inside portrait.
            float fit=Math.min(w*.93f/src.width(),h*.30f/src.height());
            float dw=src.width()*fit,dh=src.height()*fit;
            float x=(w-dw)*.5f+w*.025f*amount*MotionMath.wave(phase);
            float y=h*.76f-dh*.78f+h*.005f*amount*MotionMath.wave(phase*2f);
            // Fixed soft shadow under the moving car.
            p.setShader(null);
            p.setColor(Color.argb(85,1,4,12));
            c.drawOval(w*.10f,h*.757f,w*.90f,h*.79f,p);
            dst.set(x,y,x+dw,y+dh);
            p.setColor(Color.WHITE);
            p.setAlpha(255);
            c.drawBitmap(separatedCar,src,dst,p);
        }else{
            p.setColor(Color.rgb(180,194,220));
            p.setTextSize(Math.max(14f,w*.04f));
            c.drawText("Выдели автомобиль в приложении",w*.09f,h*.55f,p);
        }
        c.restore();
    }
}
