# Offline Chat v2: WhatsApp-style, 100% offline

Nearby Android phones chat directly over Bluetooth and Wi-Fi Direct.
There is no internet, no SIM, no server and no account.

## Features
| Feature | How it works |
|---|---|
| **Rooms with a secret code** | The code is your "frequency". Phones with different codes never connect, and the code also makes the room's encryption key. |
| **Group chat** | Text, 📷 photos and 🎤 voice notes (up to 2 minutes). |
| **Private 1-to-1 chat** | Open **Members** and tap a person. Messages are end-to-end encrypted with each person's own key (ECDH P-256 + AES-256-GCM), so phones that relay them can't read them. |
| **Mesh relay** | Messages hop phone → phone (up to 6 hops), so people out of direct range still get them. |
| **Store-and-forward** | When two phones meet, they swap the messages they're missing. A message you send with nobody around shows 🕓 and goes out automatically when someone comes in range. |
| **Ticks** | 🕓 waiting · ✓ sent to a nearby phone · ✓✓ delivered (in groups, the number shows how many people got it) |
| **Members & online status** | Phones say hello every 45 s. A green dot means seen in the last 2 minutes. |
| **Saved history** | Everything is stored only on your phone (SQLite). |
| **Background mode** | Keeps receiving, relaying and notifying with the screen off. Turn it off from the notification or the app. |
| **Verified IDs** | Each person's ID comes from their key, so nobody can pretend to be someone else's ID. Compare IDs face-to-face. |

## How to run it
1. Install **Android Studio**, then choose **File → Open** and select the `OfflineChat` folder.
2. Let Gradle sync. This needs internet once, only to download libraries.
3. Turn on USB debugging on your phone and press ▶ Run. Install the app on **2 or more phones**.
4. On each phone, turn on **Bluetooth + Wi-Fi + Location**. Wi-Fi doesn't need to be connected to anything.
5. Enter your name, then enter the same room code on every phone, then tap **Join**.

To share the app without the Play Store, use **Build → Build APK(s)** and send the APK by Bluetooth, Nearby Share or USB.

## Code map
| File | Role |
|---|---|
| `Crypto.kt` | Room key (PBKDF2), personal identity key pair, ECDH private-chat keys |
| `MeshEngine.kt` | Discovery, connections, packets (hello/msg/ack/inv/want), relay, chunking, sync |
| `MeshService.kt` | Foreground service + notifications |
| `Db.kt` | SQLite: rooms, messages, members, stored packets |
| `Repo.kt` | Shared app state |
| `Media.kt` | Photo compression, voice recording and playback |
| `MainActivity.kt` | All screens (Jetpack Compose) |

## Limits
- About 30–100 m per hop. More phones in the room means more coverage.
- One room is active at a time. History for every room is kept.
- Media is capped at about 900 KB per item to keep the radio fast.
- Anyone with the room code can read the **group** chat. **Private** chats stay private.
- Some phone brands (Xiaomi, Oppo, Vivo, Realme) kill background apps. Allow "Autostart" and set battery to "No restrictions" for this app.
