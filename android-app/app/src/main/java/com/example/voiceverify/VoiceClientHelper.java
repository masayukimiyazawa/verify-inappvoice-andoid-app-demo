package com.example.voiceverify;

import android.content.Context;
import com.vonage.android_core.VGClientInitConfig;
import com.vonage.voice.api.VoiceClient;

public class VoiceClientHelper {
    public static VoiceClient createClient(Context context, VGClientInitConfig config) {
        return VoiceClient.createClient(context, config);
    }

    public static VoiceClient createClient(Context context) {
        return VoiceClient.createClient(context);
    }
}
