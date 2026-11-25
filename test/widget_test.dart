// Finns så att `flutter create` i CI inte lägger in sin mall-test (den
// refererar en MyApp som inte finns och fäller analyze, som i MK2).
import 'package:bossman_connect/updater.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('lokala byggen har byggnummer 0 och uppdaterar inte sig själva', () {
    expect(currentBuild, 0);
  });
}
