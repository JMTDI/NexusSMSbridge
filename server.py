#!/usr/bin/env python3
"""
NexusBridge WebSocket + HTTP Server
Bridges Android SMS app with web client via WebSocket sessions.
"""

import os
import asyncio
import json
import logging
import secrets
import string
import time
from collections import defaultdict
from typing import Dict, List, Optional

from aiohttp import web, WSMsgType
from aiohttp.web_middlewares import middleware

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("nexusbridge")

BASE_URL = "https://your.domain.com"
ALLOWED_ORIGINS = {BASE_URL, "http://localhost:8000", "http://127.0.0.1:8000", "null"}
PING_INTERVAL = 30  # seconds

# ── Security limits ───────────────────────────────────────────────────────────
SESSION_TTL          = 86_400   # sessions expire after 24 h
MAX_SESSIONS         = 200      # hard cap on concurrent sessions
NEW_SESSION_RATE     = 10       # max new sessions per IP per window
NEW_SESSION_WINDOW   = 60       # seconds for rate window
PIN_MAX_ATTEMPTS     = 10       # failed PIN attempts before lockout
PIN_ATTEMPT_WINDOW   = 300      # 5-minute window for attempt count
PIN_LOCKOUT_SECS     = 900      # 15-minute lockout

# Per-IP tracking (in-memory; good enough for a single-process server)
_new_session_times: Dict[str, List[float]] = defaultdict(list)
_pin_fail_times:    Dict[str, List[float]] = defaultdict(list)
_pin_lockout_until: Dict[str, float]       = {}


class Session:
    def __init__(self, token: str, pin: str):
        self.token = token
        self.pin = pin
        self.created_at = time.time()
        self.phone_ws: Optional[web.WebSocketResponse] = None
        self.client_ws: Optional[web.WebSocketResponse] = None
        self.cached_sms_list: Optional[str] = None  # last sms_list from phone

    @property
    def phone_connected(self) -> bool:
        return self.phone_ws is not None and not self.phone_ws.closed

    @property
    def client_connected(self) -> bool:
        return self.client_ws is not None and not self.client_ws.closed


# Global state
sessions: Dict[str, Session] = {}
pin_to_token: Dict[str, str] = {}  # maps PIN → session token
pairing_token_to_session: Dict[str, str] = {}  # maps pairing token → session token


def generate_pin() -> str:
    """Generate a 6-digit PIN using a cryptographically secure source."""
    while True:
        pin = "".join(secrets.choice(string.digits) for _ in range(6))
        if pin not in pin_to_token:
            return pin


def _is_rate_limited(ip: str) -> bool:
    """Return True if this IP has exceeded the /new-session rate limit."""
    now = time.monotonic()
    times = _new_session_times[ip]
    # Evict old entries
    cutoff = now - NEW_SESSION_WINDOW
    _new_session_times[ip] = [t for t in times if t > cutoff]
    if len(_new_session_times[ip]) >= NEW_SESSION_RATE:
        return True
    _new_session_times[ip].append(now)
    return False


def _is_pin_locked(ip: str) -> bool:
    """Return True if this IP is locked out of PIN attempts."""
    now = time.monotonic()
    until = _pin_lockout_until.get(ip, 0)
    if now < until:
        return True
    # Purge old failures
    cutoff = now - PIN_ATTEMPT_WINDOW
    _pin_fail_times[ip] = [t for t in _pin_fail_times[ip] if t > cutoff]
    return False


def _record_pin_failure(ip: str) -> None:
    """Record a failed PIN attempt; impose lockout if threshold exceeded."""
    now = time.monotonic()
    _pin_fail_times[ip].append(now)
    if len(_pin_fail_times[ip]) >= PIN_MAX_ATTEMPTS:
        _pin_lockout_until[ip] = now + PIN_LOCKOUT_SECS
        log.warning(f"[AUTH] PIN lockout applied to {ip} for {PIN_LOCKOUT_SECS}s")


async def _cleanup_loop():
    """Periodically expire stale sessions to prevent unbounded memory growth."""
    while True:
        await asyncio.sleep(3_600)  # run hourly
        now = time.time()
        expired = [
            token for token, sess in list(sessions.items())
            if now - sess.created_at > SESSION_TTL
        ]
        for token in expired:
            sess = sessions.pop(token, None)
            if sess is None:
                continue
            pin_to_token.pop(sess.pin, None)
            pairing_token_to_session.pop(token, None)
            for ws in (sess.phone_ws, sess.client_ws):
                if ws and not ws.closed:
                    try:
                        await ws.close()
                    except Exception:
                        pass
        if expired:
            log.info(f"[CLEANUP] Expired {len(expired)} session(s)")


def create_session() -> Session:
    token = secrets.token_urlsafe(24)
    pin = generate_pin()

    session = Session(token=token, pin=pin)
    sessions[token] = session
    pin_to_token[pin] = token
    pairing_token_to_session[token] = token  # pairing token == session token for simplicity

    log.info(f"[SESSION] Created session token={token[:8]}... pin={pin}")
    return session


def get_qr_data(session: Session) -> str:
    return f"nexusbridge://pair?token={session.token}&server={BASE_URL}"


# ─── HTTP handlers ────────────────────────────────────────────────────────────

_INDEX_PATH = os.path.join(os.path.dirname(__file__), "index.html")


async def handle_index(request: web.Request) -> web.Response:
    try:
        with open(_INDEX_PATH, "r", encoding="utf-8") as f:
            html = f.read()
        return web.Response(text=html, content_type="text/html")
    except FileNotFoundError:
        return web.Response(text="index.html not found", status=404)

async def handle_new_session(request: web.Request) -> web.Response:
    ip = request.remote or "unknown"
    if _is_rate_limited(ip):
        log.warning(f"[HTTP] /new-session rate-limited for {ip}")
        return web.json_response({"error": "Too many requests"}, status=429)
    if len(sessions) >= MAX_SESSIONS:
        log.warning("[HTTP] /new-session rejected — session cap reached")
        return web.json_response({"error": "Server capacity reached"}, status=503)
    session = create_session()
    body = {
        "sessionToken": session.token,
        "pin": session.pin,
        "qrData": get_qr_data(session),
    }
    log.info(f"[HTTP] GET /new-session → token={session.token[:8]}... ip={ip}")
    return web.json_response(body)


async def handle_session_status(request: web.Request) -> web.Response:
    token = request.match_info["token"]
    session = sessions.get(token)
    if session is None:
        return web.json_response({"error": "Session not found"}, status=404)
    # PIN is intentionally omitted — callers already have it from /new-session
    return web.json_response({
        "sessionToken": token,
        "phoneConnected": session.phone_connected,
        "clientConnected": session.client_connected,
    })


async def handle_health(request: web.Request) -> web.Response:
    return web.json_response({"status": "ok", "sessions": len(sessions)})


# ─── CORS middleware ───────────────────────────────────────────────────────────

@middleware
async def security_middleware(request: web.Request, handler):
    origin = request.headers.get("Origin", "")

    if request.method == "OPTIONS":
        response = web.Response()
    else:
        try:
            response = await handler(request)
        except web.HTTPException as ex:
            response = ex

    # CORS — grant access to known origins; also allow requests with no Origin
    # (same-origin requests from the page the server itself serves never include
    # an Origin header, so we must return the header for them too).
    if origin in ALLOWED_ORIGINS or not origin:
        allow = origin if origin else BASE_URL
        response.headers["Access-Control-Allow-Origin"] = allow
        response.headers["Vary"] = "Origin"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization"

    # Security headers
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"]        = "DENY"
    response.headers["Referrer-Policy"]        = "no-referrer"
    return response


# ─── WebSocket handler ────────────────────────────────────────────────────────

async def relay_message(dest_ws: web.WebSocketResponse, label: str, raw: str):
    """Forward a raw message string to destination."""
    if dest_ws is not None and not dest_ws.closed:
        try:
            await dest_ws.send_str(raw)
            log.info(f"[RELAY] {label}")
        except Exception:
            log.warning(f"[RELAY] Destination closed during relay: {label}")
    else:
        log.warning(f"[RELAY] Destination unavailable, dropped: {label}")


async def ping_loop(ws: web.WebSocketResponse, label: str):
    """Send periodic pings to keep the connection alive."""
    try:
        while not ws.closed:
            await asyncio.sleep(PING_INTERVAL)
            if ws.closed:
                break
            ping_msg = json.dumps({"type": "ping", "payload": {}})
            await ws.send_str(ping_msg)
            log.debug(f"[PING] Sent to {label}")
    except asyncio.CancelledError:
        pass
    except Exception:
        pass


async def ws_handler(request: web.Request) -> web.WebSocketResponse:
    """
    WebSocket handler mounted at /ws/{token}.
    Query param: role=phone|client
    """
    identifier = request.match_info["token"]
    role = request.rel_url.query.get("role", "client")

    ws = web.WebSocketResponse(heartbeat=None, max_msg_size=10 * 1024 * 1024)
    await ws.prepare(request)

    # Resolve session
    session: Optional[Session] = None

    if identifier.isdigit() and len(identifier) == 6:
        ip = request.remote or "unknown"
        if _is_pin_locked(ip):
            log.warning(f"[AUTH] PIN attempt from locked-out IP {ip}")
            await ws.close(code=1008, message=b"Too many failed attempts")
            return ws
        token = pin_to_token.get(identifier)
        if token:
            session = sessions.get(token)
        if session is None:
            _record_pin_failure(ip)
            log.warning(f"[WS] Invalid PIN attempt from {ip}")
            await ws.close(code=1008, message=b"Invalid PIN")
            return ws
        # Successful PIN auth — clear failure history for this IP
        _pin_fail_times.pop(ip, None)
        log.info(f"[WS] Phone authenticated via PIN → session {session.token[:8]}...")
    else:
        session = sessions.get(identifier)
        if session is None:
            log.warning(f"[WS] Unknown session token: {identifier[:8]}...")
            await ws.close(code=1008, message=b"Session not found")
            return ws

    remote = request.remote
    log.info(f"[WS] {role.upper()} connected from {remote} → session {session.token[:8]}... pin={session.pin}")

    # Register the connection
    if role == "phone":
        if session.phone_ws and not session.phone_ws.closed:
            log.info(f"[WS] Replacing existing phone connection for session {session.token[:8]}...")
            await session.phone_ws.close()
        session.phone_ws = ws
        if session.client_connected:
            notify = json.dumps({"type": "phone_connected", "payload": {"pin": session.pin}})
            await relay_message(session.client_ws, "phone_connected → client", notify)
    else:
        if session.client_ws and not session.client_ws.closed:
            log.info(f"[WS] Replacing existing client connection for session {session.token[:8]}...")
            await session.client_ws.close()
        session.client_ws = ws
        status_msg = json.dumps({
            "type": "connection_status",
            "payload": {"phoneConnected": session.phone_connected, "pin": session.pin}
        })
        await ws.send_str(status_msg)
        # Replay cached sms_list so client gets conversations immediately
        if session.cached_sms_list:
            log.info(f"[WS] Replaying cached sms_list to new client for session {session.token[:8]}...")
            await ws.send_str(session.cached_sms_list)

    ping_task = asyncio.ensure_future(ping_loop(ws, f"{role}@{session.token[:8]}"))

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                raw_message = msg.data
                try:
                    parsed = json.loads(raw_message)
                    msg_type = parsed.get("type", "unknown")
                except (json.JSONDecodeError, AttributeError):
                    msg_type = "raw"

                if msg_type == "pong":
                    log.debug(f"[PONG] Received from {role}@{session.token[:8]}...")
                    continue
                if msg_type == "ping":
                    await ws.send_str(json.dumps({"type": "pong", "payload": {}}))
                    continue

                if role == "phone":
                    log.info(f"[MSG] phone→client type={msg_type} session={session.token[:8]}...")
                    # Cache sms_list so clients that connect later get it immediately
                    if msg_type == "sms_list":
                        session.cached_sms_list = raw_message
                        log.info(f"[CACHE] sms_list cached for session {session.token[:8]}...")
                    await relay_message(session.client_ws, f"phone→client [{msg_type}]", raw_message)
                else:
                    log.info(f"[MSG] client→phone type={msg_type} session={session.token[:8]}...")
                    await relay_message(session.phone_ws, f"client→phone [{msg_type}]", raw_message)

            elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                break
    finally:
        ping_task.cancel()

        if role == "phone":
            if session.phone_ws is ws:
                session.phone_ws = None
            if session.client_connected:
                disc_msg = json.dumps({"type": "phone_disconnected", "payload": {}})
                try:
                    await session.client_ws.send_str(disc_msg)
                except Exception:
                    pass
        else:
            if session.client_ws is ws:
                session.client_ws = None

        log.info(f"[WS] Cleaned up {role} for session {session.token[:8]}...")

    return ws


# ─── Server startup ────────────────────────────────────────────────────────────

async def main():
    log.info("=" * 60)
    log.info("  NexusBridge Server starting...")
    log.info(f"  Base URL: {BASE_URL}")
    log.info("=" * 60)

    asyncio.ensure_future(_cleanup_loop())

    app = web.Application(middlewares=[security_middleware])
    app.router.add_get("/", handle_index)
    app.router.add_get("/new-session", handle_new_session)
    app.router.add_get("/session-status/{token}", handle_session_status)
    app.router.add_get("/health", handle_health)
    app.router.add_get("/ws/{token}", ws_handler)

    runner = web.AppRunner(app)
    await runner.setup()
    port = int(os.environ.get("PORT", 8000))
    site = web.TCPSite(runner, "0.0.0.0", port)
    await site.start()
    log.info("[SERVER] Listening on http://0.0.0.0:%d  (HTTP + WebSocket)", port)

    try:
        await asyncio.Future()  # Run forever
    except (KeyboardInterrupt, asyncio.CancelledError):
        log.info("[SHUTDOWN] Stopping NexusBridge...")
    finally:
        await runner.cleanup()
        log.info("[SHUTDOWN] Done.")


if __name__ == "__main__":
    asyncio.run(main())
