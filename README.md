# BossmanConnect

En Flutter-app för Android med en enda knapp: **LINK SPEAKERS** (appens UI är på engelska). Den sätter två
Bose SoundLink Revolve+ i Party- eller Stereo-läge.

Appen skickar exakt samma byte som Bose Connect, men utan allt runtomkring, och försöker
automatiskt igen (4 gånger) om anslutningen fallerar.

## Struktur

- `lib/main.dart`: gränssnittet (Flutter/Dart)
- `android/app/src/main/kotlin/se/bossman/bossman_connect/MainActivity.kt`: brygga Flutter ↔ Bluetooth
- `android/app/src/main/kotlin/se/bossman/bossman_connect/BoseLink.kt`: Bluetooth-anslutning, sändning, omförsök
- `android/app/src/main/AndroidManifest.xml`: Bluetooth-behörigheter
- `tools/extract.js`: plockar ut Bose Connects kommandon ur telefonens Bluetooth-logg

## 1. Första gången: generera resten av Flutter-projektet

Det som saknas (Gradle-filer, ikoner, teman) genereras av Flutter. Befintliga filer skrivs **inte** över:

```
flutter create --org se.bossman --project-name bossman_connect --platforms android .
```

## 2. Bygg APK

```
flutter build apk --release
```

Filen hamnar i `build\app\outputs\flutter-apk\app-release.apk`. Ladda upp den till hemsidan
på samma sätt som MK2-appen. Under utveckling kan du också köra `flutter run` med telefonen i USB.

## 3. Fånga Bose Connects kommando (görs EN gång)

Boses protokoll är inte offentligt, så vi hämtar de exakta byten från Bose Connect själv.
Det räcker att Bose Connect lyckas koppla ihop högtalarna **en enda gång** medan loggen är på.

1. *Utvecklaralternativ* → **Bluetooth HCI-snooplogg** → välj **Aktiverad** (inte "Filtrerad").
2. Stäng av och slå på Bluetooth på telefonen.
3. Öppna Bose Connect och koppla ihop högtalarna i det läge du vill ha.
   Kör tills det lyckas. Notera ungefär vilken tid det var.
4. Med telefonen i USB, kör i projektmappen:

   ```
   adb bugreport capture\bugreport.zip
   node tools/extract.js capture\bugreport.zip
   ```

   Rader med **`→ TILL`** till den svarta högtalaren, runt tiden när du tryckte i Bose Connect,
   är kommandona.
5. Stäng av HCI-snoop-loggen igen.

> Samsung: om ingen `btsnoop_hci.log` finns i bugreporten, ring `*#9900#` och aktivera
> Bluetooth-loggning där, upprepa sedan steg 2–4.

## 4. Lägg in kommandona i appen

Öppna **Advanced: commands (hex)** och klistra in byten, en rad per kommando:

```
01 02 03 04   # exempel – använd DINA värden från steg 3
```

Tryck **LINK SPEAKERS**. Allt sparas, så efter detta är det bara en knapp.

## Tips

- **Tvångsstoppa Bose Connect** innan du använder appen. Den kan hålla kontrollkanalen upptagen.
- Kommandot innehåller troligen den vita högtalarens adress, så det fungerar bara med just dina högtalare.
- Loggen i appen kan kopieras (håll inne på den) om något strular.
