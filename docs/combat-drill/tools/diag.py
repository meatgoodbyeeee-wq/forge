import re,glob,collections,sys
def cls(v): return 'win' if v>=900 else 'loss' if v<=-900 else 'open'
for seat,drillidx in (('WG',1),('BR',2)):
    games=[];cur=[]
    for f in sorted(glob.glob(f'diag{seat}_*.log')):
        for l in open(f,errors='ignore'):
            m=re.search(r'\[combat-drill\] me .* life (\d+)-(\d+) oppHand (\d+) oppOpenLands (\d+) ply (\d+): original \[(.*?)\] \((-?\d+)\) -> \[(.*?)\] \((-?\d+)\)',l)
            if m:
                o=[x for x in m.group(6).split(', ') if x]; n=[x for x in m.group(8).split(', ') if x]
                cur.append(dict(o=o,n=n,vo=int(m.group(7)),vn=int(m.group(9)),hand=int(m.group(3)),open=int(m.group(4)),life=(int(m.group(1)),int(m.group(2)))))
            m=re.search(r'Game Result: .*Ai\((\d)\).* has won',l)
            if m: games.append((int(m.group(1))==drillidx,cur)); cur=[]
    W=sum(w for w,_ in games); print(f"== drill as {seat}: {W}/{len(games)} wins")
    c=collections.Counter(); wins=collections.Counter()
    for w,ovs in games:
        for ov in ovs:
            more=len(ov['n'])-len(ov['o'])
            add=len(set(ov['n'])-set(ov['o'])); rem=len(set(ov['o'])-set(ov['n']))
            kind=('decisive-'+cls(ov['vn'])+'/'+cls(ov['vo'])) if cls(ov['vn'])!='open' or cls(ov['vo'])!='open' else ('open-more' if add>0 and rem==0 else 'open-less' if rem>0 and add==0 else 'open-swap')
            c[kind]+=1; wins[kind]+=w
    for k in sorted(c): print(f"  {k:28s} n={c[k]:3d}  gameWin%={100*wins[k]/c[k]:.0f}")
    # games with open-less / open-more overrides
    g=collections.Counter(); gw=collections.Counter()
    for w,ovs in games:
        ks=set('less' if (set(o['o'])-set(o['n'])) and cls(o['vn'])=='open' and cls(o['vo'])=='open' else 'x' for o in ovs)
        key='has open-less' if 'less' in ks else 'no open-less'
        g[key]+=1; gw[key]+=w
    for k in g: print(f"  games {k}: {gw[k]}/{g[k]}")
