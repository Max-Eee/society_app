#!/usr/bin/env python3
"""Generate one printable, secured QR form for every CCOCS group."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

from reportlab.graphics.barcode import qr
from reportlab.graphics.shapes import Drawing
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SUPABASE_CLIENT = PROJECT_ROOT / "app/src/main/java/com/example/Chennai_Coop/data/remote/SupabaseClient.kt"
SCAN_VIEW_MODEL = PROJECT_ROOT / "app/src/main/java/com/example/Chennai_Coop/ui/viewmodel/ScanViewModel.kt"


def kotlin_constant(path: Path, name: str) -> str:
    content = path.read_text(encoding="utf-8")
    match = re.search(rf"\b{name}\s*=\s*\"([^\"]+)\"", content)
    if not match:
        raise RuntimeError(f"Could not find {name} in {path}")
    return match.group(1)


def load_configuration() -> tuple[str, str, str]:
    url = os.environ.get("CCOCS_SUPABASE_URL") or kotlin_constant(SUPABASE_CLIENT, "SUPABASE_URL")
    key = os.environ.get("CCOCS_SUPABASE_KEY") or kotlin_constant(SUPABASE_CLIENT, "SUPABASE_API_KEY")
    secret = os.environ.get("CCOCS_QR_SECRET") or kotlin_constant(SCAN_VIEW_MODEL, "SECRET_KEY")
    return url.rstrip("/"), key, secret


def fetch_rows(base_url: str, key: str, group_id: str | None) -> list[dict]:
    rows: list[dict] = []
    page_size = 1000
    offset = 0
    while True:
        params = {
            "select": "group_id,group_qr_id,mno,name,station",
            "group_id": f"eq.{group_id}" if group_id else "not.is.null",
            "order": "group_id.asc",
            "limit": str(page_size),
            "offset": str(offset),
        }
        request = urllib.request.Request(
            f"{base_url}/rest/v1/ccocs?{urllib.parse.urlencode(params)}",
            headers={"apikey": key, "Authorization": f"Bearer {key}", "Accept": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            page = json.loads(response.read().decode("utf-8"))
        rows.extend(page)
        if len(page) < page_size:
            return rows
        offset += page_size


def member_sort_key(member: dict) -> tuple[int, str]:
    value = str(member.get("mno") or "").strip()
    return (int(value) if value.isdigit() else sys.maxsize, value)


def group_members(rows: list[dict]) -> dict[str, dict]:
    groups: dict[str, dict] = defaultdict(lambda: {"qr_id": None, "members": {}})
    for row in rows:
        group_id = str(row.get("group_id") or "").strip()
        member_no = str(row.get("mno") or "").strip()
        qr_id = str(row.get("group_qr_id") or "").strip()
        if not group_id or not member_no or not qr_id:
            continue
        group = groups[group_id]
        group["qr_id"] = qr_id
        group["members"].setdefault(member_no, row)
    for group in groups.values():
        group["members"] = sorted(group["members"].values(), key=member_sort_key)
    return dict(sorted(groups.items()))


def secured_group_payload(qr_id: str, secret: str) -> str:
    payload = f"GROUP:{qr_id}"
    signature = hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest().upper()
    return f"{payload}|{signature}"


def qr_drawing(value: str, size: float = 34 * mm) -> Drawing:
    widget = qr.QrCodeWidget(value)
    x1, y1, x2, y2 = widget.getBounds()
    drawing = Drawing(size, size, transform=[size / (x2 - x1), 0, 0, size / (y2 - y1), 0, 0])
    drawing.add(widget)
    return drawing


def build_pdf(output: Path, group_id: str, group: dict, args: argparse.Namespace, secret: str) -> None:
    styles = getSampleStyleSheet()
    title = ParagraphStyle("TitleCenter", parent=styles["Title"], alignment=TA_CENTER, fontSize=16, leading=19)
    center = ParagraphStyle("Center", parent=styles["Normal"], alignment=TA_CENTER, leading=14)
    small = ParagraphStyle("Small", parent=styles["Normal"], fontSize=8, leading=10)

    output.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(output), pagesize=A4, rightMargin=15 * mm, leftMargin=15 * mm,
        topMargin=12 * mm, bottomMargin=35 * mm,
        title=f"{args.event_title} - {group_id}", author=args.society_name,
    )
    qr_value = secured_group_payload(group["qr_id"], secret)
    meeting_lines = [
        f"<b>{html.escape(args.society_name)}</b>",
        html.escape(args.meeting_title),
        f"Date: {html.escape(args.meeting_date)}",
        f"Venue: {html.escape(args.venue)}",
        f"<b>Group ID: {html.escape(group_id)}</b>",
    ]
    header = Table(
        [[Paragraph("<br/>".join(meeting_lines), center), qr_drawing(qr_value)]],
        colWidths=[140 * mm, 35 * mm],
    )
    header.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ALIGN", (1, 0), (1, 0), "RIGHT"),
        ("BOX", (0, 0), (-1, -1), 0.7, colors.HexColor("#263238")),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))

    table_data = [["S.NO", "MEMBER NO", "NAME", "SIGN"]]
    for index, member in enumerate(group["members"], start=1):
        table_data.append([
            str(index),
            html.escape(str(member.get("mno") or "")),
            Paragraph(html.escape(str(member.get("name") or "")), small),
            "",
        ])
    member_table = Table(table_data, colWidths=[15 * mm, 28 * mm, 87 * mm, 45 * mm], repeatRows=1)
    member_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#263238")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("ALIGN", (0, 0), (1, -1), "CENTER"),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#546E7A")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F7F8")]),
        ("FONTSIZE", (0, 1), (1, -1), 8.5),
        ("TOPPADDING", (0, 1), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 1), (-1, -1), 4),
    ]))

    story = [
        header,
        Spacer(1, 5 * mm),
        Paragraph(html.escape(args.event_title), title),
        Spacer(1, 4 * mm),
        member_table,
    ]

    def draw_attestation(canvas, _doc) -> None:
        canvas.saveState()
        canvas.setStrokeColor(colors.HexColor("#546E7A"))
        canvas.line(15 * mm, 32 * mm, 195 * mm, 32 * mm)
        canvas.setFillColor(colors.black)
        canvas.setFont("Helvetica-Bold", 9)
        canvas.drawString(17 * mm, 26 * mm, f"No. of members signed: ______ / {len(group['members'])}")
        canvas.drawRightString(191 * mm, 26 * mm, "Attested by")
        canvas.setFont("Helvetica", 9)
        canvas.drawRightString(191 * mm, 19 * mm, "Signature: ____________________")
        canvas.drawRightString(191 * mm, 12 * mm, "Name: ________________________")
        canvas.restoreState()

    doc.build(story, onFirstPage=draw_attestation, onLaterPages=draw_attestation)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group-id", help="Generate only one group, for example EDHS013")
    parser.add_argument("--output-dir", type=Path, default=PROJECT_ROOT / "output/pdf/group-forms")
    parser.add_argument("--society-name", default="Chennai Corporation Official Society Limited - 5.125")
    parser.add_argument("--meeting-title", default="G.B. Meeting")
    parser.add_argument("--meeting-date", default="12/01/2026 at 11:00 AM")
    parser.add_argument("--venue", default="Conference Hall, Admin Building")
    parser.add_argument("--event-title", default="Sweet List 2026")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    base_url, key, secret = load_configuration()
    group_id = args.group_id.upper() if args.group_id else None
    groups = group_members(fetch_rows(base_url, key, group_id))
    if not groups:
        raise SystemExit(f"No group records found{f' for {group_id}' if group_id else ''}.")
    for current_group_id, group in groups.items():
        output = args.output_dir / f"{current_group_id}-sweet-list-2026.pdf"
        build_pdf(output, current_group_id, group, args, secret)
        print(output)
    print(f"Generated {len(groups)} group PDF(s) in {args.output_dir.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
