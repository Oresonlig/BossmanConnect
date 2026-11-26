# BossmanConnect

En Flutter-app för Android med en enda knapp: **LINK SPEAKERS** (appens UI är på engelska). Den sätter två
Bose SoundLink Revolve+ i Party- eller Stereo-läge.

Appen skickar exakt samma byte som Bose Connect, men utan allt runtomkring, och försöker
automatiskt igen (3 gånger) om anslutningen fallerar.

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

## 3. Bose-protokollet (inbyggt sedan build 3)

Avläst ur Bose Connects trafik 2026-11-26. RFCOMM via SPP (Ninja har två SPP-poster; Bose Connect
använde kanal 8). Ramar: `[block] [funktion] [operation] [längd] [payload]`, operation
01 GET, 05 START, 03 STATUS, 07 RESULT, 06 PROCESSING, 04 ERROR.

| Vad | Skickas | Svar |
|---|---|---|
| Firmware (verifierar kanalen) | `00 01 01 00` | `00 01 03 05 "1.0.4"` |
| Telefonens adress | `05 01 01 00` | `05 01 03 09 00 02 01 <telefon>` |
| Parade enheter | `04 04 01 00` | `04 04 03 <n> <x> <adr>…` |
| Namn på enhet | `04 05 01 06 <adr>` | `04 05 03 <n> <adr> <3 byte> <namn>` |
| **Party link** | `04 01 05 0D 90 <Lantern> <telefon>` | `04 01 07 06 <Lantern>` |
| Unlink | `04 02 05 06 <Lantern>` | `04 02 07 …` |

LINK SPEAKERS gör sekvensen ovan själv. Fältet **Advanced** behövs bara för att prova andra kommandon
(t.ex. Stereo-läge, som inte är fångat än). Fångas så här:

### Fånga fler kommandon

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

> Samsung: snoop-loggen kommer bara med i bugreporten med `*#9900#` → Debug Level **MID**
> (telefonen startar om). Sätt tillbaka LOW efteråt. `/sdcard/log` innehåller den inte.

Egna kommandon i **Advanced** skickas rått, en rad per kommando (`01 02 03 04  # kommentar`),
i stället för den inbyggda länkningen. Töm fältet för att gå tillbaka.

## Tips

- **Tvångsstoppa Bose Connect** innan du använder appen. Den kan hålla kontrollkanalen upptagen.
- Appen letar upp den vita högtalaren via namnet **Lantern** i Ninjas lista över parade enheter.
- Loggen i appen kan kopieras (håll inne på den) om något strular.
