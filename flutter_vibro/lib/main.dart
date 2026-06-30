import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:record/record.dart';
import 'package:vibration/vibration.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:path_provider/path_provider.dart';
import 'dart:ui';

void startCallback() {
  FlutterForegroundTask.setTaskHandler(SoundToVibroHandler());
}

class SoundToVibroHandler extends TaskHandler {
  final _audioRecorder = AudioRecorder();
  Timer? _timer;
  bool _hasVibrator = false;
  bool _hasAmplitudeControl = false;

  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {
    WidgetsFlutterBinding.ensureInitialized();
    DartPluginRegistrant.ensureInitialized();

    await Future.delayed(const Duration(milliseconds: 100));

    _hasVibrator = await Vibration.hasVibrator();
    _hasAmplitudeControl = await Vibration.hasAmplitudeControl();
    FlutterForegroundTask.sendDataToMain(
        'hasVibrator: $_hasVibrator, hasAmplitudeControl: $_hasAmplitudeControl');

    try {
      if (await _audioRecorder.hasPermission()) {
        final dir = await getTemporaryDirectory();
        final path =
            '${dir.path}/temp_audio_${DateTime.now().millisecondsSinceEpoch}.wav';

        await _audioRecorder.start(
          const RecordConfig(
            encoder: AudioEncoder.pcm16bits,
            sampleRate: 16000,
            numChannels: 1,
          ),
          path: path,
        );

        _timer = Timer.periodic(const Duration(milliseconds: 100), (timer) async {
          try {
            final amplitude = await _audioRecorder.getAmplitude();
            double currentDb = amplitude.current;

            double minDb = -50.0;
            if (currentDb < minDb) currentDb = minDb;

            double intensityMultiplier = 1.0 - (currentDb / minDb).abs();
            int vibroIntensity = (intensityMultiplier * 255).round().clamp(0, 255);

            FlutterForegroundTask.sendDataToMain(
                'amplitude: ${currentDb.toStringAsFixed(1)} dB, vibro: $vibroIntensity');

            if (vibroIntensity > 20 && _hasVibrator) {
              if (_hasAmplitudeControl) {
                Vibration.vibrate(duration: 80, amplitude: vibroIntensity);
              } else {
                // Device can't vary intensity — just buzz at default strength.
                Vibration.vibrate(duration: 80);
              }
            }
          } catch (e) {
            FlutterForegroundTask.sendDataToMain('timer error: $e');
          }
        });
      } else {
        FlutterForegroundTask.sendDataToMain('no mic permission in isolate');
      }
    } catch (e) {
      FlutterForegroundTask.sendDataToMain('start failed: $e');
    }
  }

  @override
  void onRepeatEvent(DateTime timestamp) {}

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {
    _timer?.cancel();
    await _audioRecorder.stop();
    await _audioRecorder.dispose();
  }
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FlutterForegroundTask.initCommunicationPort();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  bool _isActive = false;
  String _debugLine = '';

  @override
  void initState() {
    super.initState();
    _initForegroundTask();
    FlutterForegroundTask.addTaskDataCallback(_onTaskData);
  }

  @override
  void dispose() {
    FlutterForegroundTask.removeTaskDataCallback(_onTaskData);
    super.dispose();
  }

  void _onTaskData(Object data) {
    if (data is String) {
      setState(() => _debugLine = data);
    }
  }

  void _initForegroundTask() {
    FlutterForegroundTask.init(
      androidNotificationOptions: AndroidNotificationOptions(
        channelId: 'vibro_service',
        channelName: 'Sound to Vibro Service',
        channelDescription: 'Converts background noise into vibrations.',
        channelImportance: NotificationChannelImportance.LOW,
        priority: NotificationPriority.LOW,
      ),
      iosNotificationOptions: const IOSNotificationOptions(),
      foregroundTaskOptions: ForegroundTaskOptions(
        eventAction: ForegroundTaskEventAction.repeat(5000),
        autoRunOnBoot: false,
        allowWakeLock: true,
      ),
    );
  }

  Future<void> _toggleService() async {
    if (_isActive) {
      await FlutterForegroundTask.stopService();
      setState(() => _isActive = false);
    } else {
      // 1. Notification permission
      final notificationPermission =
          await FlutterForegroundTask.checkNotificationPermission();
      if (notificationPermission != NotificationPermission.granted) {
        await FlutterForegroundTask.requestNotificationPermission();
        return;
      }

      // 2. Runtime mic permission — must be granted BEFORE startService,
      // while app is foregrounded, or Android throws SecurityException.
      final micStatus = await Permission.microphone.request();
      if (!micStatus.isGranted) {
        setState(() => _debugLine = 'Microphone permission denied');
        return;
      }

      final result = await FlutterForegroundTask.startService(
        serviceTypes: [ForegroundServiceTypes.microphone],
        notificationTitle: 'Sound to Vibro Active',
        notificationText: 'Monitoring microphone levels...',
        callback: startCallback,
      );

      if (result is ServiceRequestSuccess) {
        setState(() => _isActive = true);
      } else {
        setState(() => _debugLine = 'Failed to start service: $result');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        appBar: AppBar(title: const Text('Sound to Vibro')),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              IconButton(
                iconSize: 72,
                color: _isActive ? Colors.green : Colors.grey,
                icon: Icon(_isActive ? Icons.mic : Icons.mic_off),
                onPressed: _toggleService,
              ),
              const SizedBox(height: 10),
              Text(
                _isActive ? "Background service running" : "Service stopped",
              ),
              const SizedBox(height: 20),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Text(
                  _debugLine,
                  style: const TextStyle(fontSize: 12, color: Colors.grey),
                  textAlign: TextAlign.center,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}