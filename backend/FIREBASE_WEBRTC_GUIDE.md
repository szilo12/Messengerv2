# Olyna Messenger: Firebase + WebRTC Signaling Guide

This document explains how to set up the **Firebase + WebRTC** integration for real-time video/voice calling inside the Android application, referencing our Node.js signaling server deployable to **Render.com**.

---

## 1. Firebase Developer Console Setup
1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Create or select a project.
3. Register your Android app with the package name:
   - Package name: `com.example` (or the dynamic Application ID configured in `build.gradle.kts`: `com.aistudio.olyna.kvytdw`).
4. Download the `google-services.json` file and place it in your `/app/` directory.

---

## 2. WebRTC Android Client Integration
To make the actual WebRTC peer connection, you can add Google's official WebRTC dependency to your `build.gradle.kts` dependencies block:

```kotlin
implementation("org.webrtc:google-webrtc:1.0.32006")
```

### Flow of Signalling (How it works):
1. **Initiate Call**: Alice creates a PeerConnection, creates an `SDP Offer`, sets it locally, and sends this offer block to Bob via the Render.com signaling server or Firebase Cloud Firestore.
2. **Alert Device**: Bob receives the offer block via Firebase Cloud Messaging (FCM) high-priority notification or our live Socket connection, which automatically launches the immersive foreground `IncomingCallActivity`.
3. **Answering Call**: When Bob taps **Answer (Fogadás)**, Bob sets Alice's SDP offer, generates an `SDP Answer`, sets it locally, and sends it back to Alice.
4. **ICE Candidate Exchange**: As both devices discover their network path candidates, they swap them to establish local peer-to-peer data and media streams direct, bypassing any middleman servers (except STUN/TURN if behind deep NAT).

---

## 3. Connecting to the Render.com Signalling Server
Below is an example snippet on how you connect your Android Client to the backend:

```kotlin
import io.socket.client.IO
import io.socket.client.Socket

class SignalingClient(private val serverUrl: String) {
    private var socket: Socket? = null

    fun connect(userId: String, onIncomingCall: (from: String, sdpOffer: String) -> Unit) {
        socket = IO.socket(serverUrl)
        socket?.connect()

        // Register online presence
        socket?.emit("register", userId)

        // Listen for incoming call events
        socket?.on("call_incoming") { args ->
            val data = args[0] as org.json.JSONObject
            val from = data.getString("from")
            val signalData = data.getString("signalData") // Alice's SDP
            onIncomingCall(from, signalData)
        }
    }

    fun initiateCall(toUser: String, myUserId: String, sdpOffer: String) {
        val payload = org.json.JSONObject().apply {
            put("to", toUser)
            put("from", myUserId)
            put("signalData", sdpOffer)
            put("callType", "VIDEO")
        }
        socket?.emit("call_request", payload)
    }
}
```

Deploying this server on Render.com is as simple as creating a new Web Service, linking your repository, and checking out the dynamic live link!
