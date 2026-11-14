import ai.motionwall.cars.MotionMath;
public class MotionMathTest {
 static void close(float a,float b) {
  if(Math.abs(a-b)>0.00001f)throw new AssertionError(a+" != "+b);
 }
 public static void main(String[] args) {
  if(MotionMath.clamp(10,20,100)!=20 || MotionMath.clamp(150,20,100)!=100)
   throw new AssertionError("strength bounds");
  if(MotionMath.fitScale(0,100,400,800)!=0)throw new AssertionError("bad dimensions");
  float landscape=MotionMath.fitScale(1792,1024,886,1920);
  if(landscape*1792>886*.941f || landscape*1024>1920*.761f)
   throw new AssertionError("car crop!");
  if(landscape*1792<800)throw new AssertionError("photo too small");
  float portrait=MotionMath.fitScale(900,1800,886,1920);
  if(portrait*900>886*.941f || portrait*1800>1920*.761f)
   throw new AssertionError("portrait crop!");
  if(MotionMath.coverScale(1792,1024,886,1920)*1024<1920)
   throw new AssertionError("environment leaves bars");
  close(MotionMath.wave(0f),MotionMath.wave(1f));
  close(MotionMath.softPulse(0f),MotionMath.softPulse(1f));
  if(MotionMath.frameDelay(false)!=33 || MotionMath.frameDelay(true)!=100)
   throw new AssertionError("preview frame interval");
  if(MotionMath.renderDelay(9,false,false)!=24)throw new AssertionError("GPU fps");
  if(MotionMath.renderDelay(9,false,true)!=58)throw new AssertionError("software fps");
  if(MotionMath.renderDelay(9,true,false)!=91)throw new AssertionError("battery saver");
  if(MotionMath.renderDelay(120,false,false)!=8)throw new AssertionError("min idle");
  if(MotionMath.renderDelay(-2,false,false)!=33)throw new AssertionError("negative draw timing");
  System.out.println("PASS: photo fit, cover environment, loop seam, bounds, battery saver");
 }
}
