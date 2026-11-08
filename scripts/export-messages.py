#!/usr/bin/env python3
"""Export SMS (mmssms.db) and WhatsApp (msgstore.db + wa.db) to readable HTML + CSV.

Usage: export_messages.py <raw_dir> <out_dir>
raw_dir holds sms/mmssms.db, wa/msgstore.db, wa/wa.db, contacts/contacts2.db (copies pulled from the phone).
Databases are opened read-only; nothing in raw_dir is modified.
"""
import csv
import datetime as dt
import html
import re
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

WA_TYPES = {0: "", 1: "[photo]", 2: "[audio]", 3: "[vidéo]", 4: "[contact]", 5: "[position]", 7: "[système]",
            9: "[document]", 13: "[GIF]", 15: "[message supprimé]", 16: "[position en direct]", 20: "[sticker]",
            42: "[photo vue unique]", 43: "[vidéo vue unique]", 64: "[message supprimé par admin]",
            90: "[appel]"}
SMS_TYPES = {1: "reçu", 2: "envoyé", 3: "brouillon", 4: "boîte d'envoi", 5: "échec", 6: "en attente"}
CSS = """body{font:15px/1.45 system-ui,sans-serif;max-width:820px;margin:0 auto;padding:16px;background:#f4f1ea;color:#1d1d1d}
h1{font-size:20px}.m{margin:6px 0;padding:8px 11px;border-radius:10px;background:#fff;max-width:78%;white-space:pre-wrap;
overflow-wrap:anywhere}.me{background:#d9f2cf;margin-left:auto}.meta{font-size:12px;color:#666}.day{text-align:center;
color:#666;font-size:12px;margin:18px 0 8px}a{color:#0b5cad}li{margin:3px 0}
@media (prefers-color-scheme:dark){body{background:#161616;color:#e8e8e8}.m{background:#262626}.me{background:#1f3d22}
.meta,.day{color:#9a9a9a}a{color:#7db6ff}}"""


def ro(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def digits_key(number: str) -> str:
    d = re.sub(r"\D", "", number or "")
    return d[-9:] if len(d) >= 9 else d


def phone_names(contacts_db: Path) -> dict:
    names = {}
    if not contacts_db.exists():
        return names
    q = """select d.data1, r.display_name from data d join mimetypes m on m._id=d.mimetype_id
           join raw_contacts r on r._id=d.raw_contact_id
           where m.mimetype='vnd.android.cursor.item/phone_v2' and r.deleted=0"""
    for number, name in ro(contacts_db).execute(q):
        if number and name:
            names.setdefault(digits_key(number), name)
    return names


def fmt_ts(ms: int) -> str:
    return dt.datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S") if ms else ""


def safe_name(text: str) -> str:
    return re.sub(r"[^\w.-]+", "_", text, flags=re.UNICODE).strip("_")[:60] or "sans_nom"


def write_chat_html(path: Path, title: str, rows: list) -> None:
    """rows: (timestamp_ms, is_me, sender, text)"""
    parts = [f"<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
             f"<title>{html.escape(title)}</title><style>{CSS}</style><p><a href='../index.html'>← index</a></p>"
             f"<h1>{html.escape(title)}</h1><p class=meta>{len(rows)} messages</p>"]
    last_day = None
    for ts, is_me, sender, text in rows:
        stamp = fmt_ts(ts)
        if stamp[:10] != last_day:
            last_day = stamp[:10]
            parts.append(f"<div class=day>{last_day}</div>")
        who = "" if is_me else (f"<b>{html.escape(sender)}</b> · " if sender else "")
        parts.append(f"<div class='m{' me' if is_me else ''}'><div class=meta>{who}{stamp[11:16]}</div>"
                     f"{html.escape(text)}</div>")
    path.write_text("\n".join(parts), encoding="utf-8")


def write_index(out: Path, sections: dict) -> None:
    parts = [f"<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
             f"<title>Export messages</title><style>{CSS}</style><h1>Export messages — "
             f"{dt.date.today().isoformat()}</h1>"]
    for label, chats in sections.items():
        parts.append(f"<h2>{html.escape(label)} ({len(chats)} conversations)</h2><ul>")
        for href, title, count, last in sorted(chats, key=lambda c: c[3], reverse=True):
            parts.append(f"<li><a href='{href}'>{html.escape(title)}</a> <span class=meta>{count} msg · "
                         f"dernier {fmt_ts(last)[:10]}</span></li>")
        parts.append("</ul>")
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8")


def export_sms(raw: Path, out: Path, names: dict) -> list:
    db = raw / "sms" / "mmssms.db"
    rows = ro(db).execute("select thread_id, address, date, type, body from sms order by date").fetchall()
    threads = defaultdict(list)
    for thread_id, address, date, kind, body in rows:
        threads[(thread_id, address)].append((date, kind, body))
    (out / "sms").mkdir(parents=True, exist_ok=True)
    chats = []
    with open(out / "sms.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["date", "contact", "numéro", "sens", "texte"])
        for (thread_id, address), msgs in threads.items():
            name = names.get(digits_key(address), address or "?")
            title = f"{name} ({address})" if name != address else (address or "?")
            fname = f"sms/{thread_id}_{safe_name(name)}.html"
            html_rows = [(d, k == 2, name, b or "") for d, k, b in msgs]
            write_chat_html(out / fname, title, html_rows)
            chats.append((fname, title, len(msgs), msgs[-1][0]))
            for d, k, b in msgs:
                w.writerow([fmt_ts(d), name, address, SMS_TYPES.get(k, k), b or ""])
    return chats


def wa_names(raw: Path) -> dict:
    names = {}
    for jid, display, wa_name in ro(raw / "wa" / "wa.db").execute(
            "select jid, display_name, wa_name from wa_contacts"):
        names[jid] = display or wa_name or ""
    return names


def export_whatsapp(raw: Path, out: Path, phone_book: dict) -> list:
    con = ro(raw / "wa" / "msgstore.db")
    names = wa_names(raw)
    jids = {i: s for i, s in con.execute("select _id, raw_string from jid")}
    media = {m: (p, c) for m, p, c in con.execute(
        "select message_row_id, file_path, media_caption from message_media")}
    chats_meta = con.execute("select _id, jid_row_id, subject from chat").fetchall()
    # WhatsApp "@lid" privacy ids -> phone jid (jid_map) or the name WhatsApp stored for that lid
    lid_to_jid = {jids.get(lid): jids.get(pn) for lid, pn in con.execute("select lid_row_id, jid_row_id from jid_map")}
    lid_names = {jids.get(lid): name for lid, name in con.execute(
        "select lid_row_id, display_name from lid_display_name") if name}

    def label(jid: str) -> str:
        if not jid:
            return ""
        if jid.endswith("@lid"):
            phone_jid = lid_to_jid.get(jid)
            if phone_jid:
                return label(phone_jid)
            return names.get(jid) or lid_names.get(jid) or "contact " + jid.split("@")[0][-4:]
        if jid.endswith("@s.whatsapp.net"):
            number = jid.split("@")[0]
            return names.get(jid) or phone_book.get(digits_key(number)) or "+" + number
        return names.get(jid) or jid

    (out / "whatsapp").mkdir(parents=True, exist_ok=True)
    chats = []
    with open(out / "whatsapp.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["date", "discussion", "expéditeur", "moi", "type", "texte", "fichier"])
        for chat_id, jid_row, subject in chats_meta:
            jid = jids.get(jid_row, "")
            title = subject or label(jid) or f"chat {chat_id}"
            msgs = con.execute("""select _id, timestamp, from_me, sender_jid_row_id, message_type, text_data
                                  from message where chat_row_id=? and timestamp>0 order by sort_id, timestamp""",
                               (chat_id,)).fetchall()
            if not msgs:
                continue
            html_rows = []
            for mid, ts, from_me, sender_row, mtype, text in msgs:
                sender = "moi" if from_me else label(jids.get(sender_row, "")) or label(jid)
                file_path, caption = media.get(mid, (None, None))
                tag = WA_TYPES.get(mtype, f"[type {mtype}]")
                body = " ".join(x for x in (tag, text or caption or "",
                                            f"({Path(file_path).name})" if file_path else "") if x)
                html_rows.append((ts, bool(from_me), sender, body))
                w.writerow([fmt_ts(ts), title, sender, int(bool(from_me)), tag, text or caption or "",
                            file_path or ""])
            fname = f"whatsapp/{chat_id}_{safe_name(title)}.html"
            write_chat_html(out / fname, title, html_rows)
            chats.append((fname, title, len(msgs), msgs[-1][1]))
    return chats


def main() -> None:
    raw, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    names = phone_names(raw / "contacts" / "contacts2.db")
    sms = export_sms(raw, out, names)
    write_index(out, {"SMS": sms})
    print(f"SMS: {len(sms)} conversations, {sum(c[2] for c in sms)} messages")
    print(f"Index: {out / 'index.html'}")


if __name__ == "__main__":
    main()
