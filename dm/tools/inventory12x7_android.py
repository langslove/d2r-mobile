"""Build the isolated Android 84-cell experiment from a baseline runtime pack."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile
import zlib

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS.parent / "ios" / "tools"))
import casc_overlay as casc
import gen_widestash_art as art
import overlay_contract

LAYOUT = "data/global/ui/layouts/"
PANEL = "/playerinventoryexpansionlayouthd.json"
SPRITE = "data/hd/global/ui/controller/panel/inventorypanel/v2/inventorybg_classic.sprite"
RECTS = {
    "slot_head": (347,45,196,196), "slot_neck": (913,43,98,98),
    "slot_torso": (607,51,196,294), "slot_right_arm": (86,81,196,392),
    "slot_left_arm": (1126,81,196,392), "slot_right_hand": (852,160,98,98),
    "slot_left_hand": (974,160,98,98), "slot_belt": (606,371,196,98),
    "slot_feet": (864,273,196,196), "slot_gloves": (346,265,196,196),
    "Belt": (452,487), "grid": (114,599), "TouchPouch": (222,1294),
    "TouchPouchIcon": (246,1310), "TouchPouchCount": (350,1294),
    "TouchStore": (916,1323), "background_right_arm_selected": (74,29),
    "background_right_arm": (74,29), "background_left_arm_selected": (1114,29),
    "background_left_arm": (1114,29), "weaponswap_right_arm": (74,29),
    "weaponswap_left_arm": (1114,29), "text_i_left": (86,26),
    "text_ii_left": (191,26), "text_i_right": (1126,26), "text_ii_right": (1231,26),
    "WeaponSwapRightLegend": (79,-36), "WeaponSwapLeftLegend": (1125,-36),
}

def layout(text):
    # Preserve inherited widgets and the game's relaxed JSON syntax.
    pattern = re.compile(r'("name"\s*:\s*"([^"]+)"\s*,)(.*?)(?="type"\s*:|\Z)', re.S)
    counts = set()
    def replace(match):
        prefix, name, tail = match.groups()
        if name not in RECTS and name != "background":
            return match[0]
        counts.add(name)
        values = dict(zip(("x","y","width","height"), RECTS.get(name, (-648,-724))))
        if name == "background":
            fields = {"anchor":{"x":0.5,"y":0.5}, "filename":"Controller/Panel/InventoryPanel/V2/InventoryBG_Classic"}
        elif name == "grid":
            fields = {"cellCount":{"x":12,"y":7}}
        else:
            fields = {}
        opening = re.match(r'\s*"fields"\s*:\s*\{', tail)
        if opening:
            rest = tail[opening.end():]
            # Only this widget's fields precede its children or the next widget.
            rect = re.search(r'"rect"\s*:\s*\{([^{}]*)\}', rest)
            if rect:
                old = json.loads("{" + rect[1].strip().rstrip(",") + "}")
                old.update(values)
                rest = rest[:rect.start()] + '"rect": ' + json.dumps(old) + rest[rect.end():]
            else:
                rest = '"rect": ' + json.dumps(values) + ", " + rest
            for key, value in fields.items():
                literal = json.dumps(value)
                field = re.compile(r'"' + key + r'"\s*:\s*(?:\{[^{}]*\}|"[^"]*")')
                rest, edits = field.subn('"' + key + '": ' + literal, rest, count=1)
                if not edits:
                    rest = '"' + key + '": ' + literal + ", " + rest
            return prefix + tail[:opening.end()] + rest
        fields["rect"] = values
        return prefix + ' "fields": ' + json.dumps(fields) + ',' + tail
    output = pattern.sub(replace, text)
    assert {"grid", "background", "Belt"} <= counts
    assert "TouchBagTab" not in output
    return output

def entries(source):
    tvfs = casc.load_vfs1(source)
    po, ps, vo, _, co, cs = struct.unpack_from(">6I", tvfs, 12)
    width = casc.offset_field_size(cs)
    for _, name, offset, folder, _ in casc.walk_paths(tvfs, po, po + ps):
        if folder or tvfs[vo + offset] != 1:
            continue
        field = vo + offset + 9
        row = co + int.from_bytes(tvfs[field:field + width], "big")
        yield name.decode().lower(), bytes(tvfs[row:row + 16]), *struct.unpack_from(">I", tvfs, row + 16), struct.unpack_from(">I", tvfs, row + 21)[0]

def resource(data, item):
    _, key, encoded, size = item
    with (data / f"{key[9]:02d}-{key[10]:08x}.data").open("rb") as stream:
        stream.seek(int.from_bytes(key[11:], "big"))
        blob = stream.read(encoded)
    if blob.startswith(b"BLTE"):
        return casc.read_data_file(data, key, encoded)
    return blob if encoded == size else zlib.decompress(blob)

def background(stock):
    from PIL import Image
    path = "data/hd/global/ui/controller/panel/inventorypanel/v2/inventorybg_classic.sprite"
    original = resource(stock / "data", next(row for row in entries(stock) if row[0] == path))
    lib = art.lib()
    base = art.decode(lib, original)
    # Use a stock 49-pixel cell and the stock coin plate. Sprite coordinates are half layout units.
    cell = base.crop((10, 447, 59, 496))
    fill = base.crop((20, 460, 40, 480))
    canvas = Image.new("RGBA", (700, 724))
    for y in range(0, 724, 20):
        for x in range(0, 700, 20):
            canvas.paste(fill, (x, y))
    for row in range(7):
        for col in range(12):
            canvas.paste(cell, (57 + col * 49, 299 + row * 49))
    for name, rect in RECTS.items():
        if name.startswith("slot_"):
            x,y,w,h = rect
            canvas.paste(cell.resize((w//2,h//2)), (x//2,y//2))
    for col in range(4):
        canvas.paste(cell, (226 + col * 64, 243))
    canvas.paste(base.crop((152,628,352,692)), (270,655))
    return art.pack(lib, original, art.bc3(canvas), 700, 724, 700)

def build(stock, baseline, output):
    shutil.copytree(baseline, output)
    sprite = background(stock)
    for target in [output / "overlay", *sorted((output / "overlay_variants").glob("*"))]:
        config = target / "data" / ".build.config"
        if not config.is_file():
            continue
        with tempfile.TemporaryDirectory(dir=output.parent) as directory:
            work = Path(directory)
            source, result = work / "source", work / "result"
            (source / "data").mkdir(parents=True)
            (result / "data").mkdir(parents=True)
            shutil.copy2(config, source / "data" / ".build.config")
            for folder in (target / "data", output / "overlay" / "data", stock / "data"):
                for file in folder.glob("*.data"):
                    dest = source / "data" / file.name
                    if not dest.exists():
                        os.link(file, dest)
            index = {row[0]:row for row in entries(source)}
            paths = [LAYOUT + group + PANEL for group in ("controller/console/switch", "0touch_console_switch_lay", "xtouch_lay")]
            paths += ["data/global/excel/inventory.bin", "data/global/excel/base/inventory.bin"]
            replacements = {}
            manifest = []
            for path in paths:
                entry = index[path]
                blob = resource(source / "data", entry)
                if path.endswith(".json"):
                    blob = layout(blob.decode("utf-8-sig")).encode()
                else:
                    blob = bytearray(blob)
                    assert len(blob) == 7204 and struct.unpack_from("<I", blob) == (30,)
                    for row in (*range(5), *range(12,20), 27,28,29):
                        offset = 4 + 240 * row + 16
                        assert blob[offset:offset+2] == bytes((10,4)), (path,row)
                        blob[offset:offset+2] = bytes((12,7))
                file = work / str(len(replacements))
                file.write_bytes(blob)
                replacements[path] = file
                fields = [""] * 11
                fields[6], fields[9] = entry[1].hex(), path
                manifest.append("|".join(fields))

            file = work / "bag.sprite"
            file.write_bytes(sprite)
            replacements[SPRITE] = file
            fields = [""] * 11
            fields[6], fields[9] = index[SPRITE][1].hex(), SPRITE
            manifest.append("|".join(fields))
            (source / "data" / "build_manifest.psv").write_text("\n".join(manifest),encoding="utf8")
            casc.rewrite_tvfs(source, result, replacements, link_source=False)
            for file in (result / "data").iterdir():
                shutil.copy2(file, target / "data" / file.name)
        marker = target / "overlay.manifest"
        fingerprint = marker.read_text(encoding="utf8").splitlines()[0].split()[1]
        overlay_contract.write_manifest(target, fingerprint)
    marker = output / "pack_info.txt"
    marker.write_text(marker.read_text(encoding="utf8") + "EXPERIMENT=inventory12x7\n",encoding="utf8")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("stock_romfs","baseline_pack","output"):
        parser.add_argument(name,type=Path)
    args = parser.parse_args()
    build(args.stock_romfs,args.baseline_pack,args.output)
