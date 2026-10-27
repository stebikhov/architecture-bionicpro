import http.server
import socketserver
import os
import sys

DATA_DIR = "/data"
PORT = 9000

os.makedirs(DATA_DIR, exist_ok=True)

class S3Handler(http.server.BaseHTTPRequestHandler):

    def _get_file_path(self):
        # path is like /bionicpro-reports/reports/user/report_2026-10-01_2026-10-16.json
        clean_path = self.path.split('?')[0].strip('/')
        return os.path.join(DATA_DIR, clean_path)

    def do_HEAD(self):
        file_path = self._get_file_path()
        if os.path.isfile(file_path):
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(os.path.getsize(file_path)))
            self.send_header('ETag', f'"{os.path.getmtime(file_path)}"')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        file_path = self._get_file_path()
        if os.path.isfile(file_path):
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(os.path.getsize(file_path)))
            self.send_header('ETag', f'"{os.path.getmtime(file_path)}"')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            with open(file_path, 'rb') as f:
                self.wfile.write(f.read())
        else:
            self.send_response(404)
            self.send_header('Content-Type', 'application/xml')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(b"<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>")

    def do_PUT(self):
        file_path = self._get_file_path()
        os.makedirs(os.path.dirname(file_path), exist_ok=True)
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length)

        with open(file_path, 'wb') as f:
            f.write(body)

        self.send_response(200)
        self.send_header('ETag', f'"{os.path.getmtime(file_path)}"')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, HEAD, PUT, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.end_headers()

    def log_message(self, format, *args):
        sys.stdout.write("%s - - [%s] %s\n" %
                         (self.address_string(),
                          self.log_date_time_string(),
                          format % args))
        sys.stdout.flush()

if __name__ == '__main__':
    with socketserver.ThreadingTCPServer(('0.0.0.0', PORT), S3Handler) as httpd:
        print(f"S3 Object Storage service listening on port {PORT}...", flush=True)
        httpd.serve_forever()
