package ai.nova.director;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NovaBrainPlugin.class);
        registerPlugin(NovaToolsPlugin.class);
        registerPlugin(NovaImagePlugin.class);
        registerPlugin(NovaMotionPlugin.class);
        registerPlugin(NovaImageModelsPlugin.class);
        registerPlugin(NovaModelBackupPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
