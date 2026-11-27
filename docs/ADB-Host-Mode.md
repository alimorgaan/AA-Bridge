# USB-debugging (ADB) mode

Most cars that use a USB cable make the phone act as a "USB accessory". Some head units don't. They connect
through **USB debugging (ADB)** instead: the car signs in to the phone (you see the "Allow USB debugging?"
prompt, showing the car's key) and then asks the phone to open a few network ports. The phone passes those
connections to the app on the phone itself, so they always come from `127.0.0.1`.

Seen on a Maxus D90 factory head unit, using its "Android USB" option. The official CarbitLink app (6.6.0) works
the same way: it listens on these ports while it is open.

## What AA Bridge does

With **Settings → Advanced → USB-debugging cars** on (the default), opening the app while USB debugging is on
makes it listen on the ports below and accept connections from the phone itself only. The video part is the same
code the cable and Wi-Fi Direct connections use.

If a cable or Wi-Fi Direct connection is running, that one wins. The USB-debugging mode stops 30 seconds after
the cable is unplugged.

| Port | What it is for |
|---|---|
| `5903` | Short control commands (see below). The car opens a new connection for most commands, plus one long connection for the "are you still there?" check. |
| `10922` | The control channel that Wi-Fi Direct uses. This car hasn't been seen to use it. |
| `10921` | Video control. The car's screen size and settings arrive here first. |
| `10920` | The video itself. |

## Commands on port 5903

A command is a 4-byte number. Answers are either a 4-byte number, or a text string sent as a 4-byte length
followed by the text.

| Command | What the car wants | Our answer |
|---|---|---|
| `0x8000` | "Are you still there?", every 2 seconds. **If we don't answer, the car shows "EasyConnection APP not detected" after about 16 seconds and drops the screen.** | `1` if the phone screen is on (`-1` if off), then `0` |
| `0x0009` | The app version | `574`, the version the official app reports |
| `0x8001` | The address of a web server | empty text |
| `0x803D` | An update check. The car sends details about itself first. | `noupdate` |
| `0x8031` | The car says which app it is using, then sends four numbers | `0` |
| `0x0008` | To fetch a web page for the car | `REQUEST ERROR!`. We don't fetch anything. |

Any other command is logged and ignored. The official app handles about fifty more (screen on, home key, Wi-Fi
hotspot, file transfer and so on). This car hasn't sent any of them.

## Limits

- **The car only connects if an app with CarbitLink's package name (`net.easyconn.carman.wws`) is installed.**
  With AA Bridge's own package name the car never connects and keeps showing its "install CarbitLink" QR code.
  AA Bridge doesn't use that package name, and it can't be installed next to the official app. A personal build
  with the package name changed worked on the car above.
- The car's web requests are not passed on to any server.
- Bluetooth is not connected automatically. Connect it by hand.
- While this mode runs, any app on the phone can connect to those ports. The official app has the same property.
