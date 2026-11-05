package ai.nova.director;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NovaBrainPlugin.class);
        registerPlugin(NovaToolsPlugin.class);
        registerPlugin(NovaImagePlugin.class);
        registerPlugin(NovaImageModelsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
