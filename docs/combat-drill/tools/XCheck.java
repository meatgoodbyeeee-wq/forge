package xc;
import forge.ai.combatdrill.CombatDrillSolver;
import forge.ai.combatdrill.CombatDrillSolver.*;
import java.nio.file.*; import java.util.*;
public class XCheck {
  static Creature dec(String s){String[] f=s.split(",");Creature c=new Creature(Integer.parseInt(f[0]),Integer.parseInt(f[1]));
    for(String k:f[2].split("\\+")) switch(k){case "fly":c.fly=true;break;case "reach":c.reach=true;break;case "fs":c.fs=true;break;case "ds":c.ds=true;break;
      case "dt":c.dt=true;break;case "tr":c.tr=true;break;case "me":c.me=true;break;case "vg":c.vg=true;break;case "ll":c.ll=true;break;case "ind":c.ind=true;break;case "df":c.df=true;break;case "hx":c.hx=true;break;}
    c.wd=Integer.parseInt(f[3]);return c;}
  public static void main(String[] a) throws Exception {
    int ok=0,bad=0,chooseBad=0; long t0=System.nanoTime();
    for(String line:Files.readAllLines(Paths.get(a[0]))){
      String[] p=line.split("\\|"); int ply=Integer.parseInt(p[0]); String[] l=p[1].split(",");
      Creature[] me=Arrays.stream(p[3].split(";")).map(XCheck::dec).toArray(Creature[]::new);
      Creature[] op=Arrays.stream(p[4].split(";")).map(XCheck::dec).toArray(Creature[]::new);
      State st=new State((1<<me.length)-1,(1<<op.length)-1,Integer.parseInt(l[0]),Integer.parseInt(l[1]),0,Integer.parseInt(p[2]));
      CombatDrillSolver s=new CombatDrillSolver(me,op,ply);
      int[][] r=s.rootOptions(st);
      StringBuilder sb=new StringBuilder(); int best=Integer.MIN_VALUE;
      for(int i=0;i<r.length;i++){ if(i>0)sb.append(' '); sb.append(r[i][0]).append(':').append(r[i][1]); best=Math.max(best,r[i][1]); }
      if(sb.toString().equals(p[5])) ok++; else { bad++; if(bad<5){System.out.println("JS  "+p[5]);System.out.println("JAV "+sb);} }
      // choose() with a random preferred subset must reach the same best value (fresh solver)
      CombatDrillSolver s2=new CombatDrillSolver(me,op,ply);
      int pref=r[new Random(line.hashCode()).nextInt(r.length)][0];
      if(s2.choose(st,pref).value!=best) chooseBad++;
    }
    System.out.printf("ok=%d bad=%d chooseBad=%d  %.1fs%n",ok,bad,chooseBad,(System.nanoTime()-t0)/1e9);
  }
}
