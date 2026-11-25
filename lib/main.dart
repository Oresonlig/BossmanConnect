import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'updater.dart';

void main() => runApp(const BossmanApp());

class BossmanApp extends StatelessWidget {
  const BossmanApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'BossmanConnect',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.blueGrey, useMaterial3: true),
      home: const HomePage(),
    );
  }
}

class Speaker {
  const Speaker(this.name, this.address);
  final String name;
  final String address;
  bool get isBose {
    final n = name.toLowerCase();
    return n.contains('bose') || n.contains('ninja') || n.contains('lantern');
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  /// Kanal till Kotlin-koden i android/app/src/main/kotlin/.../MainActivity.kt
  static const _bose = MethodChannel('bossman/bose');

  final _commands = TextEditingController();
  final _logScroll = ScrollController();
  final List<String> _log = [];
  List<Speaker> _speakers = [];
  String? _selected;
  bool _busy = false;
  bool _showAdvanced = false;

  // Självuppdatering (lib/updater.dart).
  final _updater = Updater();
  late final AppLifecycleListener _lifecycle;
  UpdateInfo? _update;
  String? _updateStatus;
  bool _updateCheckFailed = false;
  bool _checkingUpdate = false;
  bool _installing = false;

  @override
  void initState() {
    super.initState();
    _bose.setMethodCallHandler((call) async {
      if (call.method == 'log') _addLog(call.arguments as String);
    });
    // Kolla vid start och varje gång appen kommer tillbaka i förgrunden — aldrig i bakgrunden.
    _lifecycle = AppLifecycleListener(onResume: _checkUpdate);
    _init();
    _checkUpdate();
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    _commands.dispose();
    _logScroll.dispose();
    super.dispose();
  }

  Future<void> _checkUpdate() async {
    if (_checkingUpdate || _installing) return;
    _checkingUpdate = true;
    try {
      final info = await _updater.check();
      if (!mounted) return;
      setState(() {
        _update = info;
        _updateCheckFailed = false;
      });
    } on UpdateCheckFailed {
      // "Kunde inte kolla" visas som just det — aldrig som "du har senaste".
      if (mounted) setState(() => _updateCheckFailed = true);
    } finally {
      _checkingUpdate = false;
    }
  }

  Future<void> _installUpdate() async {
    final info = _update;
    if (info == null || _installing) return;
    setState(() => _installing = true);
    try {
      await for (final s in _updater.install(info)) {
        if (mounted) setState(() => _updateStatus = s);
      }
    } catch (e) {
      // Annars stod det kvar på "Laddar ner …" och knappen var låst för gott (MK2-lärdom).
      if (mounted) setState(() => _updateStatus = 'Update failed: $e');
    } finally {
      if (mounted) setState(() => _installing = false);
    }
  }

  Widget _updateBanner() {
    final info = _update;
    if (info == null) {
      if (!_updateCheckFailed) return const SizedBox.shrink();
      return Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text("Couldn't check for updates (no connection?).",
            style: Theme.of(context).textTheme.bodySmall),
      );
    }
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(child: Text('New version available (build ${info.build}, you have $currentBuild).')),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: _installing ? null : _installUpdate,
                  child: const Text('UPDATE'),
                ),
              ],
            ),
            if (_updateStatus != null) ...[
              const SizedBox(height: 8),
              Text(_updateStatus!, style: Theme.of(context).textTheme.bodySmall),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _init() async {
    final settings = await _bose.invokeMapMethod<String, String>('loadSettings') ?? {};
    _commands.text = settings['commands'] ?? '';
    _selected = settings['primary'];
    setState(() => _showAdvanced = _commands.text.trim().isEmpty);

    final granted = await _bose.invokeMethod<bool>('requestPermission') ?? false;
    if (!granted) {
      _addLog('The app needs Bluetooth permission to work.');
      return;
    }
    await _loadSpeakers();
  }

  Future<void> _loadSpeakers() async {
    try {
      final raw = await _bose.invokeListMethod<Object?>('bondedDevices') ?? [];
      final speakers = raw
          .map((e) => e as Map)
          .map((m) => Speaker(m['name'] as String, m['address'] as String))
          .toList()
        ..sort((a, b) {
          if (a.isBose != b.isBose) return a.isBose ? -1 : 1;
          return a.name.compareTo(b.name);
        });
      setState(() {
        _speakers = speakers;
        if (!speakers.any((s) => s.address == _selected)) {
          // Niklas svarta högtalare heter Ninja (den vita: Lantern) — förval om inget sparat val finns.
          final ninja = speakers.where((s) => s.name.toLowerCase().contains('ninja'));
          _selected = ninja.isNotEmpty ? ninja.first.address : (speakers.isEmpty ? null : speakers.first.address);
        }
      });
      if (speakers.isEmpty) {
        _addLog("No paired devices. Pair the speaker in your phone's Bluetooth settings first.");
      }
    } on PlatformException catch (e) {
      _addLog(e.message ?? e.code);
    }
  }

  Future<void> _connect() async {
    final address = _selected;
    if (address == null) {
      _addLog('Pick a speaker first.');
      return;
    }
    if (_commands.text.trim().isEmpty) {
      _addLog('No commands entered – only testing the connection (see README).');
    }
    await _bose.invokeMethod('saveSettings', {'primary': address, 'commands': _commands.text});

    setState(() => _busy = true);
    try {
      final ok = await _bose.invokeMethod<bool>('connect', {
            'address': address,
            'commands': _commands.text,
          }) ??
          false;
      _addLog(ok ? 'DONE ✔' : 'FAILED ✘ – try again or restart the speakers.');
    } on PlatformException catch (e) {
      _addLog(e.message ?? e.code);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _addLog(String msg) {
    if (!mounted) return;
    final now = DateTime.now();
    String two(int n) => n.toString().padLeft(2, '0');
    setState(() => _log.add('${two(now.hour)}:${two(now.minute)}:${two(now.second)}  $msg'));
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_logScroll.hasClients) _logScroll.jumpTo(_logScroll.position.maxScrollExtent);
    });
  }

  void _copyLog() {
    Clipboard.setData(ClipboardData(text: _log.join('\n')));
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Log copied')));
  }

  @override
  Widget build(BuildContext context) {
    final mono = const TextStyle(fontFamily: 'monospace', fontSize: 12);

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('BossmanConnect',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold)),
              const SizedBox(height: 16),
              _updateBanner(),
              const Text('Speaker your phone plays to (Ninja, the black one):'),
              Row(
                children: [
                  Expanded(
                    child: DropdownButton<String>(
                      isExpanded: true,
                      value: _selected,
                      hint: const Text('No speakers found'),
                      items: [
                        for (final s in _speakers)
                          DropdownMenuItem(value: s.address, child: Text('${s.name}  (${s.address})')),
                      ],
                      onChanged: _busy ? null : (v) => setState(() => _selected = v),
                    ),
                  ),
                  IconButton(
                    tooltip: 'Refresh list',
                    icon: const Icon(Icons.refresh),
                    onPressed: _busy ? null : _loadSpeakers,
                  ),
                ],
              ),
              const SizedBox(height: 20),
              SizedBox(
                height: 96,
                child: FilledButton(
                  onPressed: _busy ? null : _connect,
                  child: Text(_busy ? 'LINKING …' : 'LINK SPEAKERS', style: const TextStyle(fontSize: 22)),
                ),
              ),
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: () => setState(() => _showAdvanced = !_showAdvanced),
                  icon: Icon(_showAdvanced ? Icons.expand_more : Icons.chevron_right),
                  label: const Text('Advanced: commands (hex)'),
                ),
              ),
              if (_showAdvanced)
                TextField(
                  controller: _commands,
                  minLines: 3,
                  maxLines: 6,
                  style: mono.copyWith(fontSize: 13),
                  autocorrect: false,
                  enableSuggestions: false,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    hintText: 'One command per line, e.g.\n01 02 03 04  # comment',
                  ),
                ),
              const SizedBox(height: 16),
              const Text('Log (long-press to copy):'),
              const SizedBox(height: 4),
              Expanded(
                child: GestureDetector(
                  onLongPress: _copyLog,
                  child: Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: const Color(0x0D000000),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: ListView.builder(
                      controller: _logScroll,
                      itemCount: _log.length,
                      itemBuilder: (_, i) => Text(_log[i], style: mono),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
