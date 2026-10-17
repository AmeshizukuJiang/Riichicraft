"""Generate native tile geometry and 37 item appearances; standard library only."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/riichicraft'
KEYS = [f'{rank}{suit}' for suit in 'mps' for rank in range(1, 10)]
KEYS += [f'{rank}z' for rank in range(1, 8)] + ['0m', '0p', '0s']


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def cuboid(lo, hi, material, front=False):
    faces = {side: {'uv': [0, 0, 16, 16], 'texture': '#' + material}
             for side in ('down', 'up', 'north', 'south', 'west', 'east')}
    if front:
        faces['south'] = {'uv': [0, 0, 16, 11], 'texture': '#face'}
    return {'from': lo, 'to': hi, 'faces': faces}


# Geometry is 2 x 2.75 x 1.25 sixteenths of a block. The small steps soften
# the rim without custom shaders. The face points south (+Z), top is +Y.
write(ASSETS / 'models/item/mahjong_tiles/body.json', {
    'ambientocclusion': False,
    'gui_light': 'front',
    'textures': {
        'particle': 'riichicraft:item/mahjong_tiles/ivory',
        'ivory': 'riichicraft:item/mahjong_tiles/ivory',
        'back': 'riichicraft:item/mahjong_tiles/back',
        'face': 'riichicraft:item/mahjong_tiles/1m',
    },
    'elements': [
        cuboid([7.04, 6.68, 7.375], [8.96, 9.32, 7.44], 'back'),
        cuboid([7, 6.625, 7.44], [9, 9.375, 7.92], 'back'),
        cuboid([7, 6.625, 7.92], [9, 9.375, 8.565], 'ivory'),
        cuboid([7.04, 6.68, 8.565], [8.96, 9.32, 8.625], 'ivory', front=True),
    ],
    'display': {
        'gui': {'rotation': [12, -20, 0], 'scale': [5.2, 5.2, 5.2]},
        'firstperson_righthand': {'rotation': [0, -30, 0], 'translation': [-2, 6, -2], 'scale': [2.4, 2.4, 2.4]},
        'firstperson_lefthand': {'rotation': [0, 30, 0], 'translation': [-2, 6, -2], 'scale': [2.4, 2.4, 2.4]},
        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 1, 0], 'scale': [3, 3, 3]},
        'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 1, 0], 'scale': [3, 3, 3]},
        'ground': {'translation': [0, 2, 0], 'scale': [2.5, 2.5, 2.5]},
        'fixed': {'scale': [6, 6, 6]},
    },
})
for key in KEYS:
    write(ASSETS / f'models/item/mahjong_tiles/{key}.json', {
        'parent': 'riichicraft:item/mahjong_tiles/body',
        'textures': {'face': f'riichicraft:item/mahjong_tiles/{key}'},
    })
    write(ASSETS / f'items/mahjong_tiles/{key}.json', {
        'model': {'type': 'minecraft:model', 'model': f'riichicraft:item/mahjong_tiles/{key}'},
    })
write(ASSETS / 'items/mahjong_tile.json', {
    'model': {'type': 'minecraft:model', 'model': 'riichicraft:item/mahjong_tiles/1m'},
})
write(ASSETS / 'models/item/mahjong_tiles/hidden.json', {
    'parent': 'riichicraft:item/mahjong_tiles/body',
    'textures': {'face': 'riichicraft:item/mahjong_tiles/back'},
})
write(ASSETS / 'items/mahjong_tiles/hidden.json', {
    'model': {'type': 'minecraft:model', 'model': 'riichicraft:item/mahjong_tiles/hidden'},
})
print('Generated shared tile body, 37 faces, hidden display model and matching item definitions.')
