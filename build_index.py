# Rebuilds website/index.html from template.html (embeds the icons as data URIs).
# Usage: python build_index.py   (run from this folder)
import base64, glob, json, os
here = os.path.dirname(os.path.abspath(__file__))
icons = {os.path.basename(f)[:-4]: 'data:image/png;base64,' + base64.b64encode(open(f, 'rb').read()).decode()
         for f in sorted(glob.glob(os.path.join(here, '..', 'icons', '*.png')))}
icons['_default'] = icons.get('sword')
t = open(os.path.join(here, 'template.html'), encoding='utf-8').read().replace('__ICONS__', json.dumps(icons))
full = ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
        + t.replace('<header class="bar">', '</head>\n<body id="top">\n<header class="bar">', 1) + '\n</body>\n</html>\n')
open(os.path.join(here, '..', 'website', 'index.html'), 'w', encoding='utf-8').write(full)
print('Built website/index.html')
