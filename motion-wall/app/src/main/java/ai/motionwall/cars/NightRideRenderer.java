package ai.motionwall.cars;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Three local animated presets; no AI or simulated wheel motion.
 * Center photo always uses FIT, not COVER. An atmospheric background fills
 * the remaining screen, so wide cars aren't cropped on a tall phone.
 */
public final class NightRideRenderer {
    private static final int PARTICLES = 75;
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int gradientW = -1, gradientH = -1;
    private Shader gradient;
    // Background is static on purpose. Re-scaling a full 1800px car photo
    // TWICE on every Canvas frame was causing launcher/wallpaper stutter.
    // Cache only the low-contrast background at reduced 640px width.
    private Bitmap backgroundCache;
    private Bitmap backgroundSource;
    private int backgroundW = -1, backgroundH = -1;
    private boolean backgroundAllocationFailed;

    public void release() {
        if (backgroundCache != null && !backgroundCache.isRecycled())
            backgroundCache.recycle();
        backgroundCache = null;
        backgroundSource = null;
        backgroundW = backgroundH = -1;
        backgroundAllocationFailed = false;
    }

    private void drawCachedBackground(Canvas canvas, int w, int h, Bitmap image) {
        if (image != backgroundSource || w != backgroundW || h != backgroundH) {
            release();
            backgroundSource = image;
            backgroundW = w;
            backgroundH = h;
            float scale = Math.min(1f, 640f / Math.max(1, w));
            int cw = Math.max(1, Math.round(w * scale));
            int ch = Math.max(1, Math.round(h * scale));
            try {
                backgroundCache = Bitmap.createBitmap(cw, ch, Bitmap.Config.RGB_565);
                Canvas staging = new Canvas(backgroundCache);
                staging.drawColor(Color.rgb(8, 12, 26));
                float cover = MotionMath.coverScale(image.getWidth(), image.getHeight(), cw, ch)*1.12f;
                float bw = image.getWidth()*cover, bh = image.getHeight()*cover;
                rect.set((cw-bw)*.5f,(ch-bh)*.5f,(cw+bw)*.5f,(ch+bh)*.5f);
                photoPaint.setAlpha(145);
                staging.drawBitmap(image,null,rect,photoPaint);
                photoPaint.setAlpha(255);
                brush.setShader(null);
                brush.setColor(Color.argb(170,4,8,23));
                staging.drawRect(0f,0f,cw,ch,brush);
            } catch (OutOfMemoryError error) {
                if (backgroundCache != null && !backgroundCache.isRecycled())
                    backgroundCache.recycle();
                backgroundCache = null;
                backgroundAllocationFailed = true;
            }
        }
        if (backgroundCache != null && !backgroundCache.isRecycled()) {
            photoPaint.setAlpha(255);
            rect.set(0f,0f,w,h);
            canvas.drawBitmap(backgroundCache,null,rect,photoPaint);
        } else if (backgroundAllocationFailed) {
            canvas.drawColor(Color.rgb(8,12,26));
        }
    }

    public void draw(Canvas canvas, int w, int h, Bitmap image, float phase) {
        draw(canvas, w, h, image, phase, new MotionSettings(MotionSettings.NIGHT, 65));
    }
    public void draw(Canvas canvas, int w, int h, Bitmap image, float phase, MotionSettings settings) {
        if (w <= 0 || h <= 0) return;
        MotionSettings config = settings == null ?
            new MotionSettings(MotionSettings.NIGHT, 65) : settings;
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        canvas.drawColor(Color.rgb(7, 10, 22));
        if (image != null && !image.isRecycled()) {
            drawPhoto(canvas, w, h, image, phase, config);
        } else {
            drawDemo(canvas, w, h, phase);
        }
        drawAtmosphere(canvas, w, h, phase, config);
        canvas.restore();
    }
    private void drawPhoto(Canvas canvas, int w, int h, Bitmap image,
                           float phase, MotionSettings settings) {
        float iw = image.getWidth(), ih = image.getHeight();
        float strength = settings.strength / 100f;

        // Preserve dark full-screen scenery, but only resample it on a new
        // image or surface size. Foreground remains live, bright and uncropped.
        drawCachedBackground(canvas,w,h,image);

        // FIT = important on portrait wallpaper from landscape car photos.
        // A small oscillation creates motion without cutting off the bumper.
        float fit = MotionMath.fitScale((int)iw,(int)ih,w,h);
        float zoom = 1f + .018f*MotionMath.softPulse(phase)*strength;
        float fw = iw * fit * zoom, fh = ih * fit * zoom;
        float fx = (w-fw)/2f + MotionMath.wave(phase)*w*.012f*strength;
        float fy = (h-fh)/2f + MotionMath.wave(phase+.25f)*h*.017f*strength;
        rect.set(fx,fy,fx+fw,fy+fh);
        canvas.drawBitmap(image,null,rect,photoPaint);
    }
    private void drawDemo(Canvas canvas, int w, int h, float phase) {
        brush.setShader(new LinearGradient(0,0,w,h,
            new int[]{Color.rgb(17,24,50),Color.rgb(43,16,70),Color.rgb(8,15,32)},
            null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,brush);brush.setShader(null);
        float base=h*.61f + 5f*MotionMath.wave(phase);
        brush.setColor(Color.rgb(23,25,38));
        canvas.drawRoundRect(w*.12f,base,w*.88f,base+h*.1f,20,20,brush);
        brush.setColor(Color.rgb(80,86,113));
        canvas.drawRoundRect(w*.28f,base-h*.085f,w*.72f,base+h*.025f,16,16,brush);
        brush.setColor(Color.rgb(8,11,21));
        canvas.drawCircle(w*.28f,base+h*.10f,w*.065f,brush);
        canvas.drawCircle(w*.72f,base+h*.10f,w*.065f,brush);
        brush.setColor(Color.rgb(185,209,255));
        canvas.drawCircle(w*.86f,base+h*.035f,w*.016f,brush);
    }
    private void drawAtmosphere(Canvas canvas, int w, int h, float phase,
                                MotionSettings settings) {
        if(gradient==null || gradientW!=w || gradientH!=h) {
            gradientW=w;gradientH=h;
            gradient=new LinearGradient(0,0,0,h,
                new int[]{Color.argb(65,8,14,36),Color.argb(0,0,0,0),
                    Color.argb(165,2,5,17)},
                new float[]{0f,.48f,1f},Shader.TileMode.CLAMP);
        }
        brush.setShader(gradient);
        canvas.drawRect(0,0,w,h,brush);
        brush.setShader(null);

        float amount=settings.strength/100f;
        boolean rain=MotionSettings.RAIN.equals(settings.mode);
        boolean neon=MotionSettings.NEON.equals(settings.mode);
        int r=neon?255:rain?90:126;
        int g=neon?60:rain?155:100;
        int b=neon?190:255;
        // No giant solid translucent spotlight: it used to cover the car
        // with an opaque-looking purple disc on a tall phone screen.
        // Keep light reflections BELOW the car image, not across its body.
        float lightPulse=MotionMath.softPulse(phase);
        brush.setStrokeCap(Paint.Cap.ROUND);
        brush.setStrokeWidth(Math.max(1f,w*(neon?.006f:.003f)));
        for (int i=0;i<(neon?5:3);i++) {
            float track=MotionMath.softPulse(phase+i*.18f);
            float y=h*(.79f+i*.036f);
            float x=w*(.12f+.15f*track);
            float len=w*(.05f+.13f*lightPulse);
            int alpha=(int)((neon?32:rain?14:19)*amount*(.4f+.6f*track));
            brush.setColor(Color.argb(alpha,r,g,b));
            canvas.drawLine(x,y,x+len,y,brush);
        }

        // Rain is anchored to periodic integer tracks. Frame 0 equals frame 8s.
        int n=rain?PARTICLES*2:neon?PARTICLES/3:PARTICLES;
        brush.setStrokeCap(Paint.Cap.ROUND);
        brush.setStrokeWidth(Math.max(1.1f,w/650f));
        for (int i=0;i<n;i++) {
            float depth=.4f+LoopMath.wrap01(i*.3492f)*.6f;
            float px=LoopMath.wrap01(i*.74341f+phase*(i%2+1)) * w;
            float py=LoopMath.wrap01(i*.61803f+phase*(i%3+1)) * h;
            brush.setColor(Color.argb((int)((rain?128:neon?44:76)*depth*amount),
                170,203,255));
            canvas.drawLine(px,py,px-depth*w*.01f,py+depth*h*.023f,brush);
        }

        // Continuous moving reflections, never abruptly reset at loop seam.
        brush.setStrokeCap(Paint.Cap.BUTT);
        for (int i=0;i<8;i++) {
            float y=h*(.76f+i*.024f);
            float v=MotionMath.softPulse(phase+i*.13f);
            float left=(.13f+.1f*v)*w;
            float right=(.87f-.1f*v)*w;
            brush.setStrokeWidth(Math.max(1f,w*.002f));
            brush.setColor(Color.argb((int)((18f+35f*v)*amount),r,g,b));
            canvas.drawLine(left,y,left+w*(.05f+.03f*v),y,brush);
            canvas.drawLine(right-w*(.05f+.03f*v),y,right,y,brush);
        }
    }
}
