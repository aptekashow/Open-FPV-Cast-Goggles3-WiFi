# Goggles 3 Wi-Fi receiver — first hardware test

This is an experimental Android port of the Goggles 3 UDP session in
[Evols/dji-goggles-bridge](https://github.com/Evols/dji-goggles-bridge). It has passed a standalone
Kotlin compile of the receiver class with Android API stubs, but it has **not** yet been built into
an APK or validated on a physical Samsung S24 Ultra + Goggles 3.

## Before testing

1. Power on Goggles 3 and the aircraft / O4 Air Unit. Confirm that a live camera picture is visible
   in the goggles.
2. In the goggles' shortcut menu, enable **Share Liveview to Mobile Device via Wi-Fi**.
3. Connect the S24 Ultra to the Wi-Fi access point broadcast by Goggles 3. If DJI Fly has already
   paired and saved the network, select that network in Android Wi-Fi settings. The receiver does
   not currently perform Bluetooth pairing or retrieve SSID/password itself.
4. Check that Android shows the phone's address as `192.168.60.1` on that Wi-Fi network.
5. Open Open FPV Cast and tap **Connect Goggles 3 via Wi-Fi**.

## Expected transport

- Phone UDP endpoint: `192.168.60.1:12346`
- Goggles endpoint: `192.168.60.2:9003`
- Session: type-0 handshake; type-1 window state; type-2 H.264 fragments; type-3 control frames;
  type-4/type-6 receive acknowledgments.
- Reassembled H.264 is passed into the existing VideoFanout, MediaCodec preview, MP4 recorder and
  WebRTC broadcaster.

## Current limitations

- Wi-Fi mode is video-only. Camera control and telemetry remain USB/AOA features.
- The app expects the phone to have already joined the goggles' Wi-Fi AP; automatic BLE pairing and
  SSID/password provisioning are not implemented.
- The local address/UDP port and packet behavior are based on the upstream reverse-engineered
  protocol and must be verified on this Android phone and current goggles firmware.
- Do not use this experimental receiver as the sole flight display. Keep the goggles as the primary
  display and test on the bench first.
