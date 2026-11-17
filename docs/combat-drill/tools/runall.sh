#!/bin/bash
# usage: runall.sh tag "deckA deckB" "profA profB" chunks gamesPerChunk
cd /home/claude/forge/forge-gui
J=../forge-gui-desktop/target/forge-gui-desktop-2.0.17-SNAPSHOT-jar-with-dependencies.jar
while read -r tag decks profs chunks n; do
  for c in $(seq 1 $chunks); do
    log=/home/claude/work/runs/${tag}_${c}.log
    [ -s "$log" ] && grep -q "COMBAT-DRILL\|Match Result" "$log" && [ "$(grep -c 'Game Result' $log)" -ge "$n" ] && continue
    java -Xmx3g -jar $J sim -D /home/claude/work/decks -d ${decks/,/ } -n $n -a ${profs/,/ } -mc off -q > $log 2>&1
    echo "$(date +%T) $tag $c $(grep 'Match Result' $log | tail -1)" >> /home/claude/work/runs/progress.txt
  done
done < /home/claude/work/plan.txt
echo "$(date +%T) ALL DONE" >> /home/claude/work/runs/progress.txt
