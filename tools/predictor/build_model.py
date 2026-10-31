import re, glob, gzip, collections, sys
WORD = re.compile(r"[a-z]+(?:'[a-z]+)?")
uni = {}
for line in open('en_50k.txt', encoding='utf-8'):
    w, c = line.split()
    if WORD.fullmatch(w) and (len(w) > 1 or w in ('a', 'i')):
        uni[w] = int(c)
    if len(uni) >= 30000: break
vocab = set(uni)
big = collections.Counter(); tri = collections.Counter()
def feed(text, weight=1):
    for sent in re.split(r"[.!?;:\n]{1,}|\s-{2,}\s", text.lower().replace("’", "'")):
        toks = [t for t in WORD.findall(sent)]
        for i in range(len(toks) - 1):
            a, b = toks[i], toks[i+1]
            if a in vocab and b in vocab:
                big[(a, b)] += weight
                if i + 2 < len(toks) and toks[i+2] in vocab: tri[(a, b, toks[i+2])] += weight
for f in sorted(glob.glob('pg*.txt')):
    t = open(f, encoding='utf-8', errors='ignore').read()
    s = t.find('*** START'); e = t.find('*** END')
    t = t[t.find('\n', s) + 1 if s >= 0 else 0: e if e > 0 else len(t)]
    t = t.replace('\r\n', ' ').replace('\n\n', '.\n').replace('\n', ' ')
    feed(t)
for line in open('phrases.txt'):
    feed(line.strip(), weight=200)
# prune
by1 = collections.defaultdict(list)
for (a, b), c in big.items():
    if c >= 3: by1[a].append((c, b))
by2 = collections.defaultdict(list)
for (a, b, d), c in tri.items():
    if c >= 3: by2[(a, b)].append((c, d))
out = gzip.open(sys.argv[1], 'wt', encoding='utf-8', compresslevel=9)
out.write("# broker word predictor v1. Unigrams: FrequencyWords en_50k (OpenSubtitles 2018) by Hermit Dave, CC-BY-SA-4.0.\n")
out.write("# Bigrams/trigrams: Project Gutenberg public-domain texts + broker-authored phrases. This file: CC-BY-SA-4.0.\n")
nu = nb = nt = 0
for w, c in uni.items(): out.write(f"U\t{w}\t{c}\n"); nu += 1
for a, lst in by1.items():
    for c, b in sorted(lst, reverse=True)[:8]: out.write(f"B\t{a}\t{b}\t{c}\n"); nb += 1
for (a, b), lst in by2.items():
    for c, d in sorted(lst, reverse=True)[:4]: out.write(f"T\t{a}\t{b}\t{d}\t{c}\n"); nt += 1
out.close()
print("unigrams", nu, "bigrams", nb, "trigrams", nt)
