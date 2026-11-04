#!/usr/bin/env python3
"""Build docs/stage2/stage2-report.docx from docs/stage2/stage2-report.md.

Standard library only. It understands the Markdown the report uses: headings, paragraphs,
bullet and numbered lists (nested by two spaces), tables, images on their own line,
**bold**, `code` and [links](url). Wide tables (five or more columns) and images are
placed on landscape pages; everything else is on portrait US Letter pages.

Usage: python3 scripts/build_report.py [input.md] [output.docx]
Export to PDF afterwards with Word (File > Save As > PDF), or scripts/export_pdf.sh.
"""
import os
import re
import struct
import sys
import zipfile
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'docs', 'stage2', 'stage2-report.md')
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, 'docs', 'stage2', 'stage2-report.docx')

# Page geometry in twips (1440 per inch).
PAGE_W, PAGE_H, MARGIN = 12240, 15840, 1080
PORTRAIT_TEXT = PAGE_W - 2 * MARGIN
LANDSCAPE_TEXT = PAGE_H - 2 * MARGIN
LANDSCAPE_IMAGE_H = PAGE_W - 2 * MARGIN - 900   # room for the caption line
FONT, MONO = 'Calibri', 'Consolas'
ACCENT = '1F4E79'

W_NS = ('xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
        'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
        'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
        'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"')


# ---------------------------------------------------------------- markdown parsing

def parse(md):
    """Return a list of blocks: (kind, data)."""
    lines = md.split('\n')
    blocks, i = [], 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        m = re.match(r'^(#{1,6})\s+(.*)$', line)
        if m:
            blocks.append(('heading', (len(m.group(1)), m.group(2).strip())))
            i += 1
            continue
        m = re.match(r'^!\[([^\]]*)\]\(([^)]+)\)\s*$', line)
        if m:
            blocks.append(('image', (m.group(1), m.group(2))))
            i += 1
            continue
        if line.lstrip().startswith('|'):
            rows = []
            while i < len(lines) and lines[i].lstrip().startswith('|'):
                cells = [c.strip() for c in lines[i].strip().strip('|').split('|')]
                if not all(re.fullmatch(r':?-{3,}:?', c) for c in cells):
                    rows.append(cells)
                i += 1
            blocks.append(('table', rows))
            continue
        if re.match(r'^\s*(-|\d+\.)\s+', line):
            items = []
            while i < len(lines) and re.match(r'^\s*(-|\d+\.)\s+', lines[i]):
                m = re.match(r'^(\s*)(-|\d+\.)\s+(.*)$', lines[i])
                items.append((len(m.group(1)) // 2, m.group(2) != '-', m.group(3)))
                i += 1
            blocks.append(('list', items))
            continue
        para = [line.strip()]
        i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r'^(#|!\[|\s*\||\s*(-|\d+\.)\s)', lines[i]):
            para.append(lines[i].strip())
            i += 1
        blocks.append(('para', ' '.join(para)))
    return blocks


INLINE = re.compile(r'(\*\*(?P<b>.+?)\*\*)|(`(?P<c>[^`]+)`)|(\[(?P<lt>[^\]]+)\]\((?P<lu>[^)]+)\))|(?P<url>https?://[^\s)|]+)')


class Doc:
    def __init__(self):
        self.body = []
        self.rels = []          # (id, type, target, external)
        self.media = []         # (name, bytes)
        self.nums = []          # ordered-list instances
        self.pic = 0

    def rel(self, kind, target, external=False):
        rid = f'rId{len(self.rels) + 10}'
        self.rels.append((rid, kind, target, external))
        return rid

    # ---- runs ----
    def runs(self, text, size=None, bold=False, color=None):
        out, pos = [], 0
        for m in INLINE.finditer(text):
            if m.start() > pos:
                out.append(self.run(text[pos:m.start()], size, bold, color))
            if m.group('b') is not None:
                out.append(''.join(self.runs(m.group('b'), size, True, color)))
            elif m.group('c') is not None:
                out.append(self.run(m.group('c'), size, bold, color, mono=True))
            else:
                label = m.group('lt') or m.group('url')
                url = m.group('lu') or m.group('url')
                if url.startswith('http'):
                    rid = self.rel('http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink',
                                   url, external=True)
                    out.append(f'<w:hyperlink r:id="{rid}">' + ''.join(
                        self.run(label, size, bold, '0563C1', underline=True)) + '</w:hyperlink>')
                else:
                    out.append(self.run(label, size, bold, color))
            pos = m.end()
        if pos < len(text):
            out.append(self.run(text[pos:], size, bold, color))
        return out

    @staticmethod
    def run(text, size=None, bold=False, color=None, mono=False, underline=False):
        props = []
        if mono:
            props.append(f'<w:rFonts w:ascii="{MONO}" w:hAnsi="{MONO}" w:cs="{MONO}"/>')
        if bold:
            props.append('<w:b/>')
        if color:
            props.append(f'<w:color w:val="{color}"/>')
        if underline:
            props.append('<w:u w:val="single"/>')
        sz = size or (19 if mono else None)
        if sz:
            props.append(f'<w:sz w:val="{sz}"/><w:szCs w:val="{sz}"/>')
        rpr = f'<w:rPr>{"".join(props)}</w:rPr>' if props else ''
        return f'<w:r>{rpr}<w:t xml:space="preserve">{escape(text)}</w:t></w:r>'

    # ---- blocks ----
    def para(self, text, style=None, keep_next=False, num=None, size=None, after=None, align=None):
        ppr = []
        if style:
            ppr.append(f'<w:pStyle w:val="{style}"/>')
        if keep_next:
            ppr.append('<w:keepNext/>')
        if num:
            ppr.append(f'<w:numPr><w:ilvl w:val="{num[1]}"/><w:numId w:val="{num[0]}"/></w:numPr>')
        if after is not None:
            ppr.append(f'<w:spacing w:after="{after}"/>')
        if align:
            ppr.append(f'<w:jc w:val="{align}"/>')
        return f'<w:p><w:pPr>{"".join(ppr)}</w:pPr>{"".join(self.runs(text, size))}</w:p>'

    def lst(self, items):
        # Each run of numbered items gets its own numbering instance, so it restarts at 1.
        out, current = [], {}
        for level, ordered, text in items:
            for deeper in [l for l in current if l > level]:
                del current[deeper]
            if ordered:
                if level not in current:
                    self.nums.append(len(self.nums))
                    current[level] = 100 + len(self.nums)
                nid = current[level]
            else:
                current.pop(level, None)
                nid = 1
            out.append(self.para(text, 'ListParagraph', num=(nid, level), after=40))
        return out

    def table(self, rows, width):
        ncol = max(len(r) for r in rows)
        rows = [r + [''] * (ncol - len(r)) for r in rows]
        size = 16 if ncol >= 7 else 17 if ncol >= 5 else 19
        # Column widths: every column fits its longest word, and the rest of the width is
        # shared by how much text the column holds.
        char = size * 10 * 0.55                       # twips per character at this size
        minimum, weight = [], []
        for c in range(ncol):
            words = [w for r in rows for w in re.sub(r'[*`]', '', r[c]).split()]
            longest_word = max((len(w) for w in words), default=4)
            minimum.append(int(min(longest_word, 26) * char * (1.15 if '`' in ''.join(r[c] for r in rows) else 1)) + 220)
            lens = sorted(len(re.sub(r'[*`]', '', r[c])) for r in rows)
            weight.append(max(1.0, 0.5 * lens[len(lens) * 3 // 4] + 0.25 * lens[-1]))
        spare = max(0, width - sum(minimum))
        total = sum(weight)
        widths = [minimum[c] + int(spare * weight[c] / total) for c in range(ncol)]
        if sum(widths) > width:                       # too many columns: scale down evenly
            widths = [int(w * width / sum(widths)) for w in widths]
        widths[-1] += width - sum(widths)
        grid = ''.join(f'<w:gridCol w:w="{w}"/>' for w in widths)
        border = ''.join(f'<w:{s} w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/>'
                         for s in ('top', 'left', 'bottom', 'right', 'insideH', 'insideV'))
        out = [f'<w:tbl><w:tblPr><w:tblW w:w="{width}" w:type="dxa"/><w:tblLayout w:type="fixed"/>'
               f'<w:tblBorders>{border}</w:tblBorders>'
               f'<w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/>'
               f'<w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar>'
               f'</w:tblPr><w:tblGrid>{grid}</w:tblGrid>']
        for ri, row in enumerate(rows):
            head = ri == 0
            trpr = '<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>' if head else '<w:trPr><w:cantSplit/></w:trPr>'
            cells = []
            for c, text in enumerate(row):
                shade = f'<w:shd w:val="clear" w:color="auto" w:fill="{"DCE6F1" if head else "FFFFFF"}"/>'
                runs = ''.join(self.runs(text, size, bold=head))
                cells.append(f'<w:tc><w:tcPr><w:tcW w:w="{widths[c]}" w:type="dxa"/>{shade}</w:tcPr>'
                             f'<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>'
                             f'</w:pPr>{runs}</w:p></w:tc>')
            out.append(f'<w:tr>{trpr}{"".join(cells)}</w:tr>')
        out.append('</w:tbl>')
        out.append('<w:p><w:pPr><w:spacing w:after="60"/></w:pPr></w:p>')
        return out

    def image(self, alt, path, max_w, max_h):
        full = os.path.normpath(os.path.join(os.path.dirname(SRC), path))
        data = open(full, 'rb').read()
        w_px, h_px = struct.unpack('>II', data[16:24])
        scale = min(max_w / w_px, max_h / h_px)
        cx, cy = int(w_px * scale * 635), int(h_px * scale * 635)   # twips -> EMU
        self.pic += 1
        name = f'image{self.pic}.png'
        self.media.append((name, data))
        rid = self.rel('http://schemas.openxmlformats.org/officeDocument/2006/relationships/image', f'media/{name}')
        n = self.pic
        return (f'<w:p><w:pPr><w:jc w:val="center"/><w:spacing w:after="0"/></w:pPr><w:r><w:drawing>'
                f'<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="{cx}" cy="{cy}"/>'
                f'<wp:docPr id="{n}" name="Picture {n}" descr="{escape(alt)}"/>'
                f'<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>'
                f'<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
                f'<pic:pic><pic:nvPicPr><pic:cNvPr id="{n}" name="{name}"/><pic:cNvPicPr/></pic:nvPicPr>'
                f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
                f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
                f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>'
                f'</wp:inline></w:drawing></w:r></w:p>')


def sect(landscape, last=False):
    if landscape:
        size = f'<w:pgSz w:w="{PAGE_H}" w:h="{PAGE_W}" w:orient="landscape"/>'
    else:
        size = f'<w:pgSz w:w="{PAGE_W}" w:h="{PAGE_H}"/>'
    return (f'<w:sectPr><w:footerReference w:type="default" r:id="rIdFooter"/>{size}'
            f'<w:pgMar w:top="{MARGIN}" w:right="{MARGIN}" w:bottom="{MARGIN}" w:left="{MARGIN}" '
            f'w:header="600" w:footer="500" w:gutter="0"/></w:sectPr>')


def build():
    md = open(SRC, encoding='utf-8').read()
    blocks = parse(md)
    doc = Doc()

    # Decide the orientation of each block: wide tables and images (with the bold caption
    # just before them) are landscape.
    orient = []
    for k, (kind, data) in enumerate(blocks):
        wide = kind == 'image' or (kind == 'table' and max(len(r) for r in data) >= 5)
        orient.append(wide)
    for k in range(len(blocks) - 1):
        kind, data = blocks[k]
        if kind == 'para' and orient[k + 1] and (data.startswith('**') and blocks[k + 1][0] == 'image'
                                                 or blocks[k + 1][0] == 'table' and len(data) < 400):
            orient[k] = True
    # A heading goes with the block that follows it.
    for k in range(len(blocks) - 2, -1, -1):
        if blocks[k][0] == 'heading' and orient[k + 1]:
            orient[k] = True

    body, current = [], False
    first_h1 = True
    for k, (kind, data) in enumerate(blocks):
        if orient[k] != current and body:
            # Close the running section with a section break carrying its properties.
            body.append(f'<w:p><w:pPr>{sect(current)}</w:pPr></w:p>')
        current = orient[k]
        width = LANDSCAPE_TEXT if current else PORTRAIT_TEXT
        if kind == 'heading':
            level, text = data
            if level == 1 and first_h1:
                body.append(doc.para(text, 'Title'))
                first_h1 = False
            else:
                body.append(doc.para(text, f'Heading{min(level - 1, 3) or 1}', keep_next=True))
        elif kind == 'para':
            caption = data.startswith('**') and k + 1 < len(blocks) and blocks[k + 1][0] == 'image'
            body.append(doc.para(data, 'Caption' if caption else None, keep_next=caption))
        elif kind == 'list':
            body.extend(doc.lst(data))
        elif kind == 'table':
            body.extend(doc.table(data, width))
        elif kind == 'image':
            body.append(doc.image(data[0], data[1], LANDSCAPE_TEXT, LANDSCAPE_IMAGE_H))
            nxt = k + 1 < len(blocks) and blocks[k + 1]
            # One diagram per page: a page break before the next caption.
            if nxt and nxt[0] == 'para' and nxt[1].startswith('**') and k + 2 < len(blocks) \
                    and blocks[k + 2][0] == 'image':
                body.append('<w:p><w:r><w:br w:type="page"/></w:r></w:p>')
    document = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {W_NS}><w:body>'
                + ''.join(body) + sect(current) + '</w:body></w:document>')
    write(doc, document)


def numbering(doc):
    def lvl(i, fmt, text, indent):
        return (f'<w:lvl w:ilvl="{i}"><w:start w:val="1"/><w:numFmt w:val="{fmt}"/><w:lvlText w:val="{text}"/>'
                f'<w:lvlJc w:val="left"/><w:pPr><w:ind w:left="{indent}" w:hanging="300"/></w:pPr>'
                + (f'<w:rPr><w:rFonts w:ascii="Symbol" w:hAnsi="Symbol" w:hint="default"/></w:rPr>'
                   if fmt == 'bullet' else '') + '</w:lvl>')
    bullets = ''.join(lvl(i, 'bullet', '', 400 + 360 * i) for i in range(4))
    decimal = ''.join(lvl(i, 'decimal', f'%{i + 1}.', 400 + 360 * i) for i in range(4))
    nums = '<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>'
    for n in range(len(doc.nums)):
        nid = 101 + n
        nums += (f'<w:num w:numId="{nid}"><w:abstractNumId w:val="1"/>'
                 + ''.join(f'<w:lvlOverride w:ilvl="{i}"><w:startOverride w:val="1"/></w:lvlOverride>'
                           for i in range(4)) + '</w:num>')
    return (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering {W_NS}>'
            f'<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>{bullets}</w:abstractNum>'
            f'<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>{decimal}</w:abstractNum>'
            f'{nums}</w:numbering>')


def styles():
    def heading(n, size, before, after, color=ACCENT):
        return (f'<w:style w:type="paragraph" w:styleId="Heading{n}"><w:name w:val="heading {n}"/>'
                f'<w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>'
                f'<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="{before}" w:after="{after}"/>'
                f'<w:outlineLvl w:val="{n - 1}"/></w:pPr>'
                f'<w:rPr><w:rFonts w:ascii="{FONT}" w:hAnsi="{FONT}"/><w:b/><w:color w:val="{color}"/>'
                f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/></w:rPr></w:style>')
    return (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles {W_NS}>'
            f'<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="{FONT}" w:hAnsi="{FONT}" w:eastAsia="{FONT}" '
            f'w:cs="{FONT}"/><w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="en-CA"/></w:rPr></w:rPrDefault>'
            f'<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault>'
            f'</w:docDefaults>'
            f'<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>'
            f'<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>'
            f'<w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:before="0" w:after="200"/>'
            f'<w:pBdr><w:bottom w:val="single" w:sz="8" w:space="6" w:color="{ACCENT}"/></w:pBdr></w:pPr>'
            f'<w:rPr><w:b/><w:color w:val="{ACCENT}"/><w:sz w:val="40"/><w:szCs w:val="40"/></w:rPr></w:style>'
            + heading(1, 30, 360, 140) + heading(2, 25, 260, 100) + heading(3, 22, 200, 80, '2E75B6') +
            f'<w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="caption"/><w:basedOn w:val="Normal"/>'
            f'<w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="0" w:after="80"/></w:pPr>'
            f'<w:rPr><w:color w:val="404040"/></w:rPr></w:style>'
            f'<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/>'
            f'<w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>'
            f'<w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/>'
            f'<w:rPr><w:color w:val="7F7F7F"/><w:sz w:val="17"/></w:rPr></w:style>'
            f'</w:styles>')


def footer():
    fld = lambda code: (f'<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> {code} '
                        f'</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r>'
                        f'<w:r><w:fldChar w:fldCharType="end"/></w:r>')
    return (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:ftr {W_NS}><w:p><w:pPr>'
            f'<w:pStyle w:val="Footer"/></w:pPr>'
            f'<w:r><w:t xml:space="preserve">MealIQ - Stage 2 Implementation Report</w:t></w:r>'
            f'<w:r><w:ptab w:relativeTo="margin" w:alignment="right" w:leader="none"/>'
            f'<w:t xml:space="preserve">Page </w:t></w:r>{fld("PAGE")}'
            f'<w:r><w:t xml:space="preserve"> of </w:t></w:r>{fld("NUMPAGES")}</w:p></w:ftr>')


def write(doc, document):
    rels = ''.join(
        f'<Relationship Id="{rid}" Type="{kind}" Target="{escape(target)}"'
        + (' TargetMode="External"' if ext else '') + '/>' for rid, kind, target, ext in doc.rels)
    base = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    doc_rels = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                f'<Relationship Id="rIdStyles" Type="{base}/styles" Target="styles.xml"/>'
                f'<Relationship Id="rIdNumbering" Type="{base}/numbering" Target="numbering.xml"/>'
                f'<Relationship Id="rIdSettings" Type="{base}/settings" Target="settings.xml"/>'
                f'<Relationship Id="rIdFooter" Type="{base}/footer" Target="footer1.xml"/>{rels}</Relationships>')
    content_types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                     '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                     '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                     '<Default Extension="xml" ContentType="application/xml"/>'
                     '<Default Extension="png" ContentType="image/png"/>'
                     '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
                     '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
                     '<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>'
                     '<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>'
                     '<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>'
                     '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
                     '</Types>')
    root_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                 f'<Relationship Id="rId1" Type="{base}/officeDocument" Target="word/document.xml"/>'
                 '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
                 '</Relationships>')
    core = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
            'xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>MealIQ - Stage 2 Implementation Report</dc:title>'
            '</cp:coreProperties>')
    settings = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings {W_NS}>'
                '<w:updateFields w:val="true"/><w:defaultTabStop w:val="720"/></w:settings>')
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', content_types)
        z.writestr('_rels/.rels', root_rels)
        z.writestr('docProps/core.xml', core)
        z.writestr('word/document.xml', document)
        z.writestr('word/styles.xml', styles())
        z.writestr('word/numbering.xml', numbering(doc))
        z.writestr('word/settings.xml', settings)
        z.writestr('word/footer1.xml', footer())
        z.writestr('word/_rels/document.xml.rels', doc_rels)
        for name, data in doc.media:
            z.writestr(f'word/media/{name}', data)
    print(f'Wrote {OUT} ({len(doc.media)} images)')


if __name__ == '__main__':
    build()
