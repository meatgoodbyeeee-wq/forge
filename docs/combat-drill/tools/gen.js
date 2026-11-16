const E = require(process.env.DRILL_ENGINE || '../../../../combat-drill/src/engine.js');
const KW = ['fly','reach','fs','ds','dt','tr','me','vg','ll','ind','df','hx'];
function rint(a,b){return a+Math.floor(Math.random()*(b-a+1));}
function rc(){const p=rint(0,5),t=rint(1,6);const c={p,t};
  for(const k of KW) if(Math.random()<0.12) c[k]=true;
  if(c.fs&&c.ds) delete c.fs; if(Math.random()<0.1) c.wd=rint(1,2); return c;}
const enc=c=>[c.p,c.t,KW.filter(k=>c[k]).join('+')||'-',c.wd||0].join(',');
const N=+process.argv[2]||300; const out=[];
for(let n=0;n<N;n++){
  const MAXN=+(process.env.MAXN||4); const nm=rint(1,MAXN), no=rint(1,MAXN); const board={me:[],op:[]};
  // DUP=1: often copy a creature (identical creatures exercise the symmetry pruning)
  const dup=s=>{ for(let i=1;i<s.length;i++) if(process.env.DUP&&Math.random()<0.4) s[i]=Object.assign({},s[rint(0,i-1)]); };
  for(let i=0;i<nm;i++)board.me.push(rc()); for(let i=0;i<no;i++)board.op.push(rc()); dup(board.me); dup(board.op);
  let tapped=0; for(let i=0;i<no;i++) if(Math.random()<0.2) tapped|=1<<i;
  const life=[rint(1,14),rint(1,14)], ply=rint(4,8);
  const st={a:[(1<<nm)-1,(1<<no)-1],life,turn:0,tapped};
  const s=E.makeSolver(board,ply); const opts=s.rootOptions(st);
  const vals=opts.map(o=>o.attackers.reduce((m,i)=>m|1<<i,0)+':'+o.value).join(' ');
  out.push([ply,life.join(','),tapped,board.me.map(enc).join(';'),board.op.map(enc).join(';'),vals].join('|'));
}
console.log(out.join('\n'));
