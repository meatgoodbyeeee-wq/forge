package xc;
import forge.ai.combatdrill.CombatDrillSolver;
import forge.ai.combatdrill.CombatDrillSolver.*;
import java.util.*;
public class Bench {
  public static void main(String[] a){
    Random r=new Random(1);
    for(int n=Integer.getInteger("nmin",3);n<=Integer.getInteger("nmax",7);n++) for(int ply: new int[]{4,6,8}){
      long tot=0,max=0; int to=0, N=20;
      for(int k=0;k<N;k++){
        Creature[] me=new Creature[n], op=new Creature[n];
        for(int i=0;i<n;i++){me[i]=rc(r);op[i]=rc(r);}
        CombatDrillSolver s=new CombatDrillSolver(me,op,ply);
        long t0=System.nanoTime(); s.setDeadline(t0+5_000_000_000L);
        try{ s.choose(new State((1<<n)-1,(1<<n)-1,12+r.nextInt(8),12+r.nextInt(8),0,0),0);}catch(CombatDrillSolver.TimeoutException e){to++;}
        long dt=System.nanoTime()-t0; tot+=dt; max=Math.max(max,dt);
      }
      System.out.printf("%dv%d ply%d avg=%.0fms max=%.0fms timeouts(5s)=%d/%d%n",n,n,ply,tot/1e6/N,max/1e6,to,N);
      if(tot/1e6/N>3000) break;
    }
  }
  static Creature rc(Random r){Creature c=new Creature(1+r.nextInt(5),1+r.nextInt(5));
    if(r.nextDouble()<.2)c.fly=true; if(r.nextDouble()<.08)c.reach=true; if(r.nextDouble()<.08)c.fs=true; if(r.nextDouble()<.08)c.dt=true;
    if(r.nextDouble()<.08)c.tr=true; if(r.nextDouble()<.06)c.me=true; if(r.nextDouble()<.08)c.vg=true; if(r.nextDouble()<.08)c.ll=true; return c;}
}
