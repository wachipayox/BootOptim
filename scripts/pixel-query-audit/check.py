"""Pure-mask topology equivalence and callback-contract counterexample. Not runtime proof."""
import itertools, random

def topology(frames, w, h, bitmap):
    edges = {}; calls = []
    def read(f,x,y):
        calls.append((f,x,y)); return frames[f][y*w+x]
    def edge(d,a,e):
        k=(d,a)
        if k not in edges: edges[k]=[e,e]
        else: edges[k]=[min(edges[k][0],e),max(edges[k][1],e)]
    for f in range(len(frames)):
        rows = [[read(f,x,y) for x in range(w)] for y in range(h)] if bitmap else None
        q=(lambda x,y: rows[y][x]) if bitmap else (lambda x,y:read(f,x,y))
        for y in range(h):
            for x in range(w):
                if q(x,y): continue
                if y==0 or q(x,y-1):edge(0,y,x)
                if y==h-1 or q(x,y+1):edge(1,y,x)
                if x==0 or q(x-1,y):edge(2,x,y)
                if x==w-1 or q(x+1,y):edge(3,x,y)
    return list(edges.items()), calls

rng=random.Random(20260930); cases=0
for w,h in ((1,1),(1,4),(4,1),(2,2),(3,3)):
    for bits in itertools.product((False,True),repeat=w*h):
        a,_=topology([bits],w,h,False);b,_=topology([bits],w,h,True)
        assert a==b;cases+=1
for _ in range(5000):
    w=rng.randrange(1,33);h=rng.randrange(1,33)
    frames=[[rng.choice((False,True)) for _ in range(w*h)] for _ in range(rng.randrange(1,6))]
    a,_=topology(frames,w,h,False);b,_=topology(frames,w,h,True)
    assert a==b;cases+=1
_,a=topology([[False]*4],2,2,False);_,b=topology([[False]*4],2,2,True)
assert len(a)==12 and len(b)==4 and a!=b
print(f'PASS ordered-mask-topology cases={cases}; callback mismatch stock={len(a)} bitmap={len(b)}')
