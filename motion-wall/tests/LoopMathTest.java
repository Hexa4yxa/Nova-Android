import ai.motionwall.cars.LoopMath;
public class LoopMathTest {
 static void close(float a,float b) {if(Math.abs(a-b)>0.00001f)throw new AssertionError(a+" != "+b);}
 public static void main(String[] args) {
  close(LoopMath.phase(0),0f);close(LoopMath.phase(8000),0f);
  close(LoopMath.phase(-8000),0f);close(LoopMath.phase(4000),0.5f);
  close(LoopMath.wave(0),LoopMath.wave(1));
  close(LoopMath.smooth(0),LoopMath.smooth(1));
  close(LoopMath.wrap01(-.25f),.75f);
  for(int i=0;i<16000;i+=17){float t=LoopMath.phase(i);if(t<0||t>=1)throw new AssertionError(t);}
  System.out.println("PASS: seamless 8-second animation phase");
 }
}
