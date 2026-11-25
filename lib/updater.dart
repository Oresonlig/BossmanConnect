/// Självuppdatering, samma upplägg som TheChain-MK2 (lib/app/updater.dart).
/// Lärdomar från MK1/MK2 som styr koden:
///   * versionskollen läser version.json som CI lägger i releasen (ingen
///     GitHub-API-kvot) och den laddas upp SIST, efter APK:n;
///   * APK:n laddas ner i appen och lämnas till Androids installationsdialog,
///     användaren bekräftar alltid (aldrig tyst; Samsung Auto Blocker);
///   * SHA-256 kontrolleras innan installation;
///   * "kunde inte kolla" får aldrig se ut som "du har senaste".
library;

import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:ota_update/ota_update.dart';

/// Byggnumret sätts av CI (--dart-define=BUILD=<körningsnummer>). 0 = lokalt bygge.
const currentBuild = int.fromEnvironment('BUILD');

/// Releasen som CI publicerar (.github/workflows/build.yml).
const releaseBase = 'https://github.com/Oresonlig/BossmanConnect/releases/download/app-latest';

/// Samsungs Auto Blocker stoppar installationen TYST — appen får inget fel
/// tillbaka. Därför visas tipset innan dialogen, inte efter.
const autoBlockerTip =
    "Nothing happens? Turn off your phone's auto blocker (Samsung: Settings › Security and privacy › Auto Blocker) and tap Update again.";

class UpdateInfo {
  const UpdateInfo({required this.build, required this.apkUrl, this.sha256});
  final int build;
  final String apkUrl;
  final String? sha256;
}

class UpdateCheckFailed implements Exception {
  const UpdateCheckFailed(this.reason);
  final String reason;
  @override
  String toString() => 'UpdateCheckFailed: $reason';
}

/// Tolkar version.json; null om filen är trasig.
UpdateInfo? parseVersionJson(String body, String base) {
  try {
    final j = (jsonDecode(body) as Map).cast<String, Object?>();
    final build = (j['build'] as num?)?.toInt();
    if (build == null) return null;
    return UpdateInfo(
      build: build,
      apkUrl: '$base/${j['apk'] ?? 'bossmanconnect.apk'}',
      sha256: j['sha256'] as String?,
    );
  } catch (_) {
    return null;
  }
}

class Updater {
  Updater({this.build = currentBuild, http.Client? client}) : _client = client ?? http.Client();

  final int build;
  final http.Client _client;

  /// Nyare bygge, eller null = du har senaste. Kastar [UpdateCheckFailed] vid
  /// nätfel eller trasig fil.
  Future<UpdateInfo?> check() async {
    if (build <= 0) return null; // lokala byggen uppdaterar inte sig själva
    final http.Response res;
    try {
      res = await _client
          .get(Uri.parse('$releaseBase/version.json?t=${DateTime.now().millisecondsSinceEpoch}'))
          .timeout(const Duration(seconds: 15));
    } catch (e) {
      throw UpdateCheckFailed('$e');
    }
    if (res.statusCode != 200) throw UpdateCheckFailed('HTTP ${res.statusCode}');
    final info = parseVersionJson(res.body, releaseBase);
    if (info == null) throw const UpdateCheckFailed('broken version.json');
    return info.build > build ? info : null;
  }

  /// Laddar ner och öppnar installationsdialogen. Strömmar läsbar status.
  Stream<String> install(UpdateInfo info) async* {
    yield 'Downloading…';
    await for (final e in OtaUpdate().execute(
      info.apkUrl,
      destinationFilename: 'bossmanconnect-update.apk',
      sha256checksum: info.sha256,
    )) {
      switch (e.status) {
        case OtaStatus.DOWNLOADING:
          yield 'Downloading… ${e.value ?? ''}%';
        case OtaStatus.INSTALLING:
          yield 'Confirm the install in the dialog. $autoBlockerTip';
        case OtaStatus.INSTALLATION_DONE:
          yield 'Installed';
        case OtaStatus.PERMISSION_NOT_GRANTED_ERROR:
          yield 'Allow BossmanConnect to install apps, then tap Update again.';
        case OtaStatus.CHECKSUM_ERROR:
          yield 'Download was corrupted — tap Update again.';
        case OtaStatus.ALREADY_RUNNING_ERROR:
          yield 'An update is already downloading.';
        case OtaStatus.CANCELED:
          yield 'Update canceled.';
        case OtaStatus.INSTALLATION_ERROR:
          yield 'Install blocked. $autoBlockerTip';
        case OtaStatus.DOWNLOAD_ERROR || OtaStatus.INTERNAL_ERROR:
          yield 'Update failed: ${e.value ?? 'unknown error'}';
      }
    }
  }
}
