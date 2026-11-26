import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'updater.dart';

void main() => runApp(const BossmanApp());

// ---- Palett (grafisk overhaul 2026-11-26, Niklas ja) ----
const _bgTop = Color(0xFF0E1122);
const _bgBottom = Color(0xFF070810);
const _card = Color(0xFF171B31);
const _cardBorder = Color(0x14FFFFFF);
const _muted = Color(0xFF8A90B0);
const _teal = Color(0xFF3DD9D6);
const _partyA = Color(0xFF7B5CFF);
const _partyB = Color(0xFFE8508F);
const _ok = Color(0xFF4ADE80);
const _warn = Color(0xFFFBBF24);
const _bad = Color(0xFFF87171);

class BossmanApp extends StatelessWidget {
  const BossmanApp({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(
      seedColor: _partyA,
      brightness: Brightness.dark,
    ).copyWith(surface: _card, primary: _teal);
    return MaterialApp(
      title: 'BossmanConnect',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorScheme: scheme, useMaterial3: true, scaffoldBackgroundColor: _bgBottom),
      home: const HomePage(),
    );
  }
}

class Speaker {
  const Speaker(this.name, this.address, {this.battery = -1, this.connected = false, this.audio});
  final String name;
  final String address;
  final int battery; // Androids nivå, -1 = okänd
  final bool connected;
  final bool? audio; // telefonens ljud (A2DP) går hit; null = okänt

  Speaker copyWith({int? battery, bool? audio}) =>
      Speaker(name, address, battery: battery ?? this.battery, connected: connected, audio: audio ?? this.audio);
  bool get looksBose {
    final n = name.toLowerCase();
    return n.contains('bose') || n.contains('ninja') || n.contains('lantern') || n.contains('soundlink');
  }
}

class Partner {
  const Partner(this.name, this.address, this.linked);
  final String name;
  final String address;
  final bool linked;
}

/// Det CONNECT gav: en Bose-session (party möjligt) eller bara batteri (t.ex. Jabra).
class Connection {
  Connection({
    required this.address,
    required this.bose,
    this.firmware,
    this.battery = -1,
    this.audio,
    this.partners = const [],
  });
  final String address;
  final bool bose;
  final String? firmware;
  int battery;
  bool? audio; // telefonens ljud (A2DP) går till högtalaren; null = okänt
  List<Partner> partners;
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
  String? _partner;
  Connection? _conn;
  String? _busy; // 'connect' | 'party' | 'unlink' | 'send'
  bool _showDetails = false;
  Timer? _batteryPoll;

  // Självuppdatering (lib/updater.dart). Kollar BARA när man trycker CHECK FOR UPDATES,
  // som MK2:s SYNC NOW (Niklas 2026-11-26) — inga automatiska nätanrop.
  final _updater = Updater();
  UpdateInfo? _update;
  String? _updateStatus;
  bool _updateCheckFailed = false;
  bool _checkingUpdate = false;
  bool _installing = false;
  DateTime? _updateCheckedAt;

  @override
  void initState() {
    super.initState();
    _bose.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'log':
          _addLog(call.arguments as String);
        case 'battery':
          _onBattery(Map<String, Object?>.from(call.arguments as Map));
        case 'audio':
          _onAudio(Map<String, Object?>.from(call.arguments as Map));
        case 'disconnected':
          _onDisconnected();
      }
    });
    _init();
  }

  @override
  void dispose() {
    _batteryPoll?.cancel();
    _commands.dispose();
    _logScroll.dispose();
    super.dispose();
  }

  // ---------------- Uppdatering ----------------

  Future<void> _checkUpdate() async {
    if (_checkingUpdate || _installing) return;
    setState(() => _checkingUpdate = true);
    try {
      final info = await _updater.check();
      if (!mounted) return;
      setState(() {
        _update = info;
        _updateCheckFailed = false;
        _updateCheckedAt = DateTime.now();
      });
    } on UpdateCheckFailed {
      // "Kunde inte kolla" visas som just det — aldrig som "du har senaste".
      if (mounted) setState(() => _updateCheckFailed = true);
    } finally {
      if (mounted) setState(() => _checkingUpdate = false);
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

  // ---------------- Högtalare ----------------

  Future<void> _init() async {
    final settings = await _bose.invokeMapMethod<String, String?>('loadSettings') ?? {};
    _commands.text = settings['commands'] ?? '';
    _selected = settings['primary'];
    _partner = settings['partner'];

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
          .map((m) => Speaker(m['name'] as String, m['address'] as String,
              battery: (m['battery'] as int?) ?? -1,
              connected: (m['connected'] as bool?) ?? false,
              audio: m['audio'] as bool?))
          .toList()
        ..sort((a, b) {
          if (a.connected != b.connected) return a.connected ? -1 : 1;
          if (a.looksBose != b.looksBose) return a.looksBose ? -1 : 1;
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

  Speaker? get _selectedSpeaker {
    for (final s in _speakers) {
      if (s.address == _selected) return s;
    }
    return null;
  }

  void _onBattery(Map<String, Object?> m) {
    final address = m['address'] as String?;
    final level = (m['level'] as int?) ?? -1;
    if (address == null || !mounted) return;
    setState(() {
      _speakers = [for (final s in _speakers) s.address == address ? s.copyWith(battery: level) : s];
      final c = _conn;
      if (c != null && !c.bose && c.address == address) c.battery = level;
    });
  }

  void _onAudio(Map<String, Object?> m) {
    final address = m['address'] as String?;
    final connected = m['connected'] as bool?;
    if (address == null || connected == null || !mounted) return;
    setState(() {
      _speakers = [for (final s in _speakers) s.address == address ? s.copyWith(audio: connected) : s];
      final c = _conn;
      if (c != null && c.address == address) c.audio = connected;
    });
  }

  Future<void> _openBluetooth() async {
    try {
      await _bose.invokeMethod('openBluetoothSettings');
    } on PlatformException catch (e) {
      _addLog(e.message ?? e.code);
    }
  }

  void _onDisconnected() {
    _batteryPoll?.cancel();
    if (mounted) setState(() => _conn = null);
    _addLog('Disconnected from the speaker.');
  }

  Connection _parseConnection(String address, Map raw) {
    final partners = ((raw['partners'] as List?) ?? const [])
        .map((e) => e as Map)
        .map((m) => Partner(m['name'] as String, m['address'] as String, (m['linked'] as bool?) ?? false))
        .toList();
    return Connection(
      address: address,
      bose: (raw['bose'] as bool?) ?? false,
      firmware: raw['firmware'] as String?,
      battery: (raw['battery'] as int?) ?? -1,
      audio: raw['audio'] as bool?,
      partners: partners,
    );
  }

  void _pickPartner(Connection c) {
    if (c.partners.any((p) => p.address == _partner)) return;
    final linked = c.partners.where((p) => p.linked);
    final lantern = c.partners.where((p) => p.name.toLowerCase().contains('lantern'));
    _partner = linked.isNotEmpty
        ? linked.first.address
        : lantern.isNotEmpty
            ? lantern.first.address
            : (c.partners.isEmpty ? null : c.partners.first.address);
  }

  Future<void> _connect() async {
    final address = _selected;
    if (address == null || _busy != null) return;
    await _bose.invokeMethod('saveSettings', {'primary': address});
    _batteryPoll?.cancel();
    setState(() {
      _busy = 'connect';
      _conn = null;
    });
    try {
      final raw = await _bose.invokeMapMethod<String, Object?>('connect', {'address': address}) ?? {};
      final c = _parseConnection(address, raw);
      if (c.bose && c.battery < 0) c.battery = _selectedSpeaker?.battery ?? -1;
      c.audio ??= _selectedSpeaker?.audio;
      setState(() {
        _conn = c;
        _pickPartner(c);
      });
      if (c.bose) {
        _addLog('Connected to ${_selectedSpeaker?.name ?? address}.');
        _batteryPoll = Timer.periodic(const Duration(seconds: 60), (_) => _pollBattery());
      } else {
        _addLog('No Bose control channel – showing battery only.');
      }
    } on PlatformException catch (e) {
      _addLog(e.message ?? e.code);
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  Future<void> _disconnect() async {
    _batteryPoll?.cancel();
    await _bose.invokeMethod('disconnect');
    if (mounted) setState(() => _conn = null);
  }

  Future<void> _pollBattery() async {
    final c = _conn;
    if (c == null || !c.bose || _busy != null) return;
    try {
      final level = await _bose.invokeMethod<int>('battery') ?? -1;
      if (mounted && level >= 0) setState(() => c.battery = level);
    } on PlatformException {
      // 'disconnected' kommer separat.
    }
  }

  Future<void> _refreshStatus() async {
    final c = _conn;
    if (c == null || !c.bose) return;
    try {
      final raw = await _bose.invokeMapMethod<String, Object?>('status') ?? {};
      final fresh = _parseConnection(c.address, raw);
      if (mounted) {
        setState(() {
          c.partners = fresh.partners;
          if (fresh.battery >= 0) c.battery = fresh.battery;
        });
      }
    } on PlatformException catch (e) {
      _addLog(e.message ?? e.code);
    }
  }

  Future<void> _partyAction(String method) async {
    final partner = _partner;
    if (partner == null || _busy != null) return;
    await _bose.invokeMethod('saveSettings', {'partner': partner});
    setState(() => _busy = method);
    try {
      final ok = await _bose.invokeMethod<bool>(method, {'partner': partner}) ?? false;
      final what = method == 'party' ? 'Party mode' : 'Unlink';
      _addLog(ok ? '$what ✔' : '$what failed ✘');
      final c = _conn;
      if (ok && c != null && mounted) {
        // Markera direkt i stället för att fråga högtalaren: den är upptagen strax efter en
        // länkning, och en statusfråga då fällde förr hela sessionen (fälttest 2026-11-26).
        setState(() => c.partners = [
              for (final p in c.partners)
                p.address == partner ? Partner(p.name, p.address, method == 'party') : p,
            ]);
      }
      // Hämta riktig status när högtalaren har lugnat sig.
      Future.delayed(const Duration(seconds: 10), () {
        if (mounted && _busy == null) _refreshStatus();
      });
    } on PlatformException catch (e) {
      _addLog(e.message ?? e.code);
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  Future<void> _sendRaw() async {
    if (_busy != null) return;
    await _bose.invokeMethod('saveSettings', {'commands': _commands.text});
    setState(() => _busy = 'send');
    try {
      await _bose.invokeMethod('sendRaw', {'commands': _commands.text});
    } on PlatformException catch (e) {
      _addLog(e.message ?? e.code);
    } finally {
      if (mounted) setState(() => _busy = null);
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

  // ---------------- UI ----------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [_bgTop, _bgBottom]),
        ),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(18, 16, 18, 24),
            children: [
              _header(),
              const SizedBox(height: 20),
              _speakerCard(),
              if (_conn?.bose ?? false) ...[
                const SizedBox(height: 14),
                _partyCard(_conn!),
              ],
              const SizedBox(height: 14),
              _detailsCard(),
              _updateRow(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _header() {
    final text = Theme.of(context).textTheme;
    return Row(
      children: [
        Container(
          width: 48,
          height: 48,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: const LinearGradient(colors: [_partyA, _partyB]),
            boxShadow: [BoxShadow(color: _partyA.withValues(alpha: 0.35), blurRadius: 16)],
          ),
          child: const Icon(Icons.speaker_group_rounded, color: Colors.white),
        ),
        const SizedBox(width: 14),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('BossmanConnect',
                style: text.titleLarge?.copyWith(fontWeight: FontWeight.w800, letterSpacing: 0.3, color: Colors.white)),
            Text('Speakers · battery · party mode', style: text.bodySmall?.copyWith(color: _muted)),
          ],
        ),
      ],
    );
  }

  Widget _panel({required String title, required Widget child, Widget? trailing}) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: _card,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: _cardBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Text(title,
                  style: Theme.of(context)
                      .textTheme
                      .labelMedium
                      ?.copyWith(color: _muted, letterSpacing: 1.6, fontWeight: FontWeight.w700)),
              const Spacer(),
              if (trailing != null) trailing,
            ],
          ),
          const SizedBox(height: 10),
          child,
        ],
      ),
    );
  }

  Widget _dropdown<T>({
    required T? value,
    required List<DropdownMenuItem<T>> items,
    required ValueChanged<T?>? onChanged,
    required String hint,
  }) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: _cardBorder),
      ),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<T>(
          isExpanded: true,
          value: value,
          hint: Text(hint, style: const TextStyle(color: _muted)),
          dropdownColor: _card,
          borderRadius: BorderRadius.circular(14),
          items: items,
          onChanged: onChanged,
        ),
      ),
    );
  }

  Widget _speakerCard() {
    final c = _conn;
    final sp = _selectedSpeaker;
    final connecting = _busy == 'connect';
    return _panel(
      title: 'SPEAKER',
      trailing: IconButton(
        tooltip: 'Refresh list',
        visualDensity: VisualDensity.compact,
        icon: const Icon(Icons.refresh_rounded, color: _muted),
        onPressed: _busy != null ? null : _loadSpeakers,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _dropdown<String>(
            value: _selected,
            hint: 'No paired devices',
            items: [
              for (final s in _speakers)
                DropdownMenuItem(
                  value: s.address,
                  child: Row(
                    children: [
                      Icon(s.looksBose ? Icons.speaker_rounded : Icons.bluetooth_audio_rounded,
                          size: 20, color: s.connected ? _teal : _muted),
                      const SizedBox(width: 10),
                      Expanded(child: Text(s.name, overflow: TextOverflow.ellipsis)),
                      if (s.battery >= 0) _BatteryChip(level: s.battery),
                    ],
                  ),
                ),
            ],
            onChanged: _busy != null
                ? null
                : (v) => setState(() {
                      _selected = v;
                      if (_conn?.address != v) {
                        _conn = null;
                        _batteryPoll?.cancel();
                      }
                    }),
          ),
          const SizedBox(height: 14),
          if (c != null && c.address == _selected)
            Row(
              children: [
                _BatteryRing(level: c.battery),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(sp?.name ?? c.address,
                          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: Colors.white)),
                      const SizedBox(height: 4),
                      Text(
                        c.bose ? 'Bose · firmware ${c.firmware ?? '?'}' : 'Battery only · no party mode',
                        style: const TextStyle(color: _muted, fontSize: 13),
                      ),
                      const SizedBox(height: 6),
                      // Två skilda anslutningar: appens kontrollkanal och telefonens ljud (A2DP).
                      Wrap(
                        spacing: 6,
                        runSpacing: 6,
                        children: [
                          if (c.bose) const _StatusPill(label: 'Control', color: _ok),
                          _StatusPill(
                            label: switch (c.audio) { true => 'Audio', false => 'No audio', null => 'Audio ?' },
                            color: switch (c.audio) { true => _ok, false => _warn, null => _muted },
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                if (c.bose)
                  IconButton(
                    tooltip: 'Disconnect',
                    icon: const Icon(Icons.link_off_rounded, color: _muted),
                    onPressed: _busy != null ? null : _disconnect,
                  ),
              ],
            )
          else
            SizedBox(
              height: 52,
              child: FilledButton.tonalIcon(
                style: FilledButton.styleFrom(
                  backgroundColor: _teal.withValues(alpha: 0.14),
                  foregroundColor: _teal,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                ),
                onPressed: _selected == null || _busy != null ? null : _connect,
                icon: connecting
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.bluetooth_connected_rounded),
                label: Text(connecting ? 'CONNECTING …' : 'CONNECT',
                    style: const TextStyle(fontWeight: FontWeight.w700, letterSpacing: 1.2)),
              ),
            ),
          // Android låter inte appar slå på ljudanslutningen — genväg till inställningarna i stället.
          if (c != null && c.address == _selected && c.audio != true) ...[
            const SizedBox(height: 12),
            const Text("Your phone's sound isn't going to this speaker yet. Connect it in Bluetooth settings.",
                style: TextStyle(color: _muted, fontSize: 13)),
            const SizedBox(height: 8),
            SizedBox(
              height: 44,
              child: OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: _teal,
                  side: BorderSide(color: _teal.withValues(alpha: 0.4)),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                onPressed: _openBluetooth,
                icon: const Icon(Icons.settings_bluetooth_rounded, size: 20),
                label: const Text('OPEN BLUETOOTH', style: TextStyle(fontWeight: FontWeight.w700, letterSpacing: 1.2)),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _partyCard(Connection c) {
    Partner? linkedPartner;
    for (final p in c.partners) {
      if (p.linked) linkedPartner = p;
    }
    final partying = _busy == 'party';
    final unlinking = _busy == 'unlink';
    return _panel(
      title: 'PARTY MODE',
      trailing: _StatusPill(
        label: linkedPartner != null ? 'Linked · ${linkedPartner.name}' : 'Not linked',
        color: linkedPartner != null ? _ok : _muted,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _dropdown<String>(
            value: c.partners.any((p) => p.address == _partner) ? _partner : null,
            hint: 'No partner speakers',
            items: [
              for (final p in c.partners)
                DropdownMenuItem(
                  value: p.address,
                  child: Row(
                    children: [
                      Icon(Icons.speaker_outlined, size: 20, color: p.linked ? _ok : _muted),
                      const SizedBox(width: 10),
                      Expanded(child: Text(p.name, overflow: TextOverflow.ellipsis)),
                      if (p.linked) const Text('linked', style: TextStyle(color: _ok, fontSize: 12)),
                    ],
                  ),
                ),
            ],
            onChanged: _busy != null ? null : (v) => setState(() => _partner = v),
          ),
          const SizedBox(height: 14),
          _PartyButton(
            busy: partying,
            onPressed: _partner == null || _busy != null ? null : () => _partyAction('party'),
          ),
          const SizedBox(height: 8),
          TextButton.icon(
            style: TextButton.styleFrom(foregroundColor: _muted),
            onPressed: _partner == null || _busy != null ? null : () => _partyAction('unlink'),
            icon: unlinking
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.call_split_rounded, size: 18),
            label: Text(unlinking ? 'UNLINKING …' : 'UNLINK', style: const TextStyle(letterSpacing: 1.2)),
          ),
        ],
      ),
    );
  }

  Widget _detailsCard() {
    const mono = TextStyle(fontFamily: 'monospace', fontSize: 11.5, color: Color(0xFFB8BED8));
    return _panel(
      title: 'DETAILS',
      trailing: IconButton(
        visualDensity: VisualDensity.compact,
        icon: Icon(_showDetails ? Icons.expand_less_rounded : Icons.expand_more_rounded, color: _muted),
        onPressed: () => setState(() => _showDetails = !_showDetails),
      ),
      child: !_showDetails
          ? GestureDetector(
              onTap: () => setState(() => _showDetails = true),
              child: Text(_log.isEmpty ? 'Log and raw commands' : _log.last,
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: mono),
            )
          : Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                GestureDetector(
                  onLongPress: _copyLog,
                  child: Container(
                    height: 220,
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.35),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: ListView.builder(
                      controller: _logScroll,
                      itemCount: _log.length,
                      itemBuilder: (_, i) => Text(_log[i], style: mono),
                    ),
                  ),
                ),
                Row(
                  children: [
                    const Expanded(
                      child: Text('Long-press the log to copy it.', style: TextStyle(color: _muted, fontSize: 12)),
                    ),
                    TextButton(
                      style: TextButton.styleFrom(foregroundColor: _muted),
                      onPressed: _log.isEmpty ? null : () => setState(_log.clear),
                      child: const Text('CLEAR'),
                    ),
                  ],
                ),
                if (_conn?.bose ?? false) ...[
                  const SizedBox(height: 12),
                  TextField(
                    controller: _commands,
                    minLines: 2,
                    maxLines: 5,
                    style: mono.copyWith(fontSize: 13),
                    autocorrect: false,
                    enableSuggestions: false,
                    decoration: InputDecoration(
                      labelText: 'Raw commands (hex), one per line',
                      hintText: '02 02 01 00  # battery',
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerRight,
                    child: OutlinedButton(
                      onPressed: _busy != null ? null : _sendRaw,
                      child: const Text('SEND'),
                    ),
                  ),
                ],
              ],
            ),
    );
  }

  /// Liten rad längst ner, som MK2: status + CHECK FOR UPDATES, eller UPDATE när ett nyare bygge finns.
  Widget _updateRow() {
    final small = Theme.of(context).textTheme.bodySmall?.copyWith(color: _muted);
    final info = _update;
    String two(int n) => n.toString().padLeft(2, '0');
    final String line;
    if (info != null) {
      line = 'Build ${info.build} ready';
    } else if (_checkingUpdate) {
      line = 'Checking for updates…';
    } else if (_updateCheckFailed) {
      line = 'Could not check for updates';
    } else if (_updateCheckedAt case final t?) {
      line = 'Up to date · build $currentBuild · checked ${two(t.hour)}:${two(t.minute)}';
    } else {
      line = 'Build $currentBuild';
    }
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line, style: small),
                if (info != null && _updateStatus != null) Text(_updateStatus!, style: small),
              ],
            ),
          ),
          if (info != null)
            FilledButton(
              onPressed: _installing ? null : _installUpdate,
              child: const Text('UPDATE'),
            )
          else
            TextButton(
              onPressed: _checkingUpdate ? null : _checkUpdate,
              child: const Text('CHECK FOR UPDATES'),
            ),
        ],
      ),
    );
  }
}

Color _batteryColor(int level) => level < 0
    ? _muted
    : level <= 20
        ? _bad
        : level <= 50
            ? _warn
            : _ok;

class _BatteryRing extends StatelessWidget {
  const _BatteryRing({required this.level});
  final int level;

  @override
  Widget build(BuildContext context) {
    final color = _batteryColor(level);
    return SizedBox(
      width: 72,
      height: 72,
      child: Stack(
        alignment: Alignment.center,
        children: [
          SizedBox.expand(
            child: CircularProgressIndicator(
              value: level < 0 ? 0 : level / 100,
              strokeWidth: 7,
              strokeCap: StrokeCap.round,
              backgroundColor: Colors.white.withValues(alpha: 0.08),
              color: color,
            ),
          ),
          Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(level < 0 ? '–' : '$level%',
                  style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800, color: Colors.white)),
              Icon(Icons.battery_std_rounded, size: 12, color: color),
            ],
          ),
        ],
      ),
    );
  }
}

class _BatteryChip extends StatelessWidget {
  const _BatteryChip({required this.level});
  final int level;

  @override
  Widget build(BuildContext context) {
    final color = _batteryColor(level);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(Icons.battery_std_rounded, size: 14, color: color),
        const SizedBox(width: 2),
        Text('$level%', style: TextStyle(fontSize: 12, color: color)),
      ],
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.label, required this.color});
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(99),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(width: 6, height: 6, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
          const SizedBox(width: 6),
          Text(label, style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }
}

/// Stor party-knapp med gradient. Mättad men inte skrikig (Niklas tyckte den gamla var för ljus).
class _PartyButton extends StatelessWidget {
  const _PartyButton({required this.busy, required this.onPressed});
  final bool busy;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null;
    return Opacity(
      opacity: enabled || busy ? 1 : 0.45,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(20),
          gradient: LinearGradient(colors: [
            _partyA.withValues(alpha: 0.85),
            _partyB.withValues(alpha: 0.85),
          ]),
          boxShadow: [BoxShadow(color: _partyB.withValues(alpha: 0.25), blurRadius: 20, offset: const Offset(0, 6))],
        ),
        child: Material(
          type: MaterialType.transparency,
          child: InkWell(
            borderRadius: BorderRadius.circular(20),
            onTap: onPressed,
            child: SizedBox(
              height: 76,
              child: Center(
                child: busy
                    ? const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white)),
                          SizedBox(width: 12),
                          Text('LINKING …',
                              style: TextStyle(
                                  fontSize: 20, fontWeight: FontWeight.w800, letterSpacing: 2, color: Colors.white)),
                        ],
                      )
                    : const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.celebration_rounded, color: Colors.white),
                          SizedBox(width: 12),
                          Text('PARTY',
                              style: TextStyle(
                                  fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: 3, color: Colors.white)),
                        ],
                      ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
