import glob,re,math,collections
def res(f):
    w=[0,0]
    for l in open(f,errors='ignore'):
        m=re.search(r'Game Result: .*Ai\((\d)\).* has won',l)
        if m: w[int(m.group(1))-1]+=1
    return w
groups=collections.defaultdict(lambda:[0,0])
stats=collections.defaultdict(lambda: collections.Counter())
for f in glob.glob('/home/claude/work/runs/*_[0-9]*.log'):
    tag=re.sub(r'_\d+\.log$','',f.split('/')[-1]); tag={'diagWG':'rm_drillWG','diagBR':'rm_drillBR'}.get(tag,tag); w=res(f); g=groups[tag]; g[0]+=w[0]; g[1]+=w[1]
    for l in open(f,errors='ignore'):
        m=re.match(r'COMBAT-DRILL calls=(\d+) eligible=(\d+) searched=(\d+) timeouts=(\d+) overrides=(\d+) \(wins found=(\d+), losses avoided=(\d+)\)',l)
        if m:
            for k,v in zip(['calls','eligible','searched','timeouts','overrides','wins','avoid'],m.groups()): stats[tag][k]+=int(v)
extra={'co_base':['/tmp/v2_Default_Default.log','/tmp/ab_Default_Default.log'],'co_drillWG':['/tmp/v2_Drill_Default.log'],'co_drillBR':['/tmp/v2_Default_Drill.log']}
for tag,fs in extra.items():
    for f in fs:
        w=res(f); groups[tag][0]+=w[0]; groups[tag][1]+=w[1]
        for l in open(f,errors='ignore'):
            m=re.match(r'COMBAT-DRILL calls=(\d+) eligible=(\d+) searched=(\d+) timeouts=(\d+) overrides=(\d+) \(wins found=(\d+), losses avoided=(\d+)\)',l)
            if m:
                for k,v in zip(['calls','eligible','searched','timeouts','overrides','wins','avoid'],m.groups()): stats[tag][k]+=int(v)
for tag in sorted(groups): 
    g=groups[tag]; n=sum(g); print(f"{tag:12s} WG {g[0]:4d} BR {g[1]:4d}  n={n:4d}  WG%={100*g[0]/max(n,1):5.1f}  {dict(stats[tag]) if stats[tag] else ''}")
def eff(pref,drill='drill'):
    b=groups[pref+'_base']; nb=sum(b)
    if not nb: return
    pb=b[0]/nb
    out=[]
    for seat,idx,p0 in (('WG',0,pb),('BR',1,1-pb)):
        d=groups[f'{pref}_{drill}{seat}']; n=sum(d)
        if not n: continue
        p=d[idx]/n; se=math.sqrt(p*(1-p)/n + p0*(1-p0)/nb)
        out.append((seat,p,p0,n,se))
        print(f"  {pref} {drill}{seat}: drill {100*p:.1f}% vs base {100*p0:.1f}% -> {100*(p-p0):+.1f} pt (se {100*se:.1f}, n={n})")
    if len(out)==2:
        d=(out[0][1]-out[0][2]+out[1][1]-out[1][2])/2; se=math.sqrt(out[0][4]**2+out[1][4]**2)/2
        print(f"  {pref} {drill} combined: {100*d:+.1f} pt (se {100*se:.1f}, z={d/se:.2f})")
for pref in ('co','rm'):
    eff(pref); eff(pref,'dec'); eff(pref,'cau'); eff(pref,'off')
