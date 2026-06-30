package com.example.soundtovibro

import android.content.Intent
import android.os.Build
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val methodChannelName = "com.example.soundtovibro/control"
    private val eventChannelName = "com.example.soundtovibro/events"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startService" -> {
                        val intent = Intent(this, SoundToVibroService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        result.success(true)
                    }

                    "stopService" -> {
                        val intent = Intent(this, SoundToVibroService::class.java).apply {
                            action = SoundToVibroService.ACTION_STOP
                        }
                        startService(intent)
                        result.success(true)
                    }

                    "isRunning" -> result.success(SoundToVibroService.isRunning)

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    // The service posts data on the main thread, so it's safe
                    // to forward straight into the sink from here.
                    SoundToVibroService.dataListener = { data -> events?.success(data) }
                }

                override fun onCancel(arguments: Any?) {
                    SoundToVibroService.dataListener = null
                }
            })
    }
}
