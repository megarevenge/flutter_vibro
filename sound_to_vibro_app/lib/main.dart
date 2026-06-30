import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SoundToVibroApp());
}

class SoundToVibroApp extends StatelessWidget {
  const SoundToVibroApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Sound to Vibro',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.teal),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  // Must match the channel names used in MainActivity.kt
  static const _methodChannel =
      MethodChannel('com.example.soundtovibro/control');
  static const _eventChannel =
      EventChannel('com.example.soundtovibro/events');

  StreamSubscription? _eventSub;
  bool _isActive = false;
  int _intensity = 0;
  double _db = -60.0;
  bool _hasVibrator = true;
  bool _hasAmplitudeControl = true;
  String _statusMessage = '';

  @override
  void initState() {
    super.initState();
    _checkRunning();
  }

  Future<void> _checkRunning() async {
    final running =
        await _methodChannel.invokeMethod<bool>('isRunning') ?? false;
    if (!mounted) return;
    setState(() => _isActive = running);
    if (running) _subscribeToEvents();
  }

  void _subscribeToEvents() {
    _eventSub?.cancel();
    _eventSub = _eventChannel.receiveBroadcastStream().listen((event) {
      final map = Map<String, dynamic>.from(event as Map);
      if (map.containsKey('error')) {
        setState(() => _statusMessage = map['error'].toString());
        return;
      }
      setState(() {
        _db = (map['db'] as num).toDouble();
        _intensity = (map['intensity'] as num).toInt();
        _hasVibrator = map['hasVibrator'] as bool? ?? true;
        _hasAmplitudeControl = map['hasAmplitudeControl'] as bool? ?? true;
        _statusMessage = '';
      });
    }, onError: (e) {
      if (mounted) setState(() => _statusMessage = 'Stream error: $e');
    });
  }

  Future<bool> _ensurePermissions() async {
    final micStatus = await Permission.microphone.request();
    if (!micStatus.isGranted) {
      setState(() => _statusMessage =
          'Microphone permission is required for this app to work.');
      return false;
    }

    // Android 13+ requires this for the foreground-service notification to
    // be visible. The service still works without it, but the user should
    // see what's running, so we ask anyway.
    if (await Permission.notification.isDenied) {
      await Permission.notification.request();
    }

    return true;
  }

  Future<void> _toggleService() async {
    if (_isActive) {
      await _methodChannel.invokeMethod('stopService');
      await _eventSub?.cancel();
      if (!mounted) return;
      setState(() {
        _isActive = false;
        _intensity = 0;
        _statusMessage = '';
      });
      return;
    }

    final granted = await _ensurePermissions();
    if (!granted) return;

    final started =
        await _methodChannel.invokeMethod<bool>('startService') ?? false;
    if (!mounted) return;
    if (started) {
      setState(() => _isActive = true);
      _subscribeToEvents();
    } else {
      setState(() => _statusMessage = 'Failed to start the service.');
    }
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final fraction = (_intensity / 100).clamp(0.0, 1.0);

    return Scaffold(
      appBar: AppBar(title: const Text('Sound to Vibro')),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                GestureDetector(
                  onTap: _toggleService,
                  child: Container(
                    width: 140,
                    height: 140,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _isActive
                          ? Colors.teal.withOpacity(0.15 + fraction * 0.5)
                          : Colors.grey.shade300,
                      border: Border.all(
                        color: _isActive ? Colors.teal : Colors.grey,
                        width: 3,
                      ),
                    ),
                    child: Icon(
                      _isActive ? Icons.mic : Icons.mic_off,
                      size: 64,
                      color:
                          _isActive ? Colors.teal.shade700 : Colors.grey.shade600,
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  _isActive ? 'Listening…' : 'Tap to start',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 16),
                if (_isActive) ...[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: LinearProgressIndicator(
                      value: fraction,
                      minHeight: 10,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text('${_db.toStringAsFixed(1)} dB · intensity $_intensity%'),
                  const SizedBox(height: 4),
                  Text(
                    _hasVibrator
                        ? (_hasAmplitudeControl
                            ? 'Amplitude-controlled vibration'
                            : 'Pulse-based vibration (no amplitude control on this device)')
                        : 'This device reports no vibrator hardware',
                    style: Theme.of(context).textTheme.bodySmall,
                    textAlign: TextAlign.center,
                  ),
                ],
                if (_statusMessage.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  Text(
                    _statusMessage,
                    style: const TextStyle(color: Colors.redAccent),
                    textAlign: TextAlign.center,
                  ),
                ],
                const SizedBox(height: 24),
                Text(
                  'Runs as a foreground service with the Android microphone '
                  'privacy indicator visible. No audio is ever written to disk.',
                  style: Theme.of(context).textTheme.bodySmall,
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}