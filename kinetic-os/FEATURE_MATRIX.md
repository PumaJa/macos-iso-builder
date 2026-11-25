# Funktions-/Vergleichsmatrix – Kinetic OS vs. originale Stark-VARG-Software

Status-Legende: **IT** = Implementiert und getestet (automatisiert) · **IH** = Implementiert, Hardwaretest ausstehend ·
**TI** = Teilweise implementiert · **HW** = Zusätzliche Hardware erforderlich · **EXT** = Autorisierte externe Schnittstelle
erforderlich · **NS** = Nicht unterstützt.
„Stark“-Spalte: laut öffentlich zugänglicher Herstellerangaben/Presse (siehe RESEARCH.md), nicht selbst verifiziert.

## VESC-Kommunikation
| Funktion | Stark | Kinetic OS | Status | Nachweis |
|---|---|---|---|---|
| Paketkodierung/-dekodierung, CRC16, Fragmentierung, Resync | – | `PacketCodec`, `PacketDecoder` | IT | CodecTest (Zufalls-Fragmentierung, Korruption, Garbage) |
| Timeouts, Wiederholungen, Fehlerbehandlung | – | `VescClient` | IT | ClientTest (Drop/CRC-Injektion) |
| Bluetooth LE (Nordic UART, VESC Express/NRF) | – | `BleVescTransport`, MTU-Aushandlung | IH | UUIDs gegen vesc_express-Quelle geprüft |
| USB-OTG (CDC-ACM, STM32-VCP) | – | `UsbVescTransport` (ohne Fremdbibliothek) | IH | – |
| UART über USB-UART-Adapter | – | nur CDC-ACM-Adapter; CP210x/CH34x/FTDI nicht | TI | – |
| WiFi/TCP (VESC Express) | – | `TcpVescTransport` | IH | – |
| Mehrere VESC über CAN (Erkennung, Forwarding) | – | `PING_CAN`, `FORWARD_CAN`, FW je ID | IT (Sim) / IH | ClientTest.canForwardingAndPing |
| Automatische Wiederverbindung | – | Backoff 1–15 s | IT (Sim) / IH | – |
| Firmware-/Hardware-Erkennung | – | `COMM_FW_VERSION` inkl. optionaler Felder | IT | ParsersTest |
| Erkennung unterstützter Funktionen | – | Probing IMU/BMS/Stats/GNSS/MCCONF_TEMP | IT (Sim) / IH | – |
| VESC 6.x / 7.x Kompatibilität | – | Layouts aus bldc master (7.01-beta); ältere FW: fehlende Felder = null | IT / IH | „6.7“ existiert nicht; aktuell 7.00 (RESEARCH.md) |

## Fahrmodi & Motorcharakteristik
| Funktion | Stark | Kinetic OS | Status |
|---|---|---|---|
| Eco/Sport/Race + eigene Modi | ja (Power Levels, Custom) | 4 eingebaute + bis 200 eigene Profile | IT (Logik) / IH |
| Leistung kW/PS/hp | ja | Anzeige + Leistungsgrenze (`l_watt_max`) | IT / IH |
| Drehmoment-/Motorstrombegrenzung | ja | `l_current_max_scale` | IT / IH |
| Batteriestrombegrenzung | – | `l_in_current_max` | IT / IH |
| Rekuperation / Motorbremse (Stärke) | ja | Regen-Anteil (`l_watt_min`, `l_in_current_min`) | IT / IH |
| Motorbremse beim Gaswegnehmen (Engine braking) | ja | benötigt LispBM-Gasgriffsteuerung | TI |
| LispBM-Fahrmodusverwaltung + lokales Derating | – | `lispbm/kinetic.lisp`, Protokoll v2 | IT (141 Assertions im echten LispBM-Interpreter) / IH |
| Gaskurve / progressive Leistungsentfaltung | ja (10-Punkt-Kurve) | Kurvenmodell + Vorschau; Ausführung am Fahrzeug nur per LispBM-Gasgriffsteuerung | TI |
| Angenäherte Charaktere 125/250 2T, 250/450 4T | – | als „≈ Näherung“ gekennzeichnet | TI |
| Virtuelle Schwungmasse | ja | gesperrt bis Hardwarevalidierung | HW |
| Traktionskontrolle | ja (OTA 06/2026) | gesperrt – benötigt Raddrehzahlsensor | HW |
| Wheelie-Kontrolle | – | gesperrt – benötigt validierte IMU | HW |
| Geschwindigkeits-/Drehzahlbegrenzung | – | `l_max_erpm` aus km/h | IT / IH |
| Kriechgang vor/zurück | – | Profil CRAWL (6/3 km/h-Limit); Rückwärts-Ansteuerung nicht | TI |
| Temperatur-/Akku-Derating | – | App (Anzeige+Reduktion) und LispBM (lokal) | IT (App) / IH |
| Moduswechsel nur sicher (Stillstand bei Leistungserhöhung) | – | SafetyGate | IT |

## Telemetrie
| Funktion | Kinetic OS | Status |
|---|---|---|
| Geschwindigkeit, Ø, max., Motor-RPM, ERPM, Duty | VESC + GPS-Fallback | IT / IH |
| Motor-/Batterieleistung, Ströme, Spannung | Batterie = berechnet; Motorleistung = geschätzt | IT / IH |
| Ah/Wh verbraucht, rekuperiert | gemessen (Zähler der Fahrt) | IT / IH |
| MOSFET-/Motortemperatur, Fehlercodes (35 Codes) | gemessen | IT / IH |
| Gasgriffstellung | `COMM_GET_DECODED_ADC` (ADC-App); bei anderer Eingabe „nicht verfügbar“ | IT / IH |
| GPS, Höhe, GPS-Speed | LocationManager (ohne Play-Dienste) | IH |
| Beschleunigung, G-Kräfte, Neigung | VESC-IMU wenn vorhanden, sonst berechnete Längsbeschleunigung | IH |
| Restreichweite, Wh/km | geschätzt/berechnet, gekennzeichnet | IT |
| Wirkungsgrad | bewusst nicht angezeigt (nicht zuverlässig bestimmbar) | NS |
| BMS: Zellen, min/max/Δ, Temperaturen, SoC, SoH, Status | `COMM_BMS_GET_VALUES` | IT (Parser) / HW (VESC-BMS) |
| Ladezyklen | nur Gesamt-Ah/Wh-Zähler des BMS verfügbar | TI |
| Kilometerstand (VESC-intern, stromausfallsicher) | `COMM_GET_VALUES_SETUP` odometer | IT / IH |
| Kennzeichnung gemessen/berechnet/geschätzt/simuliert | überall | IT |
| Anpassbare/adaptive Abfragerate | 1–50 Hz + Backoff | IT |

## Aufzeichnung & Analyse
| Funktion | Status |
|---|---|
| Automatische Fahrtaufzeichnung, Wiederaufnahme nach Abbruch | IT (Robolectric) |
| Synchronisierte GPS+VESC-Samples | IT |
| Diagramme (Leistung, Temperatur, Strom/Spannung, Energie, Speed, Beschleunigung, Höhe) | IH (UI) |
| Streckenkarte + Wiedergabe | IH |
| Vergleich mehrerer Fahrten / Fahrprofile | TI (Ausrichtung nach Fahrtfortschritt) |
| CSV / JSON / GPX Export | IT |
| Video-Telemetrie-Overlay | Vorschau mit Sync-Offset, **MP4-Export mit eingebranntem Overlay in der App (Media3 Transformer)**, SRT-Export | IT (Robolectric + Emulator-Instrumentierung, siehe TEST_RESULTS) |

## Gruppenfahrten
| Funktion | Stark | Kinetic OS | Status |
|---|---|---|---|
| Teilnahme an originalen Stark-Gruppenfahrten, Stark-Konto, Stark-Gruppencodes | ? | keine autorisierte Schnittstelle gefunden | EXT |
| Anzeige echter Stark-VARG-Fahrer | ? | nur wenn diese der Kinetic-Gruppe beitreten | EXT |
| VargLink-Integration | – | VargLink ist eine Dritt-App (`ru.varg.link`, nicht von Stark) ohne öffentliche API: Kinetic öffnet Einladungslinks in VargLink oder im Browser, Installationslink | IH (Link-Öffnen) / EXT (Datenaustausch) |
| Eigenes Gruppensystem: Gruppen, Codes, Einladungen (Deep Link), öffentlich/privat | – | Server + App | IT (Server) / IH |
| Live-Positionen, Fahrzeugname, Freundesliste, Präsenz | – | WebSocket | IT (Server, App-Socket) |
| Chat, Sprachnachrichten, Hilfe-Anfrage, gemeinsame Route | – | ja | IT (Server) / IH (App) |
| Freiwilliger Telemetrieaustausch | – | Opt-in-Schalter | IT |

## Telematik
| Funktion | Status |
|---|---|
| Live-Position/-Speed/Akku/Temperaturen/Motorstatus aus der Ferne (2. Handy, Browser) | IT (Server+Dashboard Integrationstest) / IH |
| Letzter bekannter Zustand mit Zeitstempel, ehrliche Offline-Anzeige | IT |
| Fahrtenhistorie, Kilometerstand (aus Fahrten), Wartungsintervalle | IH |
| Geofencing, Diebstahl-/Bewegungs-, Akku-, Fehlerbenachrichtigungen | IT – Android-Benachrichtigungen live (WebSocket) + Hintergrundabfrage alle 15 min (WorkManager), ohne Fremd-Pushdienst |
| Fernsteuerung des Motors | NS (bewusst) |
| Ortung bei ausgeschaltetem Handy/VESC | HW (GPS-Tracker nötig) |

## Navigation & Karten
| Funktion | Status |
|---|---|
| MapLibre + OpenStreetMap-Daten (OpenFreeMap, keine API-Keys) | IH |
| Offline-Karten (Region herunterladen) | IH – Nutzungsbedingungen Tile-Anbieter beachten |
| GPX-Import/-Export, Wegpunkte, Entfernungsmessung, Routenplanung (Luftlinie/Offroad) | IT (Logik) / IH |
| Routenführung entlang GPX (Abstand, Richtung, Off-Route) | IT (Logik) / IH |
| Turn-by-Turn-Straßennavigation | IT (Parser/Führung) / IH – selbst gehosteter OSRM (Compose-Profil `routing`), Abbiegehinweise, automatische Neuberechnung |
| Höhenprofil, Kartenrotation nach Fahrtrichtung, mehrere Fahrzeuge | IH |

## Racing
| Funktion | Status |
|---|---|
| Virtuelle Start/Ziel-Linie, automatische Runden, Sektoren, Bestzeit, Live-Delta | IT |
| Streckenerkennung, Ghost, Beschleunigungsmessung, Bestenliste, Genauigkeitshinweis | IT |
| Gemeinsame Rennen / Vergleich mehrerer Fahrer | TI (Server-Bestenliste) |

## Cockpit-Betrieb
| Funktion | Status |
|---|---|
| Foreground Service, Hintergrundaufzeichnung, Wake-Lock | IH |
| Start nach Neustart (Service), Auto-Verbindung | IH (Android 10+ erlaubt Activity-Start nur als HOME-App) |
| Vollbild, Kiosk (Lock Task / Device Owner), HOME-Launcher | IH |
| Lenkertasten (BT-Media-Remote, Lautstärke, D-Pad) | IH |
| Handschuh-Modus, Tag/Nacht/Auto, Energiesparmodus, Abdunkeln im Stand | IT (UI-Render) / IH |
| Samsung/ältere Geräte (minSdk 26, keine Play-Dienste) | IH |

## Aus Nutzerwünschen (Foren, siehe docs/USER_WISHES.md)
| Funktion | Status |
|---|---|
| Bild-im-Bild-Minicockpit über Navi-Apps | IH |
| Frühwarnungen (Motor-/FET-Temperatur vor Abschaltung, Akkustufen, Zelldrift, Verbindungsverlust) mit Sprachausgabe/Vibration + Verlauf | IT (Logik) / IH |
| Großer Modus-Indikator inkl. Derating-Status vom Fahrzeug | IT (Render) |
| Frei wählbare Cockpit-Kacheln, Hauptwert wählbar, Sonnenlicht-Theme | IT (Render) / IH |
| Zellchemie NMC/LFP/LTO, Coulomb-Zählung, gleitender Verbrauch für Reichweite | IT |
| VESC-Einrichtungscheck (nur lesend) + Antriebsdaten-Import | IT |
| Fahrmodi über das Fahrzeugskript (keine Konkurrenz App ↔ Skript) | IT (Simulator-E2E) / IH |
| VESC-Paket `kinetic.vescpkg` (mit VESC Tool 7.01 gebaut) | TI – gebaut und strukturell geprüft, nicht in VESC Tool geladen |
| Diebstahlsperre (Wegfahrsperre im VESC-Skript, PIN, übersteht Neustart, nur im Stand, Schiebe-/Abschlepp-Erkennung → Alarm am Zweithandy) | IT (Simulator-E2E, Server, LispBM-Interpreter) / IH |

## Aus den Stark-App-Funktionen (Funktionsumfang, eigene Gestaltung)
| Funktion | Status |
|---|---|
| Startseite mit Fahrzeugbild, Akku, Modus, Fahrzeit und Kacheln (Cockpit, Statistik, Modi, Trails, Strecken, Fahrten, Gruppe, VESC) | IT (Render) |
| Statistik gesamt/pro Fahrt: Zeit, Strecke, Airtime, Fahrten, Höchsttempo, max. Leistung, max. G-Kraft, Energie; Simulationsfahrten nur per Schalter | IT (Render) |
| Airtime-/Sprungerkennung + G-Kraft über Handy-Beschleunigungssensor | IT (Logik) / IH |
| Fahrmodi-Schnellwahl M1–M5, Leistung in PS/kW, Rekuperation pro Modus | IT (Render) / IH |
| Cockpit: Fahrzeit, Neutral-Anzeige „N“ | IT (Render, Simulator-E2E) |
| Neutral (Antrieb aus, Einschalten immer, Ausschalten nur im Stand) im Fahrzeugskript | IT (Simulator-E2E, LispBM-Interpreter) |
| Kindersicherung: PIN-geschützte Leistungs-/Tempo-Grenze im Fahrzeugskript (Senken jederzeit, Lockern nur im Stand) | IT (Simulator-E2E, LispBM-Interpreter) |
| Diebstahlsperre (Bike Lock) | siehe oben |
| Bildschirmhelligkeit in der App | IH |
| Sprachen DE/EN/FR/IT/ES/SV | IT (Lint) – FR/IT/ES/SV maschinell, Review nötig |
| Eigenes Design-System: nur Schwarz → Weiß (Graustufen), Schrift Barlow Condensed (OFL), eigenes K-Logo | IT (Render) |
| Querformat (Lenkerhalterung): 3-spaltiges Cockpit, geteilte Startseite; Ausrichtung Quer/Hoch/Auto | IT (Render quer + hoch) |
| Personalisierung: Hintergrundbild + Abdunklung, eigenes Logo, eigener Name, Akzent-Helligkeit | IT (Render) / IH (Bildauswahl) |
| Sperrbildschirm: animierter Sicherheitsring, Alarmbalken bei Bewegung, PIN-Feld direkt auf dem Bildschirm | IT (Render, Simulator-E2E) |
| Firmware-Update über WLAN | bewusst nicht (Brick-Risiko) – VESC Tool verwenden |
| Map-Switch-LED | nicht anwendbar (Stark-Hardware) |

## Bedienung & Verteilung
| Funktion | Status |
|---|---|
| Oberfläche DE/EN, zusätzlich FR/IT/ES/SV (Systemsprache) | IT (Render-Test DE) – Meldungen aus der Kernlogik noch englisch |
| Eigene Release-Signatur (keystore.properties / Umgebungsvariablen), APKs pro Architektur | IT (signierter Build geprüft) |

## Was NICHT nachgebildet wird
Originale Stark-Grafiken, Logos, Schriften, Fahrzeugdarstellung, Menütexte oder Quellcode (urheber-/markenrechtlich geschützt,
keine APK bereitgestellt). Das UI ist eine eigenständige Gestaltung nach demselben Bedienkonzept (großes Tacho, Modus-Schnellwahl,
dunkles Cockpit). Simulierte Daten werden graustufig, aber immer mit „SIM“-Banner und -Markierung angezeigt.
