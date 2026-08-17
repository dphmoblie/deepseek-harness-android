package io.deepseekharness.mobile

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(MobileRuntimePlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
