# Security Notes

- No `android.permission.INTERNET` is declared.
- No API key is required for local inference.
- Model files are copied into app-private storage.
- File export uses Android's system `CreateDocument` picker.
- ZIP output is generated from the current in-memory workspace snapshot.
- Native generation is cancelled when the ViewModel is cleared.
- No arbitrary shell command execution exists in the current APK.
- No browser automation or remote-control endpoint exists in the current APK.
- GitHub Actions uses read-only repository contents permission for the build job.

A future terminal/build executor must be sandboxed and must not accept raw model-generated shell commands without policy checks.
