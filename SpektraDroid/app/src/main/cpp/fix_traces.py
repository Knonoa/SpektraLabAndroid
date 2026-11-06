tags = ['dc_norm done', 'dc0 done', 'dense done', 'layers done', 'enlarger enter',
        'enlarger factor done', 'enlarger domain done', 'scanner enter']
path = 'spektra_engine.cpp'
src = open(path, encoding='utf-8', newline='').read()
for tag in tags:
    bad = 'fprintf(stderr, "[T] ' + tag + '" + chr(10) + ");'
    # simpler: find the broken literal directly
    bad2 = '"[T] ' + tag + '" + chr(10)
    pass
# do a regex fix: replace  <tag>\n");  with  <tag>\n");
import re
src2 = re.sub(r'(\[T\] [a-z_ ]+[a-z])\n\("\);"\)', r'\1\n");', src)
# fallback explicit
for tag in tags:
    src2 = src2.replace('"[T] ' + tag + '"\n");', '"[T] ' + tag + '\n");')
open(path, 'w', encoding='utf-8', newline='').write(src2)
print('done')
