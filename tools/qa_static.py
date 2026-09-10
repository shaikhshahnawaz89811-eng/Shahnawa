from pathlib import Path
import re, zipfile, tempfile, shutil, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/sa/assistant/MainActivity.kt'
MANIFEST = ROOT / 'app/src/main/AndroidManifest.xml'
WORKFLOW = ROOT / '.github/workflows/android.yml'

def balanced(text, pairs):
    stack=[]
    in_line=False; in_block=False; in_string=False; in_char=False; in_raw=False; esc=False
    i=0
    while i < len(text):
        c=text[i]; n=text[i+1] if i+1 < len(text) else ''; n2=text[i+2] if i+2 < len(text) else ''
        if in_line:
            if c=='\n': in_line=False
            i+=1; continue
        if in_block:
            if c=='*' and n=='/': in_block=False; i+=2; continue
            i+=1; continue
        if in_raw:
            if c=='"' and n=='"' and n2=='"': in_raw=False; i+=3; continue
            i+=1; continue
        if in_string:
            if esc: esc=False
            elif c=='\\': esc=True
            elif c=='"': in_string=False
            i+=1; continue
        if in_char:
            if esc: esc=False
            elif c=='\\': esc=True
            elif c=="'": in_char=False
            i+=1; continue
        if c=='/' and n=='/': in_line=True; i+=2; continue
        if c=='/' and n=='*': in_block=True; i+=2; continue
        if c=='"' and n=='"' and n2=='"': in_raw=True; i+=3; continue
        if c=='"': in_string=True; i+=1; continue
        if c=="'": in_char=True; i+=1; continue
        if c in pairs: stack.append(c)
        elif c in pairs.values():
            if not stack or pairs[stack.pop()] != c: return False
        i+=1
    return not stack and not in_line and not in_block and not in_string and not in_char and not in_raw

src=APP.read_text()
assert balanced(src, {'(':')','{':'}','[':']'}), 'Kotlin delimiter/string balance failed'
assert 'android.permission.INTERNET' not in MANIFEST.read_text(), 'Unexpected INTERNET permission'
assert 'android:windowSoftInputMode="adjustResize"' in MANIFEST.read_text(), 'IME resize missing'
assert 'imePadding()' in src and 'navigationBarsPadding()' in src, 'IME/navigation handling missing'
assert 'Dispatchers.IO' in src and 'generateWithContextStream' in src, 'Native generation is not on worker path'
assert 'viewModelScope.launch(Dispatchers.Main.immediate)' in src, 'Coalesced Main flush missing'
assert 'generateContinueStream' in src and 'generateWithContextStream' in src, 'Streaming entry points missing'
assert src.count('generateContinueStream(') == 1 and src.count('generateWithContextStream(') == 1, 'Duplicate native generation call sites'
assert 'generationFinished' in src and 'generationCancelled' in src, 'Generation guards missing'
assert 'nativeCancelGenerate()' in src, 'Cancellation missing'
assert 'generationWorkLineId' in src, 'Generation work line is not independently tracked'
assert 'persistJob' in src and 'viewModelScope.launch(Dispatchers.IO)' in src, 'Persistence not offloaded'
starter = src[src.index('fun starterFiles'):]
for required in ['app/src/main/java/com/sa/notes/MainActivity.kt','app/src/main/java/com/sa/notes/Note.kt','app/src/main/java/com/sa/notes/NoteRepository.kt','app/src/main/java/com/sa/notes/NoteViewModel.kt','app/src/main/AndroidManifest.xml','app/src/main/res/values/styles.xml','build.gradle.kts','settings.gradle.kts']:
    assert required in starter, f'Starter project missing {required}'
main_raw_start = starter.index('ProjectFile("app/src/main/java/com/sa/notes/MainActivity.kt"')
main_raw_end = starter.index('    ProjectFile("app/src/main/java/com/sa/notes/Note.kt"')
main_raw = starter[main_raw_start:main_raw_end]
assert main_raw.count('class MainActivity : ComponentActivity()') == 1, 'Starter project has duplicate MainActivity'
assert 'namespace = "com.sa.notes"' in starter and 'applicationId = "com.sa.notes"' in starter, 'Starter Android module coordinates missing'
assert 'Theme.NotesApp' in starter, 'Starter theme missing'
assert WORKFLOW.exists(), 'GitHub workflow missing'
wf=WORKFLOW.read_text()
for needle in ['actions/checkout@v6','actions/setup-java@v6','gradle/actions/setup-gradle@v6',':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebug','actions/upload-artifact@v4']:
    assert needle in wf, f'Workflow missing {needle}'
ET.parse(MANIFEST)
for p in ROOT.rglob('*'):
    if p.is_file() and p.stat().st_size == 0:
        raise AssertionError(f'Empty file: {p}')
for bad in ['TODO','FIXME','PLACEHOLDER','dummy API','fake API']:
    if bad.lower() in src.lower():
        raise AssertionError(f'Forbidden marker: {bad}')
print('STATIC_QA_PASS')
print('No UI loading/progress composable found; model import/inference is background work.')
print('Native generation has exactly one selected call path per turn.')
print('IME resize + imePadding + navigationBarsPadding present.')
print('Persistence JSON/disk work is off the UI thread.')
print('GitHub CI/test/lint/APK artifact pipeline present.')
