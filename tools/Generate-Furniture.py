"""Rebuild Riichicraft's original block-model furniture JSON, using only the standard library."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"
ASSETS = RES / "assets/riichicraft"


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def box(start, end, texture):
    return {"from": start, "to": end, "faces": {
        face: {"uv": [0, 0, 16, 16], "texture": "#" + texture}
        for face in ("down", "up", "north", "south", "west", "east")}}


textures = {"particle": "riichicraft:block/furniture_wood", "wood": "riichicraft:block/furniture_wood",
            "cloth": "riichicraft:block/furniture_cloth"}
variants, inventory = {}, []
for part in range(9):
    x, z = part % 3, part // 3
    elements = [box([0, 12, 0], [16, 15, 16], "wood"), box(
        [1 if x == 0 else 0, 15, 1 if z == 0 else 0],
        [15 if x == 2 else 16, 16, 15 if z == 2 else 16], "cloth")]
    if x == 0:
        elements.append(box([0, 15, 0], [1, 16, 16], "wood"))
    if x == 2:
        elements.append(box([15, 15, 0], [16, 16, 16], "wood"))
    if z == 0:
        elements.append(box([0, 15, 0], [16, 16, 1], "wood"))
    if z == 2:
        elements.append(box([0, 15, 15], [16, 16, 16], "wood"))
    if x != 1 and z != 1:
        lx, lz = (2 if x == 0 else 11), (2 if z == 0 else 11)
        elements.append(box([lx, 0, lz], [lx + 3, 12, lz + 3], "wood"))
    write(ASSETS / f"models/block/large_mahjong_table_{part}.json",
          {"parent": "minecraft:block/block", "textures": textures, "elements": elements})
    for facing, angle in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        variants[f"facing={facing},part={part}"] = {
            "model": f"riichicraft:block/large_mahjong_table_{part}", "y": angle}
    for element in elements:
        miniature = json.loads(json.dumps(element))
        for key in ("from", "to"):
            px, py, pz = element[key]
            miniature[key] = [round((x * 16 + px) / 3, 6), round(py / 3 + 5, 6), round((z * 16 + pz) / 3, 6)]
        inventory.append(miniature)

write(ASSETS / "blockstates/large_mahjong_table.json", {"variants": variants})
write(ASSETS / "models/item/large_mahjong_table.json",
      {"parent": "minecraft:block/block", "textures": textures, "elements": inventory})

for name, cloth in (("mahjong_chair", "furniture_cloth"), ("empty_chair", "empty_chair_cloth")):
    elements = [box([2, 6, 2], [14, 7, 14], "wood"), box([3, 7, 3], [13, 8, 13], "cloth"),
                box([2, 8, 12], [14, 16, 14], "wood"), box([3, 10, 11.75], [13, 15, 12], "cloth")]
    for x in (2, 12):
        for z in (2, 12):
            elements.append(box([x, 0, z], [x + 2, 6, z + 2], "wood"))
    write(ASSETS / f"models/block/{name}.json", {
        "parent": "minecraft:block/block", "textures": {**textures, "cloth": f"riichicraft:block/{cloth}"}, "elements": elements})
    write(ASSETS / f"blockstates/{name}.json", {"variants": {
        f"facing={facing}": {"model": f"riichicraft:block/{name}", "y": angle}
        for facing, angle in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})

for name in ("large_mahjong_table", "mahjong_chair", "empty_chair"):
    write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:model",
          "model": "riichicraft:" + ("item/" if name == "large_mahjong_table" else "block/") + name}})
    conditions = [{"condition": "minecraft:survives_explosion"}]
    if name == "large_mahjong_table":
        conditions.append({"condition": "minecraft:block_state_property", "block": f"riichicraft:{name}", "properties": {"part": "4"}})
    write(RES / f"data/riichicraft/loot_table/blocks/{name}.json", {"type": "minecraft:block", "pools": [
        {"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"riichicraft:{name}"}], "conditions": conditions}]})

write(RES / "data/minecraft/tags/block/mineable/axe.json", {"replace": False, "values": [
    "riichicraft:" + name for name in ("mahjong_table", "large_mahjong_table", "mahjong_chair", "empty_chair")]})
print("Generated original nine-part table, two chairs, item models, loot and axe tag inside the repository.")
