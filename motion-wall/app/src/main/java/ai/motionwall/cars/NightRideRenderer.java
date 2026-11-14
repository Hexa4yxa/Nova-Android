package ai.motionwall.cars;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Night Ride visual illusion: NOT true AI car motion, object masking, or MP4. */
public final class NightRideRenderer {
    private final Paint bitmapPaint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
    private final Paint color=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape=new Path();
    private int shaderWidth=-1,shaderHeight=-1;
    private Shader overlayShader;

    public void draw(Canvas canvas,int w,int h,Bitmap image,float phase){
        if(w<=0||h<=0)return;
        canvas.save();
        canvas.clipRect(0,0,w,h);
        canvas.drawColor(Color.rgb(7,10,22));
        if(image!=null&&!image.isRecycled())drawPhoto(canvas,w,h,image,phase);
        else drawDemo(canvas,w,h,phase);
        drawAtmosphere(canvas,w,h,phase);
        canvas.restore();
    }
    private void drawPhoto(Canvas c,int w,int h,Bitmap b,float phase){
        float zoom=1.13f+0.02f*LoopMath.wave(phase);
        float scale=Math.max(w/(float)b.getWidth(),h/(float)b.getHeight())*zoom;
        float dw=b.getWidth()*scale,dh=b.getHeight()*scale;
        float dx=(w-dw)*.5f+LoopMath.wave(phase)*w*.024f;
        float dy=(h-dh)*.5f+LoopMath.wave(phase+.25f)*h*.016f;
        c.drawBitmap(b,null,new RectF(dx,dy,dx+dw,dy+dh),bitmapPaint);
    }
    private void drawDemo(Canvas c,int w,int h,float phase){
        color.setShader(new LinearGradient(0,0,w,h,
            new int[]{Color.rgb(18,25,51),Color.rgb(45,16,72),Color.rgb(11,19,33)},
            null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,color);
        color.setShader(null);
        float bob=6f*LoopMath.wave(phase),base=h*.63f+bob;
        color.setColor(Color.rgb(20,21,32));
        c.drawRoundRect(w*.10f,base,w*.90f,base+h*.11f,20f,20f,color);
        shape.reset();
        shape.moveTo(w*.24f,base);shape.lineTo(w*.37f,base-h*.11f);
        shape.lineTo(w*.66f,base-h*.11f);shape.lineTo(w*.79f,base);shape.close();
        color.setColor(Color.rgb(63,70,97));c.drawPath(shape,color);
        color.setColor(Color.rgb(12,15,23));
        c.drawCircle(w*.27f,base+h*.11f,w*.07f,color);
        c.drawCircle(w*.73f,base+h*.11f,w*.07f,color);
        color.setColor(Color.rgb(244,87,188));
        c.drawCircle(w*.15f,base+h*.045f,w*.016f,color);
        c.drawCircle(w*.85f,base+h*.045f,w*.016f,color);
    }
    private void drawAtmosphere(Canvas c,int w,int h,float phase){
        if(overlayShader==null||shaderWidth!=w||shaderHeight!=h){
            shaderWidth=w;shaderHeight=h;
            overlayShader=new LinearGradient(0,0,0,h,
                new int[]{Color.argb(75,7,12,36),Color.argb(14,11,15,40),Color.argb(125,2,5,18)},
                new float[]{0f,.55f,1f},Shader.TileMode.CLAMP);
        }
        color.setShader(overlayShader);color.setStyle(Paint.Style.FILL);
        c.drawRect(0,0,w,h,color);color.setShader(null);
        float sx=(.5f+.35f*LoopMath.wave(phase))*w;
        color.setColor(Color.argb(25,120,68,255));
        c.drawCircle(sx,h*.72f,w*.31f,color);
        color.setStrokeWidth(Math.max(1.2f,w/560f));
        for(int i=0;i<75;i++){
            float seed=LoopMath.wrap01(i*.618034f);
            float depth=.45f+LoopMath.wrap01(i*.3471f)*.55f;
            float x0=LoopMath.wrap01(i*.28923f);
            float y=LoopMath.wrap01(seed+phase*(1+(i%3)))*h;
            float x=LoopMath.wrap01(x0+phase*((i%2)+1)*.06f)*w;
            color.setColor(Color.argb((int)(48+75*depth),174,203,255));
            c.drawLine(x,y,x-9*depth,y+20*depth,color);
        }
        float center=w*.5f;
        for(int i=0;i<10;i++){
            float y=h*(.68f+i*.028f);
            float size=w*(.045f+.02f*i);
            float glint=.35f+.65f*LoopMath.smooth(phase+i*.1f);
            color.setColor(Color.argb((int)(15+32*glint),126,74,255));
            color.setStrokeWidth(1.3f+(i%3));
            c.drawLine(center-size*3,y,center-size,y,color);
            c.drawLine(center+size,y,center+size*3,y,color);
        }
        color.setStyle(Paint.Style.FILL);
    }
}
