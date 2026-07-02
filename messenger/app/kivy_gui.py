from __future__ import annotations

"""Kivy GUI entry point for the encrypted messenger.

A lightweight, Telegram-inspired layout with a profile/settings popup. The
settings dialog drives connection setup (host, transport, mode) while the main
window focuses on chat history and presence.
"""

from datetime import datetime
import json
from io import BytesIO
import logging
import platform
from pathlib import Path
import sys
from threading import Thread
from typing import Dict

from kivy.config import Config
Config.set("input", "mouse", "mouse,disable_multitouch")

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.core.image import Image as CoreImage
from kivy.graphics import Color, Rectangle, RoundedRectangle
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.image import Image
from kivy.uix.scrollview import ScrollView
from kivy.uix.spinner import Spinner
from kivy.uix.textinput import TextInput
from kivy.uix.video import Video
from kivy.uix.filechooser import FileChooserListView
import qrcode

PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from messenger.app.gui_controller import ChatController  # noqa: E402

logger = logging.getLogger(__name__)

TRANSPORT_CHOICES = ["direct", "ygg", "ygg-embedded"]
MODE_CHOICES = ["connect", "listen", "rendezvous"]

HEADER_COLOR = (0.0, 0.53, 0.80, 1)
PRESENCE_ONLINE = (0.15, 0.69, 0.36, 1)
PRESENCE_OFFLINE = (0.85, 0.33, 0.31, 1)
BACKGROUND = (0.93, 0.94, 0.96, 1)
INBOUND_BUBBLE = (1, 1, 1, 1)
OUTBOUND_BUBBLE = (0.84, 0.94, 1, 1)
STATUS_CHIP = (0.86, 0.88, 0.91, 1)


def _local_app_dir() -> Path:
    """Return writable folder colocated with the app executable/script."""

    if getattr(sys, "frozen", False):
        base = Path(sys.executable).resolve().parent
    else:
        base = Path.cwd()
    base.mkdir(parents=True, exist_ok=True)
    return base


def _settings_path() -> Path:
    return _local_app_dir() / "2pchat_settings.json"


DEFAULT_SETTINGS = {
    "nickname": "You",
    "mode": MODE_CHOICES[0],
    "host": "127.0.0.1",
    "bind": "0.0.0.0",
    "port": "4444",
    "transport": TRANSPORT_CHOICES[0],
    "binary": "yggdrasil",
    "config": "",
    "peers": "",
    "verbose": "false",
}


class SettingsPopup(Popup):
    def __init__(self, current: Dict[str, str], on_apply, **kwargs):
        super().__init__(title="Settings", size_hint=(0.92, 0.92), **kwargs)
        self.on_apply = on_apply
        self.inputs: Dict[str, TextInput] = {}

        layout = GridLayout(
            cols=2,
            spacing=10,
            padding=12,
            row_default_height=40,
            size_hint_y=None,
        )
        layout.bind(minimum_height=layout.setter("height"))

        self._add_field(layout, "Nickname", "nickname", current.get("nickname", ""))

        self.verbose_spinner = Spinner(
            text=current.get("verbose", "false"), values=("false", "true")
        )
        layout.add_widget(Label(text="Verbose logging"))
        layout.add_widget(self.verbose_spinner)

        self.mode_spinner = Spinner(
            text=current.get("mode", MODE_CHOICES[0]), values=MODE_CHOICES
        )
        layout.add_widget(Label(text="Mode"))
        layout.add_widget(self.mode_spinner)

        self._add_field(
            layout, "Peer / Target host", "host", current.get("host", "127.0.0.1")
        )
        self._add_field(
            layout,
            "Bind address (listen/rendezvous)",
            "bind",
            current.get("bind", "0.0.0.0"),
        )
        self._add_field(layout, "Port", "port", current.get("port", "4444"))

        self.transport_spinner = Spinner(
            text=current.get("transport", TRANSPORT_CHOICES[0]),
            values=TRANSPORT_CHOICES,
        )
        layout.add_widget(Label(text="Transport"))
        layout.add_widget(self.transport_spinner)

        self._add_field(layout, "Ygg binary", "binary", current.get("binary", "yggdrasil"))
        self._add_field(layout, "Ygg config", "config", current.get("config", ""))
        self._add_field(layout, "Ygg peers (comma separated)", "peers", current.get("peers", ""))

        hint = Label(
            text=(
                "Use rendezvous when neither side knows who should listen. The peer host "
                "is dialed while also listening on the bind address."
            ),
            halign="left",
            valign="middle",
            size_hint=(1, None),
            height=60,
        )
        hint.bind(size=lambda inst, _: inst.setter("text_size")(inst, inst.size))
        layout.add_widget(hint)
        apply_btn = Button(text="Save", background_color=HEADER_COLOR)
        apply_btn.bind(on_press=lambda *_: self._apply())
        layout.add_widget(apply_btn)

        scroll = ScrollView(size_hint=(1, 1))
        scroll.add_widget(layout)
        self.content = scroll

    def _add_field(self, layout: GridLayout, label: str, key: str, value: str) -> None:
        layout.add_widget(Label(text=label))
        inp = TextInput(text=value)
        self.inputs[key] = inp
        layout.add_widget(inp)

    def _collect(self) -> Dict[str, str]:
        data = {k: v.text.strip() for k, v in self.inputs.items()}
        data["mode"] = self.mode_spinner.text
        data["transport"] = self.transport_spinner.text
        data["verbose"] = self.verbose_spinner.text
        return data

    def _apply(self) -> None:
        self.on_apply(self._collect())
        self.dismiss()


class ChatLayout(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(orientation="vertical", padding=0, spacing=0, **kwargs)
        with self.canvas.before:
            Color(*BACKGROUND)
            self._bg_rect = Rectangle(size=self.size, pos=self.pos)
        self.bind(size=self._update_bg_rect, pos=self._update_bg_rect)
        self.settings = self._load_settings()
        # Inline previews are enabled by default; users can still open files with their OS viewer.
        self.enable_previews = True

        self.controller = ChatController(
            on_message=self._handle_message,
            on_status=self._handle_status,
        )
        self.controller.set_nickname(self.settings["nickname"])

        self._presence_circle = None

        try:
            Window.bind(on_dropfile=self._on_drop_file)
        except Exception:
            # Some platforms (e.g., Android) may not expose drag-and-drop.
            pass

        self._build_header()
        self._build_status()
        self._build_log()
        self._build_input()
        self._set_presence(False, "Idle")

    def _load_settings(self) -> Dict[str, str]:
        settings = dict(DEFAULT_SETTINGS)
        path = _settings_path()
        if not path.exists():
            return settings
        try:
            loaded = json.loads(path.read_text())
            if isinstance(loaded, dict):
                for key in DEFAULT_SETTINGS:
                    if key in loaded:
                        settings[key] = str(loaded[key])
        except Exception as exc:  # noqa: BLE001
            logger.warning("Could not load settings from %s: %s", path, exc)
        return settings

    def _save_settings(self) -> None:
        path = _settings_path()
        try:
            path.write_text(json.dumps(self.settings, indent=2))
        except Exception as exc:  # noqa: BLE001
            logger.warning("Could not persist settings to %s: %s", path, exc)

    def _build_header(self) -> None:
        header = BoxLayout(
            orientation="horizontal",
            size_hint=(1, None),
            height=64,
            padding=(12, 8),
            spacing=10,
        )
        with header.canvas.before:
            Color(*HEADER_COLOR)
            self._header_rect = Rectangle(size=header.size, pos=header.pos)
        header.bind(size=self._update_header_rect, pos=self._update_header_rect)

        self.title_label = Label(
            text="2PChat",
            color=(1, 1, 1, 1),
            bold=True,
            font_size=20,
            halign="left",
            size_hint=(None, 1),
        )
        self.title_label.bind(size=lambda inst, _: inst.setter("text_size")(inst, inst.size))

        self.presence_chip = BoxLayout(
            orientation="horizontal",
            size_hint=(None, 1),
            width=180,
            padding=(0, 0),
            spacing=6,
        )

        self.presence_icon = Label(size_hint=(None, None), size=(14, 14))
        with self.presence_icon.canvas.before:
            self._presence_color = Color(*PRESENCE_OFFLINE)
            self._presence_circle = Rectangle(
                size=self.presence_icon.size, pos=self.presence_icon.pos
            )
        self.presence_icon.bind(
            size=lambda inst, _: self._update_presence_circle(),
            pos=lambda inst, _: self._update_presence_circle(),
        )

        self.presence_label = Label(
            text="Offline",
            size_hint=(None, 1),
            width=100,
            color=(1, 1, 1, 1),
            halign="left",
            valign="middle",
        )
        self.presence_label.bind(
            size=lambda inst, _: inst.setter("text_size")(inst, inst.size)
        )

        self.nickname_label = Label(
            text=f"Nickname: {self.settings['nickname']}",
            size_hint=(None, 1),
            width=160,
            color=(1, 1, 1, 1),
            halign="left",
            valign="middle",
        )
        self.nickname_label.bind(
            size=lambda inst, _: inst.setter("text_size")(inst, inst.size)
        )

        self.presence_chip.add_widget(self.presence_icon)
        self.presence_chip.add_widget(self.presence_label)

        identity_btn = Button(
            text="Identity",
            size_hint=(None, 1),
            width=95,
            background_color=(1, 1, 1, 0.25),
        )
        identity_btn.bind(on_press=lambda *_: self._show_identity())

        settings_btn = Button(
            text="Settings", size_hint=(None, 1), width=95, background_color=(1, 1, 1, 0.18)
        )
        settings_btn.bind(on_press=lambda *_: self._open_settings())

        connect_btn = Button(
            text="Connect", size_hint=(None, 1), width=95, background_color=(1, 1, 1, 0.25)
        )
        connect_btn.bind(on_press=lambda *_: self._start_session())

        disconnect_btn = Button(
            text="Disconnect", size_hint=(None, 1), width=95, background_color=(1, 1, 1, 0.25)
        )
        disconnect_btn.bind(on_press=lambda *_: self._stop_session())

        reconnect_btn = Button(
            text="Reconnect", size_hint=(None, 1), width=95, background_color=(1, 1, 1, 0.25)
        )
        reconnect_btn.bind(on_press=lambda *_: self._reconnect())

        header.add_widget(self.title_label)
        header.add_widget(self.presence_chip)
        header.add_widget(self.nickname_label)
        header_buttons = [
            identity_btn,
            settings_btn,
            connect_btn,
            disconnect_btn,
            reconnect_btn,
        ]
        for btn in header_buttons:
            header.add_widget(btn)
        self._header_buttons = header_buttons
        self._header = header
        self._resize_header(None, Window.size)
        Window.bind(size=self._resize_header)
        self.add_widget(header)

    def _update_header_rect(self, instance, _value):
        self._header_rect.size = instance.size
        self._header_rect.pos = instance.pos

    def _resize_header(self, _instance, size):
        if not hasattr(self, "_header"):
            return
        width, _height = size
        button_width = max(82, width / 10)
        label_width = max(140, width / 8)
        self.nickname_label.width = label_width
        self.presence_label.width = max(100, width / 10)
        self.title_label.width = max(120, width / 6)
        self.presence_chip.width = max(160, width / 7)
        for btn in self._header_buttons:
            btn.width = button_width
        self._header.width = width

    def _update_bg_rect(self, *args):
        if hasattr(self, "_bg_rect"):
            self._bg_rect.size = self.size
            self._bg_rect.pos = self.pos

    def _build_status(self) -> None:
        self.status_label = Label(
            text="",
            size_hint=(1, None),
            height=28,
            padding=(8, 4),
            color=(0.2, 0.2, 0.2, 1),
        )
        self.status_label.bind(size=lambda inst, _: inst.setter("text_size")(inst, inst.size))
        self.add_widget(self.status_label)

    def _build_log(self) -> None:
        self.log_scroll = ScrollView(size_hint=(1, 1), do_scroll_x=False)
        self.log_box = BoxLayout(
            orientation="vertical",
            size_hint_y=None,
            spacing=8,
            padding=(8, 8),
        )
        self.log_box.bind(minimum_height=self.log_box.setter("height"))
        self.log_scroll.add_widget(self.log_box)
        self.add_widget(self.log_scroll)

    def _build_input(self) -> None:
        row = BoxLayout(size_hint=(1, None), height=54, padding=(10, 10), spacing=10)
        attach_btn = Button(
            text="Attach",
            size_hint=(None, 1),
            width=90,
            background_color=(0.8, 0.82, 0.85, 1),
            color=(0.1, 0.1, 0.1, 1),
        )
        attach_btn.bind(on_press=lambda *_: self._open_file_picker())
        self.message_input = TextInput(hint_text="Message", size_hint=(1, 1), multiline=False)
        send_btn = Button(text="Send", size_hint=(None, 1), width=90, background_color=HEADER_COLOR)
        send_btn.bind(on_press=lambda *_: self._send_message())
        self.message_input.bind(on_text_validate=lambda *_: self._send_message())
        row.add_widget(attach_btn)
        row.add_widget(self.message_input)
        row.add_widget(send_btn)
        self.add_widget(row)

    def _open_settings(self) -> None:
        SettingsPopup(self.settings, on_apply=self._apply_settings).open()

    def _apply_settings(self, new_settings: Dict[str, str]) -> None:
        self.settings.update(new_settings)
        self.controller.set_nickname(self.settings.get("nickname", ""))
        verbose = self.settings.get("verbose", "false").lower() == "true"
        self.controller.set_log_level(logging.DEBUG if verbose else logging.INFO)
        self.nickname_label.text = f"Nickname: {self.settings.get('nickname', '')}"
        self._save_settings()
        self._set_status(f"Settings updated (saved to {_settings_path().name})")

    def _collect_transport_options(self) -> dict:
        if self.settings.get("transport") != "ygg-embedded":
            return {}
        peers = [p.strip() for p in self.settings.get("peers", "").split(",") if p.strip()]
        return {
            "binary_path": self.settings.get("binary", "yggdrasil"),
            "config_path": self.settings.get("config") or None,
            "public_peers": peers,
        }

    def _start_session(self) -> None:
        host = self.settings.get("host", "127.0.0.1")
        bind = self.settings.get("bind", "0.0.0.0")
        port = int(self.settings.get("port", "4444"))
        transport = self.settings.get("transport", TRANSPORT_CHOICES[0])
        options = self._collect_transport_options()
        mode = self.settings.get("mode", MODE_CHOICES[0])

        if mode == "connect":
            future = self.controller.connect(host, port, transport, **options)
        elif mode == "listen":
            future = self.controller.listen(bind, port, transport, **options)
        else:
            future = self.controller.rendezvous(host, port, transport, bind, **options)

        future.add_done_callback(self._handle_future)

    def _handle_future(self, result_future):
        exc = result_future.exception()
        if exc:
            Clock.schedule_once(lambda *_: self._set_status(str(exc)))

    def _stop_session(self) -> None:
        self._set_status("Disconnecting...")

        def _close_controller():
            try:
                self.controller.disconnect()
            except Exception as exc:  # noqa: BLE001
                Clock.schedule_once(lambda *_: self._set_status(f"Disconnect failed: {exc}"))
                return
            Clock.schedule_once(lambda *_: self._set_status("Disconnected"))
            Clock.schedule_once(lambda *_: self._set_presence(False, "Disconnected"))

        Thread(target=_close_controller, daemon=True).start()

    def _reconnect(self) -> None:
        try:
            future = self.controller.reconnect()
        except Exception as exc:  # noqa: BLE001
            self._set_status(str(exc))
            return
        future.add_done_callback(self._handle_future)

    def _handle_message(self, payload):
        inbound = not payload.get("outbound", False)
        Clock.schedule_once(lambda *_: self._append_chat(payload, inbound=inbound))

    def _handle_status(self, text: str):
        def _update(_):
            lower = text.lower()
            if "offline" in lower:
                self._set_presence(False, text)
            elif "connected" in lower or "rendezvous" in lower or "online" in lower:
                self._set_presence(True, text)
            self._set_status(text)

        Clock.schedule_once(_update)

    def _append_chat(self, payload, inbound: bool):
        ts = payload.get("timestamp")
        ts_text = datetime.utcfromtimestamp(ts).strftime("%H:%M:%S") if ts else ""
        author = payload.get("nickname")
        if not author:
            author = "Me" if not inbound else "Peer"
        prefix = "→" if not inbound else "←"

        mtype = payload.get("type", "chat")
        if mtype == "file_offer":
            body = payload.get("file_name") or "file"
            size = payload.get("file_size")
            note = (
                f"{prefix} {author} [{ts_text}] offered file {body}"
                + (f" ({size} bytes)" if size else "")
            )
            self._add_text_line(note, inbound=inbound)
            return

        if mtype == "file_saved":
            desc = payload.get("file_name") or "file"
            path = payload.get("file_path")
            mime = payload.get("mime")
            summary = f"{prefix} {author} [{ts_text}] sent {desc}"
            if mime:
                summary += f" ({mime})"
            if path:
                summary += f" → saved to {path}"
            self._add_text_line(summary, inbound=inbound)
            # Inline previews are shown by default; saved files can also be opened
            # with the OS viewer when preferred.
            if getattr(self, "enable_previews", False):
                self._add_media_preview(path, mime)
            return

        body = payload.get("body", "")
        line = f"{prefix} {author} [{ts_text}]: {body}"
        self._add_text_line(line, inbound=inbound)

    def _append_local_echo(self, body: str) -> None:
        payload = {
            "body": body,
            "timestamp": int(datetime.utcnow().timestamp()),
            "nickname": self.settings.get("nickname", "Me"),
        }
        self._append_chat(payload, inbound=False)

    def _set_status(self, text: str):
        self.status_label.text = text
        if text:
            self._add_text_line(f"{text}", status=True)

    def _set_presence(self, online: bool, reason: str = ""):
        color = PRESENCE_ONLINE if online else PRESENCE_OFFLINE
        label = "Online" if online else "Offline"
        self.presence_label.text = label
        if self._presence_circle:
            self._presence_circle.size = self.presence_icon.size
            self._presence_circle.pos = self.presence_icon.pos
        if hasattr(self, "_presence_color"):
            self._presence_color.rgba = color
        if reason:
            self.status_label.text = reason

    def _update_presence_circle(self):
        if self._presence_circle:
            self._presence_circle.size = self.presence_icon.size
            self._presence_circle.pos = self.presence_icon.pos

    def _add_text_line(self, text: str, inbound: bool | None = None, status: bool = False) -> None:
        if status:
            chip = Label(
                text=text,
                size_hint=(None, None),
                halign="center",
                valign="middle",
                color=(0.2, 0.24, 0.3, 1),
                padding=(10, 6),
            )

            def _size_chip(inst, *_):
                inst.text_size = (None, None)
                inst.texture_update()
                inst.size = (inst.texture_size[0] + 20, inst.texture_size[1] + 10)

            chip.bind(texture_size=_size_chip)
            _size_chip(chip)

            row = BoxLayout(size_hint=(1, None), height=chip.height + 6, padding=(8, 2))
            left = BoxLayout(size_hint=(1, 1))
            right = BoxLayout(size_hint=(1, 1))
            bubble = BoxLayout(size_hint=(None, None), size=chip.size)
            with bubble.canvas.before:
                Color(*STATUS_CHIP)
                bubble._bg = RoundedRectangle(size=bubble.size, pos=bubble.pos, radius=[14])

            bubble.bind(size=lambda inst, *_: setattr(inst._bg, "size", inst.size))
            bubble.bind(pos=lambda inst, *_: setattr(inst._bg, "pos", inst.pos))
            bubble.add_widget(chip)
            row.add_widget(left)
            row.add_widget(bubble)
            row.add_widget(right)
            self._add_widget_line(row)
            return

        is_inbound = True if inbound is None else inbound
        row = BoxLayout(size_hint=(1, None), padding=(10, 2), spacing=8)
        spacer = BoxLayout(size_hint=(1, 1))

        max_width = max(220, int(self.log_scroll.width * 0.76)) if hasattr(self, "log_scroll") else 560
        bubble = BoxLayout(size_hint=(None, None), width=max_width, padding=(12, 8))
        bubble_bg = INBOUND_BUBBLE if is_inbound else OUTBOUND_BUBBLE
        with bubble.canvas.before:
            Color(*bubble_bg)
            bubble._bg = RoundedRectangle(size=bubble.size, pos=bubble.pos, radius=[16])

        line = Label(
            text=text,
            size_hint=(1, None),
            halign="left",
            valign="middle",
            color=(0.1, 0.12, 0.16, 1),
        )

        def _resize_line(inst, *_):
            text_width = max(120, bubble.width - 24)
            inst.text_size = (text_width, None)
            inst.texture_update()
            inst.height = inst.texture_size[1]
            bubble.height = inst.height + 16
            row.height = bubble.height + 4

        line.bind(width=_resize_line, texture_size=_resize_line)
        bubble.bind(width=_resize_line)
        bubble.bind(size=lambda inst, *_: setattr(inst._bg, "size", inst.size))
        bubble.bind(pos=lambda inst, *_: setattr(inst._bg, "pos", inst.pos))

        bubble.add_widget(line)
        if is_inbound:
            row.add_widget(bubble)
            row.add_widget(spacer)
        else:
            row.add_widget(spacer)
            row.add_widget(bubble)

        Clock.schedule_once(lambda *_: _resize_line(line), 0)
        self._add_widget_line(row)

    def _add_media_preview(self, path: str | None, mime: str | None) -> None:
        if not path or not mime or not hasattr(self, "log_scroll"):
            return

        base_width = max(self.log_scroll.width, 320)

        def _target_height(texture, fallback_ratio: float = 0.75) -> float:
            ratio = fallback_ratio
            if texture and getattr(texture, "width", 0):
                ratio = max(0.1, texture.height / texture.width)
            # Keep previews readable but avoid huge empty space when the source is tiny.
            raw_height = base_width * ratio
            return min(640, max(140, raw_height))

        image_fit_kwargs = {"fit_mode": "contain"}

        def _open_full_image(_instance):
            popup_img = Image(source=path, **image_fit_kwargs)
            Popup(title="Image", content=popup_img, size_hint=(0.92, 0.92)).open()

        if mime.startswith("image"):
            wrapper = Button(
                size_hint=(1, None),
                height=_target_height(None),
                background_normal="",
                background_down="",
                background_color=(1, 1, 1, 0),
                padding=(4, 4),
            )
            img = Image(
                source=path,
                **image_fit_kwargs,
                size_hint=(1, None),
                height=_target_height(None),
            )

            def _resize_preview(*_args):
                img.height = _target_height(img.texture)
                wrapper.height = img.height

            img.bind(texture_size=_resize_preview)
            wrapper.bind(size=_resize_preview)
            Clock.schedule_once(_resize_preview, 0)

            wrapper.add_widget(img)
            wrapper.bind(on_press=_open_full_image)
            self._add_widget_line(wrapper)
        elif mime.startswith("video"):
            video = Video(
                source=path,
                size_hint=(1, None),
                height=_target_height(None),
                allow_stretch=True,
                keep_ratio=True,
            )

            def _resize_video(*_args):
                video.height = _target_height(video.texture)

            video.bind(texture_size=_resize_video, size=_resize_video)
            Clock.schedule_once(_resize_video, 0)

            self._add_widget_line(video)

    def _add_widget_line(self, widget) -> None:
        if not hasattr(self, "log_box"):
            return
        self.log_box.add_widget(widget)
        self._scroll_to_bottom()

    def _scroll_to_bottom(self):
        if not hasattr(self, "log_scroll") or not hasattr(self, "log_box"):
            return

        if self.log_box.height <= self.log_scroll.height:
            return

        def _do_scroll(_):
            self.log_scroll.scroll_y = 0

        Clock.schedule_once(_do_scroll)

    def _send_message(self) -> None:
        text = self.message_input.text.strip()
        if not text:
            return
        future = self.controller.send_chat(text)
        future.add_done_callback(self._handle_future)
        self._append_local_echo(text)
        self.message_input.text = ""

    def _open_file_picker(self) -> None:
        native_path = self._native_file_dialog()
        if native_path:
            self._send_file(native_path)
            return

        try:
            chooser = FileChooserListView(path=str(_local_app_dir()), size_hint=(1, 1))
        except ModuleNotFoundError as exc:
            self._set_status(
                f"File picker unavailable ({exc}). Install missing Windows runtime dependencies."
            )
            return

        def _choose(_instance):
            selection = chooser.selection
            if not selection:
                popup.dismiss()
                return
            popup.dismiss()
            self._send_file(selection[0])

        chooser.dirselect = False
        chooser.multiselect = False
        send_btn = Button(text="Send", size_hint=(1, None), height=48)
        send_btn.bind(on_press=_choose)

        container = BoxLayout(orientation="vertical")
        container.add_widget(chooser)
        container.add_widget(send_btn)

        popup = Popup(title="Select file to send", content=container, size_hint=(0.9, 0.9))
        chooser.bind(on_submit=lambda *_: _choose(None))
        popup.open()

    def _native_file_dialog(self):
        """Open the platform file picker when available (e.g., Windows native dialog)."""

        # Prefer native desktop picker to match platform expectations (e.g., Windows Explorer).
        if platform.system().lower() not in {"windows", "darwin", "linux"}:
            return None

        try:
            import tkinter as tk
            from tkinter import filedialog
        except Exception as exc:  # pragma: no cover - depends on host environment
            logger.debug("Native dialog unavailable: %s", exc)
            return None

        try:
            root = tk.Tk()
            root.withdraw()
            try:
                root.attributes("-topmost", True)
            except Exception:
                pass
            path = filedialog.askopenfilename()
            root.destroy()
            return path or None
        except Exception as exc:  # pragma: no cover - GUI environment specific
            logger.debug("Native dialog failed, falling back to Kivy picker: %s", exc)
            return None

    def _send_file(self, path: str) -> None:
        self._add_text_line(f"Sending file {path}...")
        future = self.controller.send_file(path)
        future.add_done_callback(self._handle_future)

    def _on_drop_file(self, _window, file_path: bytes, *_args):
        """Handle drag-and-drop files from the OS file manager."""

        try:
            decoded = Path(file_path.decode("utf-8")).expanduser()
        except Exception:
            logger.debug("Could not decode dropped file path: %s", file_path)
            return
        self._send_file(str(decoded))

    def _show_identity(self) -> None:
        local_fp = self.controller.local_fingerprint()
        local_hex = self.controller.local_fingerprint(encoding="hex")
        payload = self.controller.identity_qr_payload(self.settings.get("nickname") or None)
        peer_fp = self.controller.peer_fingerprint()
        sas = self.controller.session_sas()

        summary_lines = [
            "Your identity (public only, safe to share):",
            f"- Fingerprint (base64): {local_fp}",
            f"- Fingerprint (hex): {local_hex}",
            "",
            "Use Generate QR to show a scannable identity code.",
            "Secrets are never embedded—only your public fingerprint and label.",
            payload,
        ]
        if peer_fp:
            summary_lines.append("")
            summary_lines.append(f"Connected peer fingerprint: {peer_fp}")
            if sas:
                summary_lines.append(f"Safety number (SAS): {sas}")
        else:
            summary_lines.append("Connect to a peer to compute a SAS (safety number).")

        root = BoxLayout(orientation="vertical", spacing=8, padding=(8, 8))
        qr_image = Image(size_hint=(1, None), height=260, fit_mode="contain")

        def _render_qr(_instance):
            qr = qrcode.QRCode(border=2, box_size=8)
            qr.add_data(payload)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white")
            buffer = BytesIO()
            img.save(buffer, format="PNG")
            buffer.seek(0)
            qr_image.texture = CoreImage(buffer, ext="png").texture

        generate_btn = Button(
            text="Generate QR",
            size_hint=(1, None),
            height=44,
            background_color=HEADER_COLOR,
        )
        generate_btn.bind(on_press=_render_qr)

        content = TextInput(
            text="\n".join(summary_lines),
            readonly=True,
            size_hint=(1, None),
            height=360,
        )
        scroll = ScrollView(size_hint=(1, 1))
        scroll.add_widget(content)

        root.add_widget(generate_btn)
        root.add_widget(qr_image)
        root.add_widget(scroll)
        Popup(title="Identity & Verification", content=root, size_hint=(0.9, 0.95)).open()

    def on_stop(self):
        self.controller.close()


class MessengerApp(App):
    def build(self):
        return ChatLayout()


if __name__ == "__main__":
    MessengerApp().run()
