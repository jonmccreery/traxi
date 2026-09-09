#!/usr/bin/env python3
"""Render a project markdown document to styled HTML at the repo root.

Faithful conversion, not a rewrite: python-markdown (tables + fenced_code)
plus the two things it can't do alone — GFM strikethrough, and raw angle
brackets in prose that a browser would otherwise eat as fake HTML tags.
Styled to match DEVELOPMENT-JOURNEY.html.

Defaults to ENGINEERING-RECORD.md, which is what it was written for; any
other document in the repo can be passed instead, so the second one did not
have to mean a second copy of the stylesheet.

Usage: python3 tools/render-engineering-record.py [NAME.md]
Requires: pip install markdown
"""
import re
import sys
from pathlib import Path

import markdown

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / (sys.argv[1] if len(sys.argv) > 1 else "ENGINEERING-RECORD.md")
DST = SRC.with_suffix(".html")

md = SRC.read_text()

# The <title> is the document's own first heading, so a rendered page is not
# mislabelled as the engineering record.
title = next(
    (l.lstrip("# ").strip() for l in md.splitlines() if l.startswith("# ")),
    SRC.stem,
)

# Raw angle brackets in prose (not code) would be eaten as fake HTML tags.
md = md.replace('"dump by <date>"', '"dump by &lt;date&gt;"')

# GFM strikethrough -> <del>, spanning lines (python-markdown lacks the extension).
md = re.sub(r"~~(.+?)~~", r"<del>\1</del>", md, flags=re.DOTALL)

body = markdown.markdown(md, extensions=["tables", "fenced_code"])

# Wide tables must scroll in their own container, not the page body.
body = body.replace("<table>", '<div class="tablewrap"><table>').replace(
    "</table>", "</table></div>")

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TITLE</title>
<style>
  :root {
    --paper: #F6F7F4;
    --ink: #212E28;
    --muted: #5F6D65;
    --line: #D7DDD5;
    --accent: #2E6B52;
    --accent-soft: #E4EEE8;
    --card: #FFFFFF;
    --code-bg: #EAEEE9;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --paper: #121815;
      --ink: #D9E0DA;
      --muted: #8C988F;
      --line: #29332D;
      --accent: #7DBFA0;
      --accent-soft: #1B2A23;
      --card: #171F1B;
      --code-bg: #1D2721;
    }
  }
  * { box-sizing: border-box; }
  body {
    background: var(--paper);
    color: var(--ink);
    font-family: "Iowan Old Style", "Palatino Linotype", "Book Antiqua", Palatino, Georgia, serif;
    font-size: 17px;
    line-height: 1.62;
    margin: 0;
    -webkit-font-smoothing: antialiased;
  }
  main { max-width: 48rem; margin: 0 auto; padding: 3rem 1.4rem 5rem; }
  h1 {
    font-size: clamp(1.9rem, 5vw, 2.6rem);
    line-height: 1.12;
    margin: 0 0 1.2rem;
    font-weight: 600;
    letter-spacing: -0.01em;
    text-wrap: balance;
  }
  h2 {
    font-size: 1.45rem; font-weight: 600; letter-spacing: -0.01em;
    margin: 2.8rem 0 0.7rem; text-wrap: balance;
    padding-top: 0.4rem;
  }
  h3 { font-size: 1.12rem; font-weight: 600; margin: 1.9rem 0 0.6rem; text-wrap: balance; }
  h4 {
    font-family: ui-sans-serif, system-ui, sans-serif;
    font-size: 0.78rem; font-weight: 700; letter-spacing: 0.12em;
    text-transform: uppercase; color: var(--muted);
    margin: 1.6rem 0 0.6rem;
  }
  p { margin: 0 0 1rem; }
  ul, ol { margin: 0 0 1rem; padding-left: 1.3rem; }
  li { margin-bottom: 0.4rem; }
  code {
    font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
    font-size: 0.85em;
    background: var(--code-bg);
    padding: 0.1em 0.35em;
    border-radius: 3px;
  }
  pre {
    background: var(--code-bg);
    border: 1px solid var(--line);
    border-radius: 6px;
    padding: 0.85rem 1rem;
    overflow-x: auto;
    font-size: 0.82rem;
    line-height: 1.5;
    margin: 1rem 0;
  }
  pre code { background: none; padding: 0; font-size: inherit; }
  .tablewrap { overflow-x: auto; margin: 1.2rem 0; }
  table {
    border-collapse: collapse; width: 100%;
    font-size: 0.88rem;
    font-family: ui-sans-serif, system-ui, sans-serif;
  }
  th {
    text-align: left; font-size: 0.7rem; letter-spacing: 0.1em; text-transform: uppercase;
    color: var(--muted); font-weight: 600;
    padding: 0.45rem 0.9rem 0.45rem 0; border-bottom: 1px solid var(--ink);
  }
  td { padding: 0.5rem 0.9rem 0.5rem 0; border-bottom: 1px solid var(--line); vertical-align: top; }
  blockquote {
    margin: 1.3rem 0;
    padding: 0.9rem 1.15rem;
    background: var(--card);
    border: 1px solid var(--line);
    border-left: 4px solid var(--accent);
    border-radius: 6px;
  }
  blockquote > :first-child { margin-top: 0; }
  blockquote > :last-child, blockquote p:last-child { margin-bottom: 0; }
  blockquote h2 { margin-top: 0; font-size: 1.2rem; }
  blockquote blockquote { border-left-color: var(--muted); margin: 1rem 0; }
  del { color: var(--muted); text-decoration: line-through; }
  hr { border: none; border-top: 1px solid var(--line); margin: 2.6rem 0; }
  a { color: var(--accent); overflow-wrap: anywhere; }
  strong { font-weight: 700; }
</style>
</head>
<body>
<main>
BODY
</main>
</body>
</html>
"""

DST.write_text(TEMPLATE.replace("TITLE", title).replace("BODY", body))
print(f"wrote {DST} ({len(body)} bytes of body)")
