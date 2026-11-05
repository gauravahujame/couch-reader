"""Serve the production EPUB shell and sanitized fixtures for desktop engine verification.
Only the virtual origin is rewritten; Android WebView integration remains a device check.
Run after compiling the app. Binds loopback only, with no uploaded content or credentials.
"""
from pathlib import Path
import glob
import mimetypes
import shutil
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "app/build/browser-fixtures"
if FIXTURES.exists():
    shutil.rmtree(FIXTURES)
FIXTURES.mkdir(parents=True)
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
classpath = ":".join([
    str(ROOT / "app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar"),
    glob.glob(str(cache / "org.jsoup/jsoup/1.18.3/*/*.jar"))[0],
    glob.glob(str(cache / "org.jetbrains.kotlin/kotlin-stdlib/2.1.20/*/*.jar"))[0],
])
subprocess.run(["javac", "-cp", classpath, "-d", str(FIXTURES), "tests/PrepareFixtures.java"], cwd=ROOT, check=True)
subprocess.run(["java", "-cp", str(FIXTURES) + ":" + classpath, "PrepareFixtures"], cwd=ROOT, check=True)

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?")[0]
        base = ROOT / "app/src/main/assets/reader" if path.startswith("/reader/") else FIXTURES
        relative = path.removeprefix("/reader/") if path.startswith("/reader/") else path.removeprefix("/book/")
        file = (base / relative).resolve()
        if not file.is_relative_to(base.resolve()) or not file.is_file():
            self.send_error(404)
            return
        data = file.read_bytes()
        mime = mimetypes.guess_type(file)[0] or "application/xml"
        if file.suffix in (".js", ".html", ".xhtml", ".css"):
            data = data.replace(b"https://reader.invalid", b"http://127.0.0.1:8765")
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *args):
        pass

print("Engine fixture server: http://127.0.0.1:8765/reader/index.html", flush=True)
ThreadingHTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
