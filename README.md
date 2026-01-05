# ODV Server Controller
This server manager is a native android application built with Kotlin and Jetpack Compose for remote Linux server administration. It allows users to monitor system health, view logs, and execute terminal commands via SSH.

This application uses a Trust On First Use (TOFU) security model, similar to standard SSH clients (like PuTTY or OpenSSH).
1.  **First Connection:** The app trusts the server and securely saves its unique Host Key fingerprint to the device's internal storage.
2.  **Subsequent Connections:** The app verifies the server's identity against the stored fingerprint.
3.  **Security Alert:** If the server's key changes, the connection is blocked.
4.  **Reset:** You can manually clear the known hosts in the *Server Control* screen if you have reinstalled your server.

**Encryption:** All connections are secured using the BouncyCastle cryptographic provider.

## Tech Stack

* **Language:** Kotlin
* **UI Framework:** Jetpack Compose (Material Design 3)
* **SSH Library:** [SSHJ](https://github.com/hierynomus/sshj)
* **Async/Concurrency:** Kotlin Coroutines
* **Min SDK:** API 26 (Android 8.0)
